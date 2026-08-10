package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.source.api.Book

class PersonBooksViewModel(
    app: Application,
    private val query: String,
    private val mode: String,
) : BookListViewModel(app) {

    private val queryNorm = normalize(query)
    private val queryKey = queryNorm.split(' ').maxByOrNull { it.length } ?: queryNorm

    override suspend fun loadPage(page: Int): List<Book> {
        val books = appContext.searchAll(query, page)
        return books.filter { book ->
            val person = if (mode == "reader") book.reader else book.author
            person != null && personMatches(person)
        }
    }

    override suspend fun currentSourceId(): String? = appContext.activeSource()?.id

    private fun personMatches(person: String): Boolean {
        val p = normalize(person)
        if (queryKey.isBlank()) return p.contains(queryNorm) || queryNorm.contains(p)
        return p.split(' ').any { token ->
            token == queryKey ||
                (token.startsWith(queryKey) && token.length <= queryKey.length + 3)
        }
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace('ё', 'е')
            .replace(Regex("""\(.*?\)"""), "")
            .replace(Regex("""[^a-zа-яё0-9]"""), "")
}

class PersonBooksViewModelFactory(
    private val app: AudioBookApplication,
    private val query: String,
    private val mode: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PersonBooksViewModel(app, query, mode) as T
}
