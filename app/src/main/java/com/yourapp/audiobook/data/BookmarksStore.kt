package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    suspend fun save(bookId: String, bookmarks: List<Bookmark>) {
        appContext.bookmarksDataStore.edit { prefs ->
            prefs[key(bookId)] = encode(bookmarks)
        }
    }

    suspend fun load(bookId: String): List<Bookmark> {
        val raw = appContext.bookmarksDataStore.data.first()[key(bookId)] ?: return emptyList()
        return decode(raw)
    }

    private fun key(bookId: String): androidx.datastore.preferences.core.Preferences.Key<String> =
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
