package com.yourapp.audiobook.data.sync

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourapp.audiobook.data.Bookmark
import com.yourapp.audiobook.data.HistoryEntry
import com.yourapp.audiobook.source.api.Book
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/** Резервная копия в облаке: идентификатор и время создания. */
data class BackupInfo(
    val id: String,
    val createdAtMs: Long,
)

/**
 * Хранилище синхронизации в Cloud Firestore с историей резервных копий.
 * Каждая синхронизация создаёт новую копию в коллекции users/{uid}/backups,
 * поэтому можно восстановить состояние на любой момент времени.
 */
class FirebaseSyncStore(private val gson: Gson) {

    private val firestore = FirebaseFirestore.getInstance()

    /** Последняя резервная копия. Если истории ещё нет — читает устаревший документ users/{uid}. */
    suspend fun read(uid: String): SyncData? {
        val latest = backupsRef(uid)
            .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .awaitTask()
            .documents.firstOrNull()
        if (latest != null) {
            return readBackup(uid, latest.id)
        }
        val doc = firestore.collection(COLLECTION_USERS).document(uid).get().awaitTask()
        if (!doc.exists()) return null
        return SyncData(
            version = 2,
            updatedAtMs = doc.getLong(FIELD_UPDATED_AT) ?: 0L,
            favorites = decodeList<Book>(doc.getString(FIELD_FAVORITES)),
            history = decodeList<HistoryEntry>(doc.getString(FIELD_HISTORY)),
            progress = decodeMap(doc.get(FIELD_PROGRESS)),
            bookmarks = decodeBookmarks(doc.getString(FIELD_BOOKMARKS)),
            settings = decodeMap(doc.get(FIELD_SETTINGS)),
        )
    }

    /** Список доступных резервных копий, от новых к старым. */
    suspend fun listBackups(uid: String): List<BackupInfo> {
        val snapshots = backupsRef(uid)
            .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
            .get()
            .awaitTask()
        return snapshots.documents.mapNotNull { doc ->
            val createdAt = doc.getLong(FIELD_CREATED_AT)
                ?: doc.id.toLongOrNull()
                ?: return@mapNotNull null
            BackupInfo(id = doc.id, createdAtMs = createdAt)
        }
    }

    /** Конкретная резервная копия по идентификатору. */
    suspend fun readBackup(uid: String, backupId: String): SyncData? {
        val doc = backupsRef(uid).document(backupId).get().awaitTask()
        if (!doc.exists()) return null
        return SyncData(
            version = 2,
            updatedAtMs = doc.getLong(FIELD_UPDATED_AT) ?: 0L,
            favorites = decodeList<Book>(doc.getString(FIELD_FAVORITES)),
            history = decodeList<HistoryEntry>(doc.getString(FIELD_HISTORY)),
            progress = decodeMap(doc.get(FIELD_PROGRESS)),
            bookmarks = decodeBookmarks(doc.getString(FIELD_BOOKMARKS)),
            settings = decodeMap(doc.get(FIELD_SETTINGS)),
        )
    }

    /** Создаёт новую резервную копию и удаляет самые старые. */
    suspend fun write(uid: String, data: SyncData) {
        val now = System.currentTimeMillis()
        val values = hashMapOf<String, Any?>(
            FIELD_FAVORITES to gson.toJson(data.favorites),
            FIELD_HISTORY to gson.toJson(data.history),
            FIELD_PROGRESS to data.progress,
            FIELD_BOOKMARKS to gson.toJson(data.bookmarks),
            FIELD_SETTINGS to data.settings,
            FIELD_UPDATED_AT to data.updatedAtMs,
            FIELD_CREATED_AT to now,
        )
        backupsRef(uid).add(values).awaitTask()
        prune(uid)
    }

    private suspend fun prune(uid: String) {
        val docs = backupsRef(uid)
            .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
            .get()
            .awaitTask()
        for (doc in docs.documents.drop(MAX_BACKUPS)) {
            doc.reference.delete().awaitTask()
        }
    }

    private fun backupsRef(uid: String) =
        firestore.collection(COLLECTION_USERS).document(uid).collection(COLLECTION_BACKUPS)

    private inline fun <reified T> decodeList(raw: String?): List<T> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            gson.fromJson<List<T>>(
                raw,
                TypeToken.getParameterized(List::class.java, T::class.java).type,
            )
        }.getOrDefault(emptyList())
    }

    private fun decodeBookmarks(raw: String?): Map<String, List<Bookmark>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, List<Bookmark>>>(
                raw,
                TypeToken.getParameterized(
                    Map::class.java,
                    String::class.java,
                    TypeToken.getParameterized(List::class.java, Bookmark::class.java).type,
                ).type,
            )
        }.getOrDefault(emptyMap())
    }

    private fun decodeMap(value: Any?): Map<String, String> {
        if (value !is Map<*, *>) return emptyMap()
        val result = mutableMapOf<String, String>()
        value.forEach { (key, item) ->
            if (key is String && item is String) {
                result[key] = item
            }
        }
        return result
    }

    companion object {
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_BACKUPS = "backups"
        private const val FIELD_FAVORITES = "favorites"
        private const val FIELD_HISTORY = "history"
        private const val FIELD_PROGRESS = "progress"
        private const val FIELD_BOOKMARKS = "bookmarks"
        private const val FIELD_SETTINGS = "settings"
        private const val FIELD_UPDATED_AT = "updatedAtMs"
        private const val FIELD_CREATED_AT = "createdAtMs"

        /** Сколько резервных копий хранить в облаке. */
        const val MAX_BACKUPS = 30
    }
}
