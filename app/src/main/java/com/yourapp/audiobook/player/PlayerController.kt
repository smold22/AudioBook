package com.yourapp.audiobook.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
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
import com.yourapp.audiobook.data.SettingsStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Вытаскивает метку referer (query-параметр ref) из URL и возвращает
 * чистый URL + готовый заголовок Referer (https://<host>/).
 */
fun Uri.stripRefParam(): Pair<Uri, String?> {
    val ref = getQueryParameter("ref") ?: return this to null
    val builder = buildUpon().clearQuery()
    queryParameterNames
        .filter { it != "ref" }
        .forEach { name ->
            getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
        }
    return builder.build() to "https://$ref/"
}

data class NowPlaying(
    val book: Book,
    val tracks: List<AudioTrack>,
    val details: BookDetails,
)

/** Режим остановки: по времени, на границе главы или в конце книги. */
enum class SleepTimerMode { TIME, END_OF_CHAPTER, END_OF_BOOK }

data class SleepTimer(
    val mode: SleepTimerMode,
    val endAtMs: Long? = null,
    val minutes: Int? = null,
    /** Длина затухания перед остановкой, секунды. 0 — без затухания. */
    val fadeSeconds: Int = SleepTimerPolicy.FADE_DEFAULT_SECONDS,
)

class PlayerController(
    context: Context,
    private val historyStore: HistoryStore,
    private val progressStore: ProgressStore,
    private val bookmarksStore: BookmarksStore,
    private val equalizerStore: EqualizerStore,
    private val settingsStore: SettingsStore,
) {

    private val appContext = context.applicationContext

    private val sessionActivity: PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, com.yourapp.audiobook.LauncherActivity::class.java)
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
                    val host = chain.request().url.host
                    if (host.contains("izib") && chain.request().header("Referer") == null) {
                        header("Referer", "https://pda.izib.uk/")
                    }
                }
                .build()
            chain.proceed(request)
        }
        .build()

    private val okHttpDataSourceFactory = OkHttpDataSource.Factory(httpClient)

    /**
     * Фабрика данных: локальные файлы (file/content/asset/raw) обрабатывает
     * [DefaultDataSource], а http/https — OkHttp с учётом Referer.
     */
    private val dataSourceFactory: DataSource.Factory =
        DefaultDataSource.Factory(
            appContext,
            object : DataSource.Factory {
                override fun createDataSource(): DataSource =
                    RefererAwareDataSource(okHttpDataSourceFactory.createDataSource())
            },
        )

    private class RefererAwareDataSource(private val base: DataSource) : DataSource by base {
        override fun open(dataSpec: DataSpec): Long {
            val host = dataSpec.uri.host
            val (cleanUri, ref) = dataSpec.uri.stripRefParam()
            val newSpec = when {
                ref != null ->
                    dataSpec.withUri(cleanUri).withRequestHeaders(
                        dataSpec.httpRequestHeaders + ("Referer" to ref),
                    )
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

    var mediaSession: MediaSession = buildSession()
        private set

    private fun buildSession(): MediaSession =
        MediaSession.Builder(appContext, player)
            .setSessionActivity(sessionActivity)
            .build()

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    return
                }
                val timer = _sleepTimer.value ?: return
                val previousIndex = mediaItemTransitionFrom
                mediaItemTransitionFrom = player.currentMediaItemIndex
                if (previousIndex < 0) return
                if (!SleepTimerPolicy.pauseOnTransition(
                        timer.mode,
                        previousIndex,
                        player.currentMediaItemIndex,
                        player.mediaItemCount,
                    )
                ) {
                    return
                }
                stopForSleepTimer()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val timer = _sleepTimer.value ?: return
                if (playbackState != Player.STATE_ENDED) return
                if (SleepTimerPolicy.clearOnEnded(
                        timer.mode,
                        player.currentMediaItemIndex,
                        player.mediaItemCount,
                    )
                ) {
                    // STATE_ENDED приходит уже после паузы, поэтому только
                    // снимаем таймер и возвращаем громкость, если она была
                    // приглушена затуханием.
                    stopFade(restoreVolume = true)
                    _sleepTimer.value = null
                    fadeCancelledByUser = false
                }
            }

            /**
             * Ручное изменение громкости (клавиши, Bluetooth, панель) во время
             * затухания — отменяем его. Иначе аниматор тут же перезапишет
             * выбранный пользователем уровень.
             *
             * Собственные записи аниматора отсекаем по счётчику: Media3
             * доставляет события с задержкой, и к моменту обработки аниматор
             * успевает записать ещё несколько кадров.
             */
            override fun onVolumeChanged(volume: Float) {
                if (SleepTimerPolicy.isOwnVolumeWrite(
                        pendingSelfWrites = pendingSelfVolumeWrites,
                        lastWrittenVolume = lastWrittenVolume,
                        reportedVolume = volume,
                    )
                ) {
                    if (pendingSelfVolumeWrites > 0) pendingSelfVolumeWrites--
                    return
                }
                if (!fadeRunning) return
                // Громкость изменили извне — оставляем её как есть и запрещаем
                // повторный запуск затухания до конца текущего таймера.
                fadeCancelledByUser = true
                stopFade(restoreVolume = false)
                // Новый уровень пользователя становится точкой отсчёта, иначе
                // на границе главы мы вернули бы громкость к старой.
                volumeBeforeFade = volume
                volumeReducedByFade = false
            }
        })
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /**
     * Счётчик запусков воспроизведения.
     *
     * Отличать «начали играть» от «уже играет» по [nowPlaying] нельзя: там нет
     * события, а на телевизоре по нажатию «Слушать» нужно открыть плеер даже
     * для уже загруженной книги.
     */
    private val _playStarts = MutableStateFlow(0)
    val playStarts: StateFlow<Int> = _playStarts.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()

    /** Длина затухания, выбранная в настройках: 0 — не затухать. */
    private val _fadeSeconds = MutableStateFlow(SleepTimerPolicy.FADE_DEFAULT_SECONDS)
    val fadeSeconds: StateFlow<Int> = _fadeSeconds.asStateFlow()

    private var fadeAnimator: ValueAnimator? = null

    /** Идёт ли затухание прямо сейчас: без этого нельзя вернуть громкость. */
    private var fadeRunning = false

    /**
     * Пользователь тронул громкость во время затухания: до конца текущего
     * таймера больше не гасим, иначе аниматор затыкал бы звук вопреки выбору.
     */
    private var fadeCancelledByUser = false

    /** Громкость до затухания — из неё начинаем спад и в неё возвращаемся. */
    private var volumeBeforeFade = 1f

    /**
     * Снизили ли мы громкость и ещё не вернули.
     *
     * Отдельный флаг, а не [fadeRunning]: восстановление происходит уже после
     * того, как аниматор снят, и опираться на «идёт ли аниматор» нельзя —
     * иначе громкость остаётся на нуле и звук пропадает до следующей книги.
     */
    private var volumeReducedByFade = false

    /**
     * Сколько записей громкости мы сделали сами и Media3 ещё не подтвердил.
     *
     * Счётчик 1:1 с событиями [Player.Listener.onVolumeChanged]: пока событие
     * лежит в очереди главного потока, аниматор успевает записать ещё кадры,
     * поэтому «последние N значений» протухают и обрывают затухание.
     */
    private var pendingSelfVolumeWrites = 0

    /** Последнее значение, выставленное нами: страховка от склейки событий. */
    private var lastWrittenVolume = 1f

    /** Индекс главы до последнего перехода — для «до конца главы/книги». */
    private var mediaItemTransitionFrom = -1

    /** Аниматор затухания живёт только на [scope] и снимается при паузе. */
    private fun applyFadeSeconds(seconds: Int) {
        _fadeSeconds.value = seconds
        // Действующий таймер должен увидеть новую длительность, иначе выбор
        // в настройке не подхватится до следующего запуска таймера.
        _sleepTimer.value = _sleepTimer.value?.copy(fadeSeconds = seconds)
        // Затухание уже идёт: снимаем его, а следующий тик запустит заново
        // с новой длительностью. Точную пересборку аниматора на ходу делать
        // незачем — затухание длится считаные секунды.
        if (fadeRunning) stopFade(restoreVolume = true)
    }

    fun setFadeSeconds(seconds: Int) {
        applyFadeSeconds(seconds)
        scope.launch { settingsStore.setSleepTimerFadeSeconds(seconds) }
    }

    /** Находит следующую книгу серии (цикла) после завершения текущей. Устанавливается приложением. */
    var nextSeriesBookProvider: (suspend (BookDetails) -> Book?)? = null

    /** Воспроизводит указанную книгу (загружает детали и продолжает с сохранённой позиции). Устанавливается приложением. */
    var playBook: (suspend (Book) -> Unit)? = null

    private val _nextBookSuggestion = MutableStateFlow<Book?>(null)
    val nextBookSuggestion: StateFlow<Book?> = _nextBookSuggestion.asStateFlow()

    private var bookEndHandled = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val equalizer = EqualizerController(equalizerStore, scope)

    init {
        // Восстанавливаем сохранённую скорость воспроизведения.
        scope.launch {
            val savedSpeed = settingsStore.playbackSpeed.first()
            if (savedSpeed != 1f) {
                player.playbackParameters = PlaybackParameters(savedSpeed)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                equalizer.attachIfNeeded(player.audioSessionId)
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) {
                    bookEndHandled = false
                    return
                }
                if (bookEndHandled) return
                val now = _nowPlaying.value ?: return
                if (!player.playWhenReady) return
                if (player.mediaItemCount == 0 || player.currentMediaItemIndex != player.mediaItemCount - 1) return
                bookEndHandled = true
                val details = now.details
                scope.launch {
                    val next = nextSeriesBookProvider?.invoke(details)
                    if (next != null) _nextBookSuggestion.value = next
                }
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    // Пауза во время затухания: аниматор снимаем и возвращаем
                    // уровень — иначе после возобновления звука не будет.
                    // Если затухания не было, громкость не трогаем.
                    stopFade(restoreVolume = true)
                    saveProgressNow()
                } else if (_nowPlaying.value != null && player.mediaItemCount > 0) {
                    try {
                        ContextCompat.startForegroundService(
                            appContext,
                            Intent(appContext, PlaybackService::class.java),
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        })
        scope.launch {
            // Сохраняем выбранную длительность затухания.
            applyFadeSeconds(settingsStore.sleepTimerFadeSeconds.first())
            settingsStore.sleepTimerFadeSeconds
                .map { it }
                .collect { applyFadeSeconds(it) }
        }
        scope.launch {
            // Границы глав/книжи: переход уже обработан слушателем, здесь
            // держим mediaItemTransitionFrom в актуальном состоянии.
            while (true) {
                delay(1000)
                if (_sleepTimer.value == null) {
                    mediaItemTransitionFrom = player.currentMediaItemIndex
                }
            }
        }
        scope.launch {
            while (true) {
                delay(TIMER_TICK_MS)
                checkSleepTimer()
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

    /**
     * Тик таймера сна: гасит звук перед остановкой и останавливает плеер.
     *
     * Отсчёт идёт по [SleepTimerPolicy]; сам таймер снимается тут же после
     * паузы, чтобы бейдж в плеере не показывал уже завершившийся отсчёт.
     */
    private fun checkSleepTimer() {
        val timer = _sleepTimer.value ?: return
        val fadeSeconds = timer.fadeSeconds

        if (timer.mode == SleepTimerMode.TIME) {
            if (!SleepTimerPolicy.timeExpired(timer.endAtMs, System.currentTimeMillis())) {
                maybeStartFade(fadeSeconds, remainingMs = (timer.endAtMs ?: 0L) - System.currentTimeMillis())
                return
            }
            // Дедлайн наступил, но затухание ещё идёт — даём ему дописать
            // спад, иначе громкость прыгнет вверх на середине затухания.
            if (fadeRunning) return
            stopForSleepTimer()
            return
        }

        // Режимы по главе/книже: пауза наступает на границе, её обрабатывает
        // слушатель переходов. Здесь только затухание у конца главы.
        if (!SleepTimerPolicy.fadeAppliesToChapter(
                timer.mode,
                player.currentMediaItemIndex,
                player.mediaItemCount,
            )
        ) {
            return
        }
        val remaining = remainingChapterMs() ?: return
        maybeStartFade(fadeSeconds, remaining)
        // Длительность главы уточняется по ходу, поэтому оценка остатка на
        // старте затухания устаревает. Каждый тик продлеваем (или укорачиваем)
        // спад так, чтобы он пришёл ровно к нулю, а не на пару секунд раньше.
        reanchorRunningFade(remaining)
    }

    /**
     * Дотягивает уже идущий спад до реального конца главы.
     *
     * Меняем [ValueAnimator.duration] на «уже прошло + осталось»: у аниматора
     * это сдвигает момент финала, поэтому спад всегда приходит на 0.
     */
    private fun reanchorRunningFade(remainingMs: Long) {
        val animator = fadeAnimator ?: return
        if (!fadeRunning) return
        if (remainingMs <= 0L) {
            // До конца уже не ждём: доводим спад до тишины и останавливаем.
            stopForSleepTimer()
            return
        }
        val elapsed = (animator.animatedFraction * animator.duration).toLong()
        val target = SleepTimerPolicy.fadeDurationForRemaining(elapsed, remainingMs)
        if (target > animator.duration) animator.duration = target
    }

    /**
     * Остаток текущей главы в миллисекундах либо null, если длительность
     * неизвестна (стриминг, ещё не загрузилось).
     */
    private fun remainingChapterMs(): Long? {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return null
        return (duration - player.currentPosition).coerceAtLeast(0L)
    }

    /** Запускает затухание, если до остановки осталось меньше, чем его длина. */
    private fun maybeStartFade(fadeSeconds: Int, remainingMs: Long) {
        // Затухание имеет смысл только на живом воспроизведении: на паузе
        // звука и так нет, а аниматор лишь зря крутит громкость. Повторно его
        // не поднимаем, если оно уже идёт или его отменил пользователь.
        if (!SleepTimerPolicy.shouldStartFade(
                remainingMs = remainingMs,
                fadeSeconds = fadeSeconds,
                isPlaying = player.isPlaying,
                fadeRunning = fadeRunning,
                cancelledByUser = fadeCancelledByUser,
            )
        ) {
            return
        }
        startFade(SleepTimerPolicy.effectiveFadeMs(remainingMs, fadeSeconds))
    }

    /** Плавно снижает громкость до нуля за [durationMs]. */
    private fun startFade(durationMs: Long) {
        if (durationMs <= 0L) return
        stopFade(restoreVolume = false)
        volumeBeforeFade = player.volume
        fadeRunning = true
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                setPlayerVolume(SleepTimerPolicy.fadeVolume(volumeBeforeFade, fraction))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // ValueAnimator зовёт onAnimationEnd и при cancel(), поэтому
                    // сверяемся с текущим аниматором: чужой (уже снятый) конец
                    // не должен ни возвращать громкость, ни ставить паузу.
                    if (animation !== fadeAnimator) return
                    // Снимать аниматор здесь нельзя: stopForSleepTimer должен
                    // увидеть, что спад шёл, и вернуть громкость после паузы.
                    stopForSleepTimer()
                }
            })
        }
        fadeAnimator = animator
        animator.start()
    }

    /**
     * Останавливает затухание.
     *
     * [restoreVolume] = true оставляет громкость как есть, если затухание
     * не шло, — иначе обычная пауза обнуляла бы выбор пользователя.
     */
    private fun stopFade(restoreVolume: Boolean) {
        val animator = fadeAnimator
        fadeAnimator = null
        fadeRunning = false
        pendingSelfVolumeWrites = 0
        if (animator != null) {
            animator.removeAllUpdateListeners()
            // Слушатель тоже снимаем: cancel() зовёт onAnimationEnd, а тот
            // останавливает плеер по таймеру.
            animator.removeAllListeners()
            animator.cancel()
        }
        if (restoreVolume && volumeReducedByFade) restoreVolumeAfterFade()
    }

    /**
     * Выставляет громкость от нашего имени: событие [Player.Listener.onVolumeChanged]
     * придёт следом, и [pendingSelfVolumeWrites] защитит его от ложной отмены.
     */
    private fun setPlayerVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        lastWrittenVolume = clamped
        pendingSelfVolumeWrites++
        if (clamped < volumeBeforeFade) volumeReducedByFade = true
        player.volume = clamped
    }

    /** Возвращает уровень, который был до затухания. */
    private fun restoreVolumeAfterFade() {
        setPlayerVolume(volumeBeforeFade)
        volumeReducedByFade = false
    }

    /**
     * Пауза по таймеру: снимаем таймер, гасим звук и возвращаем громкость.
     *
     * Порядок важен. На границе главы ExoPlayer успевает отдать первые кадры
     * следующей главы, а `duration` у VBR-дорожек включает паддинг, поэтому
     * переход приходит на пару секунд раньше конца отсчёта. Если вернуть
     * громкость до паузы, звук прыгнет вверх и затухание прозвучит
     * оборванным. Поэтому сначала доводим спад до тишины и ставим паузу,
     * и лишь затем, уже на паузе, возвращаем уровень.
     */
    private fun stopForSleepTimer() {
        _sleepTimer.value = null
        fadeCancelledByUser = false
        val wasFading = fadeRunning
        stopFade(restoreVolume = false)
        if (wasFading) setPlayerVolume(0f)
        pause()
        if (SleepTimerPolicy.restoreVolumeAfterPause(volumeReducedByFade)) {
            restoreVolumeAfterFade()
        }
    }

    fun saveProgressNow() {
        val nowPlaying = _nowPlaying.value ?: return
        val track = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        if (position <= 0) return
        lastSavedTrack = track
        lastSavedPosition = position
        scope.launch { progressStore.save(bookKey(nowPlaying.book), track, position) }
    }

    private fun saveProgress() {
        val nowPlaying = _nowPlaying.value ?: return
        val track = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        if (position <= 0) return
        if (track == lastSavedTrack && position == lastSavedPosition) return
        lastSavedTrack = track
        lastSavedPosition = position
        scope.launch { progressStore.save(bookKey(nowPlaying.book), track, position) }
    }

    private fun bookKey(book: Book): String = "${book.sourceId}:${book.id}"

    fun setSleepTimer(minutes: Int?) {
        stopFade(restoreVolume = true)
        fadeCancelledByUser = false
        mediaItemTransitionFrom = player.currentMediaItemIndex
        _sleepTimer.value = minutes?.let {
            SleepTimer(
                mode = SleepTimerMode.TIME,
                endAtMs = System.currentTimeMillis() + it * 60_000L,
                minutes = it,
                fadeSeconds = _fadeSeconds.value,
            )
        }
    }

    fun setSleepTimerToEndOfChapter() {
        stopFade(restoreVolume = true)
        fadeCancelledByUser = false
        mediaItemTransitionFrom = player.currentMediaItemIndex
        _sleepTimer.value = SleepTimer(
            mode = SleepTimerMode.END_OF_CHAPTER,
            fadeSeconds = _fadeSeconds.value,
        )
    }

    fun setSleepTimerToEndOfBook() {
        stopFade(restoreVolume = true)
        fadeCancelledByUser = false
        mediaItemTransitionFrom = player.currentMediaItemIndex
        _sleepTimer.value = SleepTimer(
            mode = SleepTimerMode.END_OF_BOOK,
            fadeSeconds = _fadeSeconds.value,
        )
    }

    /**
     * Загружает книгу в плеер.
     *
     * [autoPlay] = false только подготавливает книгу на сохранённой позиции,
     * не начиная воспроизведение: нужно для «Открывать окно плеера при запуске»
     * без включённого автовоспроизведения последней книги.
     */
    fun play(
        details: BookDetails,
        startIndex: Int,
        startPositionMs: Long,
        localUris: List<Uri?>? = null,
        autoPlay: Boolean = true,
    ) {
        if (details.tracks.isEmpty()) return
        _sleepTimer.value = null
        fadeCancelledByUser = false
        // Новая книга не должна начинаться с приглушённой громкости.
        stopFade(restoreVolume = true)
        mediaItemTransitionFrom = startIndex
        _nextBookSuggestion.value = null
        val items = buildMediaItems(details, localUris)
        player.setMediaItems(
            items,
            startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            if (startPositionMs > 0) startPositionMs else 0L,
        )
        player.prepare()
        player.playWhenReady = autoPlay
        equalizer.attachIfNeeded(player.audioSessionId)
        _nowPlaying.value = NowPlaying(details.book, details.tracks, details)
        // Счётчик означает именно запуск воспроизведения: подготовка книги
        // на паузе не должна открывать плеер на ТВ как реакцию на «Слушать».
        if (autoPlay) _playStarts.value = _playStarts.value + 1
        scope.launch {
            historyStore.record(details.book)
            _bookmarks.value = bookmarksStore.load(bookKey(details.book))
        }
    }

    private fun buildMediaItems(details: BookDetails, localUris: List<Uri?>?): List<MediaItem> =
        details.tracks.mapIndexed { index, track ->
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
        scope.launch { bookmarksStore.save(bookKey(playing.book), updated) }
    }

    fun removeBookmark(id: String) {
        val playing = _nowPlaying.value ?: return
        val updated = _bookmarks.value.filterNot { it.id == id }
        _bookmarks.value = updated
        scope.launch { bookmarksStore.save(bookKey(playing.book), updated) }
    }

    fun seekToBookmark(bookmark: Bookmark) {
        playTrack(bookmark.trackIndex, bookmark.positionMs)
    }

    fun playTrack(index: Int, positionMs: Long = 0L) {
        val playing = _nowPlaying.value ?: return
        if (index !in playing.tracks.indices) return
        player.seekTo(index, positionMs.coerceAtLeast(0L))
        mediaItemTransitionFrom = index
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
        if (player.currentPosition > 3_000L) {
            player.seekTo(player.currentMediaItemIndex, 0L)
        } else {
            player.seekToPreviousMediaItem()
        }
        player.playWhenReady = true
    }

    fun setSpeed(speed: Float) {
        if (player.mediaItemCount == 0) return
        player.playbackParameters = PlaybackParameters(speed)
        scope.launch { settingsStore.setPlaybackSpeed(speed) }
    }

    fun stopAndClear() {
        saveProgressNow()
        _sleepTimer.value = null
        fadeCancelledByUser = false
        stopFade(restoreVolume = true)
        _nextBookSuggestion.value = null
        player.stop()
        player.clearMediaItems()
        _nowPlaying.value = null
        _bookmarks.value = emptyList()
        mediaSession.release()
        mediaSession = buildSession()
    }

    /** Пользователь пропустил предложение продолжить серию. */
    fun dismissNextBookSuggestion() {
        _nextBookSuggestion.value = null
    }

    /** Воспроизводит предложенную следующую книгу серии. */
    fun playNextBook() {
        val next = _nextBookSuggestion.value ?: return
        _nextBookSuggestion.value = null
        scope.launch { playBook?.invoke(next) }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L

        /** Частота проверки таймера сна: чаще секунды не нужно. */
        const val TIMER_TICK_MS = 500L
    }
}