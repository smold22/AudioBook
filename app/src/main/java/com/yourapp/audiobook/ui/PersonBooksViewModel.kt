package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PersonBooksViewModel(
    app: Application,
    private val query: String,
    private val mode: String,
) : BookListViewModel(app) {

    private val queryNorm = normalize(query)

    /** Название источника, в котором прямо сейчас идёт поиск (для индикатора в UI). */
    private val _searchingIn = MutableStateFlow<String?>(null)
    val searchingIn: StateFlow<String?> = _searchingIn.asStateFlow()

    private var searchJob: Job? = null
    private var searchGeneration = 0L

    override suspend fun loadPage(page: Int): List<Book> {
        // Не используется: загрузка идёт через собственный прогрессивный [loadMore].
        return emptyList()
    }

    override suspend fun currentSourceId(): String? = appContext.activeSource()?.id

    override fun refresh() {
        searchGeneration++
        searchJob?.cancel()
        currentPage = 0
        mutateState { BookListState() }
        loadMore()
    }

    /**
     * Ищет книги автора/чтеца по всем источникам последовательно:
     * результаты каждого источника добавляются в список сразу после его опроса,
     * а в [searchingIn] публикуется название источника, который ищется сейчас.
     */
    override fun loadMore() {
        if (searchJob?.isActive == true) return
        if (state.value.endReached) return
        val gen = searchGeneration
        searchJob = viewModelScope.launch {
            mutateState { it.copy(loading = true, error = null) }
            try {
                val hidden = appContext.settingsStore.hiddenSourceIds()
                val sources = appContext.sourceRegistry.sources.filter { it.id !in hidden }
                val deadKeys = appContext.deadBooksStore.snapshot()
                var page = currentPage + 1
                var totalKept = 0
                var pagesFetched = 0
                var reachedEnd = false
                while (true) {
                    if (gen != searchGeneration) return@launch
                    var anyOnPage = false
                    for (source in sources) {
                        if (gen != searchGeneration) return@launch
                        if (appContext.sourceCooldown.isCoolingDown(source.id)) continue
                        _searchingIn.value = source.name
                        val found = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            runCatching { source.search(query, page) }
                                .onFailure { if (isBlockError(it)) appContext.sourceCooldown.mark(source.id) }
                                .getOrDefault(emptyList())
                        } ?: emptyList()
                        if (gen != searchGeneration) return@launch
                        val kept = filterIgnored(found)
                            .filter { book ->
                                val person = if (mode == "reader") book.reader else book.author
                                person != null && personMatches(person)
                            }
                            .filterNot { "${it.sourceId}:${it.id}" in deadKeys }
                        if (kept.isEmpty()) continue
                        anyOnPage = true
                        kept.forEach { appContext.bookCache.put(it) }
                        mutateState { st ->
                            val merged = (st.books + kept).distinctBy { "${it.sourceId}:${it.id}" }
                            st.copy(books = merged)
                        }
                        totalKept += kept.size
                    }
                    currentPage = page
                    pagesFetched++
                    if (!anyOnPage) {
                        reachedEnd = true
                        break
                    }
                    if (totalKept >= TARGET_BOOKS || pagesFetched >= MAX_PAGES_PER_LOAD) break
                    page++
                }
                if (gen == searchGeneration) {
                    mutateState { it.copy(loading = false, endReached = reachedEnd) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != searchGeneration) return@launch
                mutateState { it.copy(loading = false, error = e.message ?: "Ошибка загрузки") }
            } finally {
                if (gen == searchGeneration) {
                    _searchingIn.value = null
                }
            }
        }
    }

    private fun personMatches(person: String): Boolean {
        val p = normalize(person)
        if (p.isBlank()) return false
        if (p.contains(queryNorm)) return true
        val qTokens = queryNorm.split(' ').filter { it.length >= 3 }
        if (qTokens.isEmpty()) return false
        val pTokens = p.split(' ').filter { it.isNotEmpty() }
        return qTokens.any { q ->
            pTokens.any { t ->
                t.length >= 3 && (t == q || t.startsWith(q) || q.startsWith(t))
            }
        }
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace('ё', 'е')
            .replace(Regex("""\(.*?\)"""), "")
            .replace(Regex("""[^a-zа-яё0-9]"""), "")

    private companion object {
        const val SOURCE_TIMEOUT_MS = 25_000L
    }
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