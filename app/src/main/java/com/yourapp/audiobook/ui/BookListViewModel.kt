package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.AuthorGender
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
    private var hideFemaleAuthors = false
    private var deadKeys: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            appContext.settingsStore.hideFemaleAuthors.collect { enabled ->
                if (enabled != hideFemaleAuthors) {
                    hideFemaleAuthors = enabled
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            appContext.deadBooksStore.deadKeys.collect { keys ->
                deadKeys = keys
                _state.update { it.copy(books = it.books.filterNot { b -> "${b.sourceId}:${b.id}" in keys }) }
            }
        }
    }

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
        if (_state.value.endReached) return
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
                var visible = emptyList<Book>()
                var reachedEnd = false
                val deadKeysSnapshot = deadKeys
                var pagesFetched = 0
                while (true) {
                    val items = loadPage(page)
                    if (gen != generation) return@launch
                    items.forEach { appContext.bookCache.put(it) }
                    pagesFetched++
                    val kept = filterIgnored(items).filterNot { "${it.sourceId}:${it.id}" in deadKeysSnapshot }
                    if (items.isEmpty()) {
                        currentPage = page
                        reachedEnd = true
                        break
                    }
                    visible += kept
                    currentPage = page
                    if (visible.size >= TARGET_BOOKS || pagesFetched >= MAX_PAGES_PER_LOAD) break
                    if (kept.isEmpty() && --attemptsLeft <= 0) {
                        reachedEnd = true
                        break
                    }
                    page += 1
                }
                if (visible.isNotEmpty() || reachedEnd) {
                    _state.update {
                        val merged = it.books + visible
                        it.copy(
                            books = merged.distinctBy { b -> "${b.sourceId}:${b.id}" },
                            loading = false,
                            endReached = reachedEnd,
                        )
                    }
                } else {
                    _state.update { it.copy(loading = false) }
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
        var filtered = books
        if (genres.isNotEmpty() || authors.isNotEmpty() || readers.isNotEmpty()) {
            filtered = filtered.filterNot { book ->
                genres.any { book.genre?.contains(it, ignoreCase = true) == true } ||
                    authors.any { book.author?.contains(it, ignoreCase = true) == true } ||
                    readers.any { book.reader?.contains(it, ignoreCase = true) == true }
            }
        }
        if (hideFemaleAuthors) {
            filtered = filtered.filterNot { book ->
                book.author != null && AuthorGender.isFemaleAuthor(book.author!!)
            }
        }
        return filtered
    }

    private companion object {
        const val TARGET_BOOKS = 40
        const val MAX_PAGES_PER_LOAD = 20
    }
}
