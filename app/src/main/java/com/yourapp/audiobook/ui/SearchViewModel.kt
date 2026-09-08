package com.yourapp.audiobook.ui

import android.app.Application
import com.yourapp.audiobook.source.api.Book

class SearchViewModel(app: Application) : BookListViewModel(app) {

    private var query: String = ""
    private var sourceId: String? = null
    private var exactSearch = false
    private var searchByTitle = true
    private var searchByAuthor = true
    private var searchByReader = true

    fun search(newQuery: String) {
        query = newQuery.trim()
        refresh()
    }

    fun setSource(newSourceId: String?) {
        if (sourceId == newSourceId) return
        sourceId = newSourceId
        refresh()
    }

    /** Точный поиск: искать целое слово или словосочетание как есть. */
    fun setExactSearch(enabled: Boolean) {
        if (exactSearch == enabled) return
        exactSearch = enabled
        refresh()
    }

    /** Поля, по которым фильтровать результаты: название, автор и/или чтец. */
    fun setSearchFields(byTitle: Boolean, byAuthor: Boolean, byReader: Boolean) {
        if (searchByTitle == byTitle && searchByAuthor == byAuthor && searchByReader == byReader) return
        searchByTitle = byTitle
        searchByAuthor = byAuthor
        searchByReader = byReader
        refresh()
    }

    fun selectedSourceId(): String? = sourceId

    override suspend fun currentSourceId(): String? = sourceId

    override suspend fun loadPage(page: Int): List<Book> {
        if (query.isBlank()) return emptyList()
        val target = sourceId
        val results = if (target == null) {
            appContext.searchAll(query, page)
        } else {
            appContext.sourceRegistry.get(target)?.search(query, page).orEmpty()
        }
        return results.filter(::matchesQuery)
    }

    /** Оставляет только книги, релевантные запросу: все слова запроса встречаются в названии или авторе. */
    private fun matchesQuery(book: Book): Boolean {
        if (query.isBlank()) return true
        if (exactSearch) return matchesExact(book)
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return true
        val haystack = bookHaystack(book)
        return words.all { haystack.contains(it) }
    }

    /**
     * Точное совпадение: запрос встречается как целое слово (если одно слово)
     * или как точное словосочетание в названии или авторе.
     */
    private fun matchesExact(book: Book): Boolean {
        val q = query.lowercase()
        val pattern = Regex("(?<![а-яёa-z0-9])" + Regex.escape(q) + "(?![а-яёa-z0-9])")
        return pattern.containsMatchIn(bookHaystack(book))
    }

    private fun bookHaystack(book: Book): String = buildString {
        val searchTitle = searchByTitle || (!searchByAuthor && !searchByReader)
        if (searchTitle) append(book.title.lowercase())
        if (searchByAuthor) book.author?.let { append(' ').append(it.lowercase()) }
        if (searchByReader) book.reader?.let { append(' ').append(it.lowercase()) }
    }
}
