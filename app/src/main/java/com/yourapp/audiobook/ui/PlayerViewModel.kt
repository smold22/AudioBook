package com.yourapp.audiobook.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.Bookmark
import com.yourapp.audiobook.player.EqualizerState
import com.yourapp.audiobook.player.PlaybackService
import com.yourapp.audiobook.player.SleepTimer
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerSnapshot(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val trackIndex: Int = 0,
    val speed: Float = 1f,
)

/** Вариант книги, прочитанный конкретным чтецом (для выбора чтеца в плеере). */
data class ReaderOption(
    val book: Book,
    val isCurrent: Boolean,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app as AudioBookApplication
    private val controller = appContext.playerController

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _readerOptions = MutableStateFlow<List<ReaderOption>>(emptyList())
    val readerOptions: StateFlow<List<ReaderOption>> = _readerOptions.asStateFlow()

    private val _readerSearching = MutableStateFlow(false)
    val readerSearching: StateFlow<Boolean> = _readerSearching.asStateFlow()

    private val _readerError = MutableStateFlow<String?>(null)
    val readerError: StateFlow<String?> = _readerError.asStateFlow()

    val sleepTimer: StateFlow<SleepTimer?> get() = controller.sleepTimer

    val bookmarks: StateFlow<List<Bookmark>> get() = controller.bookmarks

    val nextBookSuggestion: StateFlow<Book?> get() = controller.nextBookSuggestion

    val equalizerState: StateFlow<EqualizerState> get() = controller.equalizer.state

    private var tickerJob: Job? = null

    init {
        tickerJob = viewModelScope.launch {
            while (true) {
                try {
                    updateSnapshot()
                } catch (e: Exception) {
                    Log.w("PlayerViewModel", "updateSnapshot failed", e)
                }
                delay(500)
            }
        }
        viewModelScope.launch {
            controller.nowPlaying.collect { playing ->
                _readerOptions.value = emptyList()
                _readerSearching.value = false
                playing?.let { loadReaderOptions(it.book) }
            }
        }
    }

    fun setSleepTimer(minutes: Int?) = controller.setSleepTimer(minutes)

    fun setSleepTimerToEndOfChapter() = controller.setSleepTimerToEndOfChapter()

    fun addBookmark() = controller.addBookmark()

    fun removeBookmark(id: String) = controller.removeBookmark(id)

    fun seekToBookmark(bookmark: Bookmark) = controller.seekToBookmark(bookmark)

    fun setEqualizerEnabled(enabled: Boolean) = controller.equalizer.setEnabled(enabled)

    fun setEqualizerBandLevel(index: Int, levelMb: Int) = controller.equalizer.setBandLevel(index, levelMb)

    fun resetEqualizer() = controller.equalizer.reset()

    fun playNextBook() = controller.playNextBook()

    fun dismissNextBookSuggestion() = controller.dismissNextBookSuggestion()

    fun togglePlayPause() = controller.togglePlayPause()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun seekBy(offsetMs: Long) = controller.seekBy(offsetMs)

    fun next() = controller.next()

    fun previous() = controller.previous()

    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    private fun updateSnapshot() {
        val player = controller.player
        val position = player.currentPosition
        val duration = player.duration
        _snapshot.value = PlayerSnapshot(
            isPlaying = player.isPlaying,
            positionMs = position.coerceAtLeast(0L),
            durationMs = if (duration > 0) duration else 0L,
            trackIndex = player.currentMediaItemIndex,
            speed = player.playbackParameters.speed,
        )
    }

    fun saveProgressNow() = controller.saveProgressNow()

    /**
     * Ищет в источнике другие издания той же книги (то же название и автор),
     * прочитанные другими чтецами.
     */
    private suspend fun loadReaderOptions(book: Book) {
        val rawReader = book.reader?.trim()
        val reader: String = rawReader?.takeIf { it.isNotEmpty() } ?: return
        val title = normalize(book.title)
        if (title.isEmpty()) return
        val author = book.author?.trim()?.takeIf { it.isNotEmpty() }
        val currentKey = "${book.sourceId}:${book.id}"
        _readerSearching.value = true
        val allMatches = coroutineScope {
            appContext.sourceRegistry.sources.map { source ->
                async {
                    runCatching { source.search(book.title, 1) }.getOrElse { emptyList() }
                }
            }.awaitAll().flatten()
        }
        val currentReaderKey = readerKey(reader)
        val candidates = buildList {
            add(book)
            addAll(allMatches.filter { candidate ->
                normalize(candidate.title) == title &&
                    candidate.reader?.isNotBlank() == true &&
                    readerKey(candidate.reader) != currentReaderKey &&
                    "${candidate.sourceId}:${candidate.id}" != currentKey &&
                    (author == null || normalize(candidate.author) == normalize(author))
            })
        }
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<Book>()
        for (candidate in candidates) {
            if (seen.add(readerKey(candidate.reader))) unique += candidate
        }
        unique.forEach { appContext.bookCache.put(it) }
        _readerSearching.value = false
        _readerOptions.value = unique.map { ReaderOption(it, "${it.sourceId}:${it.id}" == currentKey) }
    }

    /** Переключает воспроизведение на версию книги, прочитанную другим чтецом. */
    fun switchReader(option: Book) {
        val source = appContext.sourceRegistry.get(option.sourceId) ?: return
        viewModelScope.launch {
            try {
                val details = source.getBookDetails(option.url)
                if (details.tracks.isEmpty()) {
                    _readerError.value = "Треки не найдены"
                    return@launch
                }
                val merged = details.book.copy(
                    title = details.book.title.ifBlank { option.title },
                    coverUrl = details.book.coverUrl?.takeIf { it.isNotBlank() } ?: option.coverUrl,
                    author = details.book.author ?: option.author,
                    reader = details.book.reader ?: option.reader,
                )
                appContext.bookCache.put(merged)
                controller.play(details.copy(book = merged), 0, 0L, null)
                ContextCompat.startForegroundService(
                    getApplication(),
                    Intent(getApplication(), PlaybackService::class.java),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _readerError.value = e.message ?: "Не удалось загрузить книгу"
            }
        }
    }

    fun consumeReaderError() {
        _readerError.value = null
    }

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: ""

    private fun readerKey(value: String?): String =
        (value ?: "")
            .lowercase()
            .replace(Regex("[^а-яёa-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() && it !in setOf("чтец", "читает", "читает:", "исполнитель", "озвучивает", "диктор", "начит") }
            .sorted()
            .joinToString(" ")
}