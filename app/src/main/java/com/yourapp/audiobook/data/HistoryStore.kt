package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyDataStore by preferencesDataStore(name = "history")

data class HistoryEntry(
    val book: Book,
    val playedAtMs: Long,
)

class HistoryStore(context: Context) {

    private val appContext = context.applicationContext

    val history: Flow<List<HistoryEntry>> = appContext.historyDataStore.data.map { prefs ->
        prefs[entriesKey]?.let(::decodeList).orEmpty()
    }

    suspend fun record(book: Book) {
        appContext.historyDataStore.edit { prefs ->
            val current = prefs[entriesKey]?.let(::decodeList).orEmpty()
            val bookKey = "${book.sourceId}:${book.id}"
            val updated = listOf(HistoryEntry(book, System.currentTimeMillis())) +
                current.filterNot { "${it.book.sourceId}:${it.book.id}" == bookKey }
            prefs[entriesKey] = encodeList(updated.take(MAX_ENTRIES))
        }
    }

    suspend fun remove(bookKey: String) {
        appContext.historyDataStore.edit { prefs ->
            val current = prefs[entriesKey]?.let(::decodeList).orEmpty()
            prefs[entriesKey] = encodeList(
                current.filterNot { "${it.book.sourceId}:${it.book.id}" == bookKey },
            )
        }
    }

    suspend fun clear() {
        appContext.historyDataStore.edit { it.remove(entriesKey) }
    }

    suspend fun snapshot(): List<HistoryEntry> =
        appContext.historyDataStore.data
            .map { prefs -> prefs[entriesKey]?.let(::decodeList).orEmpty() }
            .first()

    suspend fun restore(entries: List<HistoryEntry>) {
        appContext.historyDataStore.edit { prefs ->
            prefs[entriesKey] = encodeList(entries)
        }
    }

    private fun decodeList(raw: String): List<HistoryEntry> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val bookJson = json.optJSONObject("book") ?: return@mapNotNull null
            HistoryEntry(
                book = Book(
                    sourceId = bookJson.optString("sourceId"),
                    id = bookJson.optString("id"),
                    title = bookJson.optString("title"),
                    url = bookJson.optString("url"),
                    coverUrl = bookJson.optString("coverUrl").takeIf { it.isNotBlank() },
                    author = bookJson.optString("author").takeIf { it.isNotBlank() },
                    reader = bookJson.optString("reader").takeIf { it.isNotBlank() },
                    durationText = bookJson.optString("durationText").takeIf { it.isNotBlank() },
                    genre = bookJson.optString("genre").takeIf { it.isNotBlank() },
                    seriesTitle = bookJson.optString("seriesTitle").takeIf { it.isNotBlank() },
                    seriesIndex = if (bookJson.has("seriesIndex") && !bookJson.isNull("seriesIndex")) {
                        bookJson.optInt("seriesIndex").takeIf { it > 0 }
                    } else {
                        null
                    },
                    seriesUrl = bookJson.optString("seriesUrl").takeIf { it.isNotBlank() },
                ),
                playedAtMs = json.optLong("playedAtMs"),
            )
        }
    }.getOrDefault(emptyList())

    private fun encodeList(entries: List<HistoryEntry>): String =
        JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject().apply {
                        put("playedAtMs", entry.playedAtMs)
                        put(
                            "book",
                            JSONObject().apply {
                                put("sourceId", entry.book.sourceId)
                                put("id", entry.book.id)
                                put("title", entry.book.title)
                                put("url", entry.book.url)
                                putOpt("coverUrl", entry.book.coverUrl)
                                putOpt("author", entry.book.author)
                                putOpt("reader", entry.book.reader)
                                putOpt("durationText", entry.book.durationText)
                                putOpt("genre", entry.book.genre)
                                putOpt("seriesTitle", entry.book.seriesTitle)
                                putOpt("seriesIndex", entry.book.seriesIndex)
                                putOpt("seriesUrl", entry.book.seriesUrl)
                            },
                        )
                    },
                )
            }
        }.toString()

    private companion object {
        val entriesKey = stringPreferencesKey("entries")
        const val MAX_ENTRIES = 100
    }
}