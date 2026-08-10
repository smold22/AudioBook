package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.IgnoreSection
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookListState(
    val books: List<Book> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
)

abstract class BookListViewModel(app: Application) : AndroidViewModel(app) {

    protected val appContext = app as AudioBookApplication

    private val _state = MutableStateFlow(BookListState())
    val state: StateFlow<BookListState> = _state.asStateFlow()

    protected var currentPage = 0
    private var loadingJob: kotlinx.coroutines.Job? = null
    private var generation = 0L
    private var lastSourceId: String? = null

    protected abstract suspend fun loadPage(page: Int): List<Book>

    protected open suspend fun currentSourceId(): String? = null

    protected fun isBlockError(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("HTTP 400") || message.contains("HTTP 403") || message.contains("HTTP 429")
    }

    open fun refreshForSource(sourceId: String?) {
        if (sourceId == lastSourceId) return
        lastSourceId = sourceId
        refresh()
    }

    fun refresh() {
        generation++
        currentPage = 0
        loadingJob?.cancel()
        _state.update { it.copy(books = emptyList(), loading = false, error = null, endReached = false) }
        loadMore()
    }

    fun loadMore() {
        if (loadingJob?.isActive == true) return
        val gen = generation
        loadingJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val currentSource = currentSourceId()
                if (currentSource != null && appContext.sourceCooldown.isCoolingDown(currentSource)) {
                    _state.update {
                        it.copy(loading = false, endReached = true, error = "Источник временно недоступен, попробуйте позже")
                    }
                    return@launch
                }
                var page = currentPage + 1
                var attemptsLeft = 3
                val loaded = mutableListOf<Book>()
                var visible = emptyList<Book>()
                val deadKeys = appContext.deadBooksStore.snapshot()
                while (true) {
                    val items = loadPage(page)
                    if (gen != generation) return@launch
                    items.forEach { appContext.bookCache.put(it) }
                    val kept = filterIgnored(items).filterNot { "${it.sourceId}:${it.id}" in deadKeys }
                    loaded += items
                    if (kept.isNotEmpty() || items.isEmpty() || attemptsLeft-- <= 0) {
                        currentPage = page
                        visible = kept
                        break
                    }
                    page += 1
                }
                if (visible.isNotEmpty() || loaded.isEmpty()) {
                    _state.update {
                        val merged = it.books + visible
                        it.copy(books = merged.distinctBy { b -> "${b.sourceId}:${b.id}" }, loading = false)
                    }
                } else {
                    _state.update { it.copy(loading = false, endReached = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                if (isBlockError(e)) {
                    currentSourceId()?.let { appContext.sourceCooldown.mark(it) }
                }
                _state.update { it.copy(loading = false, error = e.message ?: "Ошибка загрузки") }
            }
        }
    }

    private suspend fun filterIgnored(books: List<Book>): List<Book> {
        val genres = appContext.settingsStore.ignored(IgnoreSection.GENRE)
        val authors = appContext.settingsStore.ignored(IgnoreSection.AUTHOR)
        val readers = appContext.settingsStore.ignored(IgnoreSection.READER)
        if (genres.isEmpty() && authors.isEmpty() && readers.isEmpty()) return books
        return books.filterNot { book ->
            genres.any { book.genre?.contains(it, ignoreCase = true) == true } ||
                authors.any { book.author?.contains(it, ignoreCase = true) == true } ||
                readers.any { book.reader?.contains(it, ignoreCase = true) == true }
        }
    }
}
