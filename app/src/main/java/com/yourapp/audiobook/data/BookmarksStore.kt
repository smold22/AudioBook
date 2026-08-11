package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.bookmarksDataStore by preferencesDataStore(name = "bookmarks")

data class Bookmark(
    val id: String,
    val trackIndex: Int,
    val positionMs: Long,
    val createdAtMs: Long,
)

class BookmarksStore(context: Context) {

    private val appContext = context.applicationContext

    private val changeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /** Поток сигналов об изменении данных (для автосинхронизации). */
    val changes: Flow<Unit> = changeSignal.asSharedFlow()

    suspend fun save(bookId: String, bookmarks: List<Bookmark>) {
        appContext.bookmarksDataStore.edit { prefs ->
            prefs[key(bookId)] = encode(bookmarks)
        }
        changeSignal.emit(Unit)
    }

    suspend fun load(bookId: String): List<Bookmark> {
        val raw = appContext.bookmarksDataStore.data.first()[key(bookId)] ?: return emptyList()
        return decode(raw)
    }

    suspend fun snapshot(): Map<String, List<Bookmark>> {
        val prefs = appContext.bookmarksDataStore.data.first()
        return prefs.asMap().entries
            .filter { it.key.name.startsWith("book:") }
            .mapNotNull { (prefKey, _) ->
                val raw = prefs[stringPreferencesKey(prefKey.name)] ?: return@mapNotNull null
                prefKey.name.removePrefix("book:") to decode(raw)
            }
            .toMap()
    }

    suspend fun restore(snapshot: Map<String, List<Bookmark>>) {
        appContext.bookmarksDataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("book:") }
                .forEach { prefs.remove(it) }
            snapshot.forEach { (bookId, bookmarks) ->
                if (bookmarks.isNotEmpty()) prefs[key(bookId)] = encode(bookmarks)
            }
        }
        changeSignal.emit(Unit)
    }

    private fun key(bookId: String): Preferences.Key<String> =
        stringPreferencesKey("book:$bookId")

    private fun encode(bookmarks: List<Bookmark>): String =
        JSONArray().apply {
            bookmarks.forEach { bookmark ->
                put(
                    JSONObject().apply {
                        put("id", bookmark.id)
                        put("track", bookmark.trackIndex)
                        put("pos", bookmark.positionMs)
                        put("created", bookmark.createdAtMs)
                    },
                )
            }
        }.toString()

    private fun decode(raw: String): List<Bookmark> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            Bookmark(
                id = json.optString("id"),
                trackIndex = json.optInt("track"),
                positionMs = json.optLong("pos"),
                createdAtMs = json.optLong("created"),
            )
        }
    }.getOrDefault(emptyList())
}