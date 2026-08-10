package com.yourapp.audiobook.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.yourapp.audiobook.data.Bookmark
import com.yourapp.audiobook.data.BookmarksStore
import com.yourapp.audiobook.data.EqualizerStore
import com.yourapp.audiobook.data.HistoryStore
import com.yourapp.audiobook.data.ProgressStore
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class NowPlaying(
    val book: Book,
    val tracks: List<AudioTrack>,
)

enum class SleepTimerMode { TIME, END_OF_CHAPTER }

data class SleepTimer(
    val mode: SleepTimerMode,
    val endAtMs: Long? = null,
    val minutes: Int? = null,
)

class PlayerController(
    context: Context,
    private val historyStore: HistoryStore,
    private val progressStore: ProgressStore,
    private val bookmarksStore: BookmarksStore,
    private val equalizerStore: EqualizerStore,
) {

    private val appContext = context.applicationContext

    private val sessionActivity: PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, com.yourapp.audiobook.MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                )
                .apply {
                    if (chain.request().header("Referer") == null) {
                        header("Referer", "https://pda.izib.uk/")
                    }
                }
                .build()
            chain.proceed(request)
        }
        .build()

    private val okHttpDataSourceFactory = OkHttpDataSource.Factory(httpClient)

    private val dataSourceFactory: DataSource.Factory = object : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RefererAwareDataSource(okHttpDataSourceFactory.createDataSource())
    }

    private class RefererAwareDataSource(private val base: DataSource) : DataSource by base {
        override fun open(dataSpec: DataSpec): Long {
            val host = dataSpec.uri.host
            val newSpec = when {
                host == null -> dataSpec
                host.contains("redirectto.cc") ->
                    dataSpec.withRequestHeaders(
                        dataSpec.httpRequestHeaders + ("Referer" to "https://book-zvuk.com/"),
                    )
                host.contains("aubks.org") ->
                    dataSpec.withRequestHeaders(
                        dataSpec.httpRequestHeaders + ("Referer" to "https://m1.audiomir.xyz/"),
                    )
                host.contains("fantbox.net") ->
                    dataSpec.withRequestHeaders(
                        dataSpec.httpRequestHeaders + ("Referer" to "https://lis10book.com/"),
                    )
                else -> dataSpec
            }
            return base.open(newSpec)
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    val mediaSession: MediaSession = MediaSession.Builder(appContext, player)
        .setSessionActivity(sessionActivity)
        .build()

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    val timer = _sleepTimer.value ?: return
                    if (timer.mode == SleepTimerMode.END_OF_CHAPTER) {
                        _sleepTimer.value = null
                        pause()
                    }
                }
            }
        })
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val equalizer = EqualizerController(equalizerStore, scope)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                equalizer.attachIfNeeded(player.audioSessionId)
            }
        })
        scope.launch {
            while (true) {
                delay(1000)
                val timer = _sleepTimer.value ?: continue
                if (timer.mode == SleepTimerMode.TIME) {
                    val endAt = timer.endAtMs ?: continue
                    if (System.currentTimeMillis() >= endAt) {
                        _sleepTimer.value = null
                        pause()
                    }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                saveProgress()
            }
        }
    }

    private var lastSavedTrack = -1
    private var lastSavedPosition = -1L

    fun saveProgressNow() {
        val nowPlaying = _nowPlaying.value ?: return
        val track = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        if (player.mediaItemCount == 0 && position <= 0) return
        lastSavedTrack = track
        lastSavedPosition = position
        scope.launch { progressStore.save(nowPlaying.book.id, track, position) }
    }

    private fun saveProgress() {
        val nowPlaying = _nowPlaying.value ?: return
        val track = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        if (position <= 0) return
        if (track == lastSavedTrack && position == lastSavedPosition) return
        lastSavedTrack = track
        lastSavedPosition = position
        scope.launch { progressStore.save(nowPlaying.book.id, track, position) }
    }

    fun setSleepTimer(minutes: Int?) {
        _sleepTimer.value = minutes?.let {
            SleepTimer(
                mode = SleepTimerMode.TIME,
                endAtMs = System.currentTimeMillis() + it * 60_000L,
                minutes = it,
            )
        }
    }

    fun setSleepTimerToEndOfChapter() {
        _sleepTimer.value = SleepTimer(mode = SleepTimerMode.END_OF_CHAPTER)
    }

    fun play(details: BookDetails, startIndex: Int, startPositionMs: Long, localUris: List<Uri>? = null) {
        val items = details.tracks.mapIndexed { index, track ->
            val uri = localUris?.getOrNull(index) ?: Uri.parse(track.url)
            MediaItem.Builder()
                .setMediaId("${details.book.id}:$index")
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(details.book.title)
                        .setAlbumTitle(details.book.title)
                        .setArtworkUri(details.book.coverUrl?.let { Uri.parse(it) })
                        .build(),
                )
                .build()
        }
        player.setMediaItems(
            items,
            startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            if (startPositionMs > 0) startPositionMs else 0L,
        )
        player.prepare()
        player.playWhenReady = true
        equalizer.attachIfNeeded(player.audioSessionId)
        _nowPlaying.value = NowPlaying(details.book, details.tracks)
        scope.launch {
            historyStore.record(details.book)
            _bookmarks.value = bookmarksStore.load(details.book.id)
        }
    }

    fun addBookmark() {
        val playing = _nowPlaying.value ?: return
        if (player.mediaItemCount == 0) return
        val bookmark = Bookmark(
            id = System.currentTimeMillis().toString(),
            trackIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            createdAtMs = System.currentTimeMillis(),
        )
        val updated = (_bookmarks.value + bookmark).sortedByDescending { it.createdAtMs }
        _bookmarks.value = updated
        scope.launch { bookmarksStore.save(playing.book.id, updated) }
    }

    fun removeBookmark(id: String) {
        val playing = _nowPlaying.value ?: return
        val updated = _bookmarks.value.filterNot { it.id == id }
        _bookmarks.value = updated
        scope.launch { bookmarksStore.save(playing.book.id, updated) }
    }

    fun seekToBookmark(bookmark: Bookmark) {
        playTrack(bookmark.trackIndex, bookmark.positionMs)
    }

    fun playTrack(index: Int, positionMs: Long = 0L) {
        val playing = _nowPlaying.value ?: return
        if (index !in playing.tracks.indices) return
        player.seekTo(index, positionMs.coerceAtLeast(0L))
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.mediaItemCount == 0) return
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun pause() {
        if (player.mediaItemCount == 0) return
        if (player.playWhenReady) {
            player.pause()
            saveProgressNow()
        }
    }

    fun seekTo(positionMs: Long) {
        if (player.mediaItemCount == 0) return
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekBy(offsetMs: Long) {
        if (player.mediaItemCount == 0) return
        val duration = player.duration
        val target = player.currentPosition + offsetMs
        val clamped = if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
        player.seekTo(clamped)
    }

    fun next() {
        if (player.mediaItemCount == 0) return
        player.seekToNextMediaItem()
        player.playWhenReady = true
    }

    fun previous() {
        if (player.mediaItemCount == 0) return
        player.seekToPreviousMediaItem()
        player.playWhenReady = true
    }

    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)

    fun cycleSpeed(): Float {
        if (player.mediaItemCount == 0) return 1f
        val current = player.playbackParameters.speed
        val next = speeds[(speeds.indexOfFirst { it == current } + 1).let { if (it >= speeds.size) 0 else it }]
        player.playbackParameters = PlaybackParameters(next)
        return next
    }

    fun stopAndClear() {
        saveProgressNow()
        player.stop()
        player.clearMediaItems()
        _nowPlaying.value = null
        _bookmarks.value = emptyList()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}