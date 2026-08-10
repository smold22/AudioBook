package com.yourapp.audiobook.player

import android.media.audiofx.Equalizer
import com.yourapp.audiobook.data.EqualizerSettings
import com.yourapp.audiobook.data.EqualizerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EqualizerBand(
    val index: Int,
    val freqHz: Int,
    val levelMb: Int,
)

data class EqualizerState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val minLevelMb: Int = 0,
    val maxLevelMb: Int = 0,
    val bands: List<EqualizerBand> = emptyList(),
)

class EqualizerController(
    private val store: EqualizerStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    private var equalizer: Equalizer? = null
    private var attachedSession = -1
    private var pending = EqualizerSettings()

    init {
        scope.launch {
            store.settings.collect { pending = it }
        }
    }

    fun attachIfNeeded(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == attachedSession) return
        try {
            equalizer?.release()
            equalizer = null
            attachedSession = audioSessionId
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            applyPending(eq)
            refreshState(eq)
        } catch (e: Exception) {
            equalizer = null
            _state.value = EqualizerState(available = false)
        }
    }

    fun setEnabled(enabled: Boolean) {
        equalizer?.let { eq ->
            runCatching { eq.enabled = enabled }
        }
        _state.value = _state.value.copy(enabled = enabled)
        persist()
    }

    fun setBandLevel(index: Int, levelMb: Int) {
        val current = _state.value
        equalizer?.let { eq ->
            val clamped = levelMb.coerceIn(current.minLevelMb, current.maxLevelMb)
            runCatching { eq.setBandLevel(index.toShort(), clamped.toShort()) }
            _state.value = current.copy(
                bands = current.bands.map { band ->
                    if (band.index == index) band.copy(levelMb = clamped) else band
                },
            )
        }
        persist()
    }

    fun reset() {
        val current = _state.value
        equalizer?.let { eq ->
            runCatching {
                for (band in 0 until eq.numberOfBands.toInt()) {
                    eq.setBandLevel(band.toShort(), 0)
                }
            }
            _state.value = current.copy(
                bands = current.bands.map { it.copy(levelMb = 0) },
            )
        }
        persist()
    }

    private fun applyPending(eq: Equalizer) {
        val settings = pending
        val levels = settings.bandLevels
        runCatching {
            for (band in 0 until eq.numberOfBands.toInt()) {
                eq.setBandLevel(band.toShort(), (levels.getOrNull(band) ?: 0).toShort())
            }
            eq.enabled = settings.enabled
        }
    }

    private fun refreshState(eq: Equalizer) {
        val range = eq.bandLevelRange
        val min = range[0].toInt()
        val max = range[1].toInt()
        val bands = (0 until eq.numberOfBands.toInt()).map { index ->
            EqualizerBand(
                index = index,
                freqHz = runCatching { eq.getCenterFreq(index.toShort()) }.getOrDefault(0) / 1000,
                levelMb = runCatching { eq.getBandLevel(index.toShort()) }.getOrDefault(0).toInt(),
            )
        }
        _state.value = EqualizerState(
            available = true,
            enabled = runCatching { eq.enabled }.getOrDefault(false),
            minLevelMb = min,
            maxLevelMb = max,
            bands = bands,
        )
    }

    private fun persist() {
        val current = _state.value
        scope.launch {
            store.save(current.enabled, current.bands.map { it.levelMb })
        }
    }
}
