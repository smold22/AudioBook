package com.yourapp.audiobook.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.TrackPosition
import com.yourapp.audiobook.player.PlaybackService
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookScreenState(
    val book: Book? = null,
    val details: BookDetails? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val progress: TrackPosition? = null,
    val currentTrackIndex: Int? = null,
    val related: List<Book> = emptyList(),
    val seriesBooks: List<Book> = emptyList(),
)

class BookViewModel(app: Application, private val bookKey: String) : AndroidViewModel(app) {

    private val appContext = app as AudioBookApplication

    private val _state = MutableStateFlow(BookScreenState())
    val state: StateFlow<BookScreenState> = _state.asStateFlow()

    init {
        val cached = appContext.bookCache.get(bookKey)
        _state.update { it.copy(book = cached) }
        load()
        viewModelScope.launch {
            while (true) {
                delay(1000)
                updateLiveProgress()
            }
        }
    }

    private fun updateLiveProgress() {
        val book = _state.value.book ?: return
        val controller = appContext.playerController
        val nowPlaying = controller.nowPlaying.value ?: return
        if (nowPlaying.book.id != book.id) return
        val position = controller.player.currentPosition.coerceAtLeast(0L)
        val track = controller.player.currentMediaItemIndex
        if (position <= 0 && !controller.player.isPlaying) return
        _state.update {
            it.copy(progress = TrackPosition(track, position), currentTrackIndex = track)
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val offline = runCatching { appContext.downloadManager.offlineDetails(bookKey) }.getOrNull()
            if (offline != null) {
                val book = offline.book.copy(title = offline.book.title.ifBlank { _state.value.book?.title.orEmpty() })
                appContext.bookCache.put(book)
                val progress = appContext.progressStore.load(book.id)
                val currentIndex = appContext.playerController.player.currentMediaItemIndex
                    .takeIf {
                        appContext.playerController.player.mediaItemCount > 0 &&
                            appContext.playerController.nowPlaying.value?.book?.id == book.id
                    }
                _state.update {
                    it.copy(
                        book = book,
                        details = offline,
                        loading = false,
                        progress = progress,
                        currentTrackIndex = currentIndex,
                        related = offline.related,
                        seriesBooks = offline.seriesBooks,
                    )
                }
                return@launch
            }
            try {
                val sourceId = bookKey.substringBefore(":")
                val source = appContext.sourceRegistry.get(sourceId)
                val book = _state.value.book ?: source?.let { s ->
                    Book(
                        sourceId = s.id,
                        id = bookKey.substringAfter(":"),
                        title = "",
                        url = s.urlForId(bookKey.substringAfter(":")),
                    )
                }
                if (source == null || book == null) {
                    _state.update { it.copy(loading = false, error = "Источник не найден") }
                    return@launch
                }
                val details = source.getBookDetails(book.url)
                val merged = details.book.copy(
                    title = details.book.title.ifBlank { book.title },
                    coverUrl = details.book.coverUrl ?: book.coverUrl,
                    genre = details.book.genre ?: book.genre,
                    author = details.book.author ?: book.author,
                    seriesTitle = details.book.seriesTitle ?: book.seriesTitle,
                    seriesIndex = details.book.seriesIndex ?: book.seriesIndex,
                    seriesUrl = details.book.seriesUrl ?: book.seriesUrl,
                )
                val newDetails = details.copy(book = merged)
                appContext.bookCache.put(merged)
                val related = newDetails.related
                    .filterNot { it.url == merged.url }
                    .onEach { appContext.bookCache.put(it) }
                val seriesBooks = newDetails.seriesBooks
                    .filterNot { it.url == merged.url }
                    .sortedWith(compareBy<Book> { it.seriesIndex == null }.thenBy { it.seriesIndex })
                    .onEach { appContext.bookCache.put(it) }
                val progress = appContext.progressStore.load(merged.id)
                val currentIndex = appContext.playerController.player.currentMediaItemIndex
                    .takeIf { appContext.playerController.player.mediaItemCount > 0 && appContext.playerController.nowPlaying.value?.book?.id == merged.id }
                _state.update {
                    it.copy(
                        book = merged,
                        details = newDetails,
                        loading = false,
                        progress = progress,
                        currentTrackIndex = currentIndex,
                        related = related,
                        seriesBooks = seriesBooks,
                    )
                }
            } catch (e: Exception) {
                val message = e.message ?: "Ошибка загрузки"
                if (message.contains("удалена") || message.contains("фрагмент") || message.contains("правообладател")) {
                    appContext.deadBooksStore.markDead(bookKey)
                }
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    fun playTrack(index: Int, startPositionMs: Long = 0L) {
        val details = _state.value.details ?: return
        viewModelScope.launch {
            val localUris = if (appContext.downloadManager.isDownloaded(bookKey)) {
                appContext.downloadManager.offlineTrackUris(bookKey, details.tracks.size)
            } else {
                null
            }
            appContext.playerController.play(details, index, startPositionMs, localUris)
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), PlaybackService::class.java),
            )
        }
    }

    fun continuePlayback() {
        val progress = _state.value.progress ?: return
        playTrack(progress.trackIndex, progress.positionMs)
    }
}