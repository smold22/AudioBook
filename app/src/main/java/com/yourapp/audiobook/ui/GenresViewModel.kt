package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Genre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GenresState(
    val genres: List<Genre> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class GenresViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app as AudioBookApplication

    private val _state = MutableStateFlow(GenresState())
    val state: StateFlow<GenresState> = _state.asStateFlow()

    private var lastSourceId: String? = null

    init {
        load()
    }

    fun loadForSource(sourceId: String?) {
        if (sourceId == lastSourceId) return
        lastSourceId = sourceId
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val source = appContext.activeSource()
                val genres = source?.genres().orEmpty()
                _state.update { it.copy(genres = genres, loading = false) }
                loadCounts(source, genres)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Ошибка загрузки") }
            }
        }
    }

    private fun loadCounts(source: AudiobookSource?, genres: List<Genre>) {
        if (source == null) return
        if (source.id in NO_COUNT_SOURCES) return
        if (genres.size > MAX_COUNT_GENRES) return
        if (appContext.sourceCooldown.isCoolingDown(source.id)) return
        val pending = genres.filter { it.bookCount == null }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            pending.chunked(COUNT_CONCURRENCY).forEach { chunk ->
                val results = chunk.map { genre ->
                    async(Dispatchers.IO) {
                        genre.url to (runCatching { source.genreBookCount(genre.url) }.getOrNull()
                            ?: runCatching { source.genreBookCount(genre.url) }.getOrNull())
                    }
                }.awaitAll()
                withContext(Dispatchers.Main) {
                    _state.update { st ->
                        val updated = st.genres.map { g ->
                            val count = results.firstOrNull { it.first == g.url }?.second
                            if (count != null && count > 0) g.copy(bookCount = count) else g
                        }
                        st.copy(genres = updated)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_COUNT_GENRES = 150
        const val COUNT_CONCURRENCY = 4
        val NO_COUNT_SOURCES = setOf("uknig")
    }
}
