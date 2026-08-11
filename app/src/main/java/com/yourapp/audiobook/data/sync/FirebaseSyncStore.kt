package com.yourapp.audiobook.data.sync

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
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

/**
 * Хранилище данных синхронизации в Cloud Firestore:
 * документ users/{uid} с полями-снимками состояния приложения.
 */
class FirebaseSyncStore(private val gson: Gson) {

    private val firestore = FirebaseFirestore.getInstance()

    suspend fun read(uid: String): SyncData? {
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

    suspend fun write(uid: String, data: SyncData) {
        val values = hashMapOf<String, Any?>(
            FIELD_FAVORITES to gson.toJson(data.favorites),
            FIELD_HISTORY to gson.toJson(data.history),
            FIELD_PROGRESS to data.progress,
            FIELD_BOOKMARKS to gson.toJson(data.bookmarks),
            FIELD_SETTINGS to data.settings,
            FIELD_UPDATED_AT to data.updatedAtMs,
        )
        firestore.collection(COLLECTION_USERS).document(uid).set(values).awaitTask()
    }

    suspend fun delete(uid: String) {
        firestore.collection(COLLECTION_USERS).document(uid).delete().awaitTask()
    }

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

    @Suppress("UNCHECKED_CAST")
    private fun decodeMap(value: Any?): Map<String, String> =
        value as? Map<String, String> ?: emptyMap()

    companion object {
        private const val COLLECTION_USERS = "users"
        private const val FIELD_FAVORITES = "favorites"
        private const val FIELD_HISTORY = "history"
        private const val FIELD_PROGRESS = "progress"
        private const val FIELD_BOOKMARKS = "bookmarks"
        private const val FIELD_SETTINGS = "settings"
        private const val FIELD_UPDATED_AT = "updatedAtMs"
    }
}
