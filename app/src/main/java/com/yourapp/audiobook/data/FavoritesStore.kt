package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

class FavoritesStore(context: Context) {

    private val appContext = context.applicationContext

    private val changeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /** Поток сигналов об изменении данных (для автосинхронизации). */
    val changes: Flow<Unit> = changeSignal.asSharedFlow()

    private val favoritesFlow = appContext.favoritesDataStore.data.map { prefs ->
        prefs[booksKey]?.let(::decodeList).orEmpty()
    }

    val favorites: Flow<List<Book>> = favoritesFlow

    val favoriteKeys: Flow<Set<String>> = favoritesFlow.map { books ->
        books.map(::keyOf).toSet()
    }

    suspend fun toggle(book: Book) {
        appContext.favoritesDataStore.edit { prefs ->
            val current = prefs[booksKey]?.let(::decodeList).orEmpty()
            val bookKey = keyOf(book)
            val updated = if (current.any { keyOf(it) == bookKey }) {
                current.filterNot { keyOf(it) == bookKey }
            } else {
                current + book
            }
            prefs[booksKey] = encodeList(updated)
        }
        changeSignal.emit(Unit)
    }

    suspend fun remove(bookKey: String) {
        appContext.favoritesDataStore.edit { prefs ->
            val current = prefs[booksKey]?.let(::decodeList).orEmpty()
            prefs[booksKey] = encodeList(current.filterNot { keyOf(it) == bookKey })
        }
        changeSignal.emit(Unit)
    }

    suspend fun snapshot(): List<Book> = favoritesFlow.first()

    suspend fun restore(books: List<Book>) {
        appContext.favoritesDataStore.edit { prefs ->
            prefs[booksKey] = encodeList(books)
        }
        changeSignal.emit(Unit)
    }

    private fun decodeList(raw: String): List<Book> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::decodeBook)
        }
    }.getOrDefault(emptyList())

    private fun encodeList(books: List<Book>): String =
        JSONArray().apply { books.forEach { put(encodeBook(it)) } }.toString()

    private fun encodeBook(book: Book): JSONObject = JSONObject().apply {
        put("sourceId", book.sourceId)
        put("id", book.id)
        put("title", book.title)
        put("url", book.url)
        putOpt("coverUrl", book.coverUrl)
        putOpt("author", book.author)
        putOpt("reader", book.reader)
        putOpt("durationText", book.durationText)
        putOpt("genre", book.genre)
        putOpt("seriesTitle", book.seriesTitle)
        putOpt("seriesIndex", book.seriesIndex)
        putOpt("seriesUrl", book.seriesUrl)
    }

    private fun decodeBook(json: JSONObject): Book = Book(
        sourceId = json.optString("sourceId"),
        id = json.optString("id"),
        title = json.optString("title"),
        url = json.optString("url"),
        coverUrl = json.optString("coverUrl").takeIf { it.isNotBlank() },
        author = json.optString("author").takeIf { it.isNotBlank() },
        reader = json.optString("reader").takeIf { it.isNotBlank() },
        durationText = json.optString("durationText").takeIf { it.isNotBlank() },
        genre = json.optString("genre").takeIf { it.isNotBlank() },
        seriesTitle = json.optString("seriesTitle").takeIf { it.isNotBlank() },
        seriesIndex = if (json.has("seriesIndex") && !json.isNull("seriesIndex")) {
            json.optInt("seriesIndex").takeIf { it > 0 }
        } else {
            null
        },
        seriesUrl = json.optString("seriesUrl").takeIf { it.isNotBlank() },
    )

    private fun keyOf(book: Book): String = "${book.sourceId}:${book.id}"

    private companion object {
        val booksKey = stringPreferencesKey("books")
    }
}