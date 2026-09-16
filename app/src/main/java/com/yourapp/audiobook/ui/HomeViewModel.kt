package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.data.HomeGenre
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : BookListViewModel(app) {

    private val _newBooks = MutableStateFlow<List<Book>>(emptyList())
    val newBooks: StateFlow<List<Book>> = _newBooks.asStateFlow()

    private val _activeHomeGenres = MutableStateFlow<List<HomeGenre>>(emptyList())
    val activeHomeGenres: StateFlow<List<HomeGenre>> = _activeHomeGenres.asStateFlow()

    init {
        refreshNewBooks()
    }

    override fun refresh() {
        super.refresh()
        refreshNewBooks()
    }

    override suspend fun currentSourceId(): String? = appContext.activeSource()?.id

    override suspend fun loadPage(page: Int): List<Book> {
        val source = appContext.activeSource() ?: return emptyList()
        val genres = appContext.resolveHomeGenres()
        _activeHomeGenres.value = genres
        if (genres.isNotEmpty()) {
            return coroutineScope {
                genres.map { genre ->
                    async {
                        source.books(genre.url, page)
                            .map { if (it.genre == null) it.copy(genre = genre.name) else it }
                    }
                }.awaitAll().flatten()
            }
        }
        return source.home(page)
    }

    private fun refreshNewBooks() {
        viewModelScope.launch {
            _newBooks.value = appContext.newBooksAll(1).distinctBy { "${it.sourceId}:${it.id}" }
        }
    }
}