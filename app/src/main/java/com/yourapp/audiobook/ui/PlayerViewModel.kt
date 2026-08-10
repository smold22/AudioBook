package com.yourapp.audiobook.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.Bookmark
import com.yourapp.audiobook.player.EqualizerState
import com.yourapp.audiobook.player.SleepTimer
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

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app as AudioBookApplication
    private val controller = appContext.playerController

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    val sleepTimer: StateFlow<SleepTimer?> get() = controller.sleepTimer

    val bookmarks: StateFlow<List<Bookmark>> get() = controller.bookmarks

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
    }

    fun setSleepTimer(minutes: Int?) = controller.setSleepTimer(minutes)

    fun setSleepTimerToEndOfChapter() = controller.setSleepTimerToEndOfChapter()

    fun addBookmark() = controller.addBookmark()

    fun removeBookmark(id: String) = controller.removeBookmark(id)

    fun seekToBookmark(bookmark: Bookmark) = controller.seekToBookmark(bookmark)

    fun setEqualizerEnabled(enabled: Boolean) = controller.equalizer.setEnabled(enabled)

    fun setEqualizerBandLevel(index: Int, levelMb: Int) = controller.equalizer.setBandLevel(index, levelMb)

    fun resetEqualizer() = controller.equalizer.reset()

    fun togglePlayPause() = controller.togglePlayPause()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun seekBy(offsetMs: Long) = controller.seekBy(offsetMs)

    fun next() = controller.next()

    fun previous() = controller.previous()

    fun cycleSpeed(): Float = controller.cycleSpeed()

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
}