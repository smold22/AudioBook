package com.yourapp.audiobook

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import com.yourapp.audiobook.data.BookCache
import com.yourapp.audiobook.data.BookDescriptionStore
import com.yourapp.audiobook.data.BackupManager
import com.yourapp.audiobook.data.BookmarksStore
import com.yourapp.audiobook.data.DeadBooksStore
import com.yourapp.audiobook.data.EqualizerStore
import com.yourapp.audiobook.data.FavoritesStore
import com.yourapp.audiobook.data.HistoryStore
import com.yourapp.audiobook.data.HomeGenre
import com.yourapp.audiobook.data.ProgressStore
import com.yourapp.audiobook.data.SearchHistoryStore
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.data.SourceCooldown
import com.yourapp.audiobook.data.SourceHealthTracker
import com.yourapp.audiobook.data.SourcesSimilarProvider
import com.yourapp.audiobook.data.WatchlistStore
import com.yourapp.audiobook.data.sync.SyncManager
import com.yourapp.audiobook.download.DownloadManager
import com.yourapp.audiobook.player.PlayerController
import com.yourapp.audiobook.player.PlaybackService
import com.yourapp.audiobook.player.stripRefParam
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.yourapp.audiobook.source.extra.AknigaComSource
import com.yourapp.audiobook.source.extra.AknigaSource
import com.yourapp.audiobook.source.extra.AknigXyzSource
import com.yourapp.audiobook.source.extra.AudioLibSource
import com.yourapp.audiobook.source.extra.AudioknigiOnlainSource
import com.yourapp.audiobook.source.extra.AuthorTodaySource
import com.yourapp.audiobook.source.extra.Aknigi24Source
import com.yourapp.audiobook.source.extra.AudioknigaLifeSource
import com.yourapp.audiobook.source.extra.AudioknigiFunSource
import com.yourapp.audiobook.source.extra.AudioknigiTopSource
import com.yourapp.audiobook.source.extra.AudioknigaOneSource
import com.yourapp.audiobook.source.extra.AudioknigiProSource
import com.yourapp.audiobook.source.extra.AudiomirSource
import com.yourapp.audiobook.source.extra.BazaKnigSource
import com.yourapp.audiobook.source.extra.BookZvukSource
import com.yourapp.audiobook.source.extra.BookishSource
import com.yourapp.audiobook.source.extra.GolosomSource
import com.yourapp.audiobook.source.extra.KnigaAudioSource
import com.yourapp.audiobook.source.extra.KnigaVuheSource
import com.yourapp.audiobook.source.extra.KnigiAudioNetSource
import com.yourapp.audiobook.source.extra.KnigobludSource
import com.yourapp.audiobook.source.extra.Lis10bookSource
import com.yourapp.audiobook.source.extra.OtrubSource
import com.yourapp.audiobook.source.extra.PoleknigSource
import com.yourapp.audiobook.source.extra.RuKnigaMeSource
import com.yourapp.audiobook.source.extra.SlushatKnigiSource
import com.yourapp.audiobook.source.extra.TAudioknigiMp3Source
import com.yourapp.audiobook.source.extra.UkNigSource
import com.yourapp.audiobook.source.izibuk.IziBukSource
import com.yourapp.audiobook.torrent.TorrentManager
import com.yourapp.audiobook.update.UpdateManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AudioBookApplication : Application() {

    /** Загрузчик изображений с учётом метки referer (ref=host) у CDN-обложек. */
    lateinit var imageLoader: ImageLoader
        private set

    private fun setupImageLoader() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (cleanUrl, ref) = Uri.parse(request.url.toString()).stripRefParam()
                val builder = request.newBuilder()
                if (ref != null) builder.header("Referer", ref)
                if (cleanUrl.toString() != request.url.toString()) builder.url(cleanUrl.toString())
                chain.proceed(builder.build())
            }
            .build()
        imageLoader = ImageLoader.Builder(this)
            .components { add(OkHttpNetworkFetcherFactory(client)) }
            .build()
    }

    lateinit var sourceRegistry: SourceRegistry
        private set
    lateinit var bookCache: BookCache
        private set
    lateinit var descriptionCache: BookDescriptionStore
        private set
    lateinit var progressStore: ProgressStore
        private set
    lateinit var deadBooksStore: DeadBooksStore
        private set
lateinit var sourceCooldown: SourceCooldown
        private set
    lateinit var sourceHealth: SourceHealthTracker
        private set
lateinit var settingsStore: SettingsStore
        private set
    lateinit var searchHistory: SearchHistoryStore
        private set
    lateinit var downloadManager: DownloadManager
        private set
    lateinit var backupManager: BackupManager
        private set
    lateinit var favoritesStore: FavoritesStore
        private set
    lateinit var watchlistStore: WatchlistStore
        private set
lateinit var historyStore: HistoryStore
        private set
    lateinit var bookmarksStore: BookmarksStore
        private set
    lateinit var equalizerStore: EqualizerStore
        private set
    lateinit var similarBooks: SourcesSimilarProvider
        private set
    lateinit var syncManager: SyncManager
        private set
    lateinit var playerController: PlayerController
        private set
    lateinit var torrentManager: TorrentManager
        private set
    lateinit var updateManager: UpdateManager
        private set

    /** Область приложения: корутины, переживающие уничтожение активностей. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Защита от повторного возобновления прослушивания в рамках одного процесса. */
    private var resumeAttempted = false

    /** Кеш жанров главного экрана: источник -> (выбранные названия, результат). */
    private val homeGenreCache = mutableMapOf<String, Pair<List<String>, List<HomeGenre>>>()

    /**
     * Продолжает прослушивание последней книги при запуске, если включено в настройках.
     * Работает в области приложения, поэтому не отменяется при уничтожении [LauncherActivity].
     */
    fun resumeLastBookIfEnabled() {
        if (resumeAttempted) return
        resumeAttempted = true
        applicationScope.launch {
            if (!settingsStore.resumeOnLaunch.first()) return@launch
            if (playerController.nowPlaying.value != null) return@launch
            val last = historyStore.snapshot().firstOrNull() ?: return@launch
            resumeBook(last.book)
        }
    }

    /**
     * Загружает детали книги (офлайн-копии или из источника) и продолжает
     * воспроизведение с сохранённой позиции. Используется для возобновления
     * последней книги и для воспроизведения следующей книги серии.
     */
    private suspend fun resumeBook(book: Book) {
        val bookKey = "${book.sourceId}:${book.id}"
        val offlineDetails = withContext(Dispatchers.IO) {
            runCatching { downloadManager.offlineDetails(bookKey) }.getOrNull()
        }
        val details = if (offlineDetails != null && offlineDetails.tracks.isNotEmpty()) {
            offlineDetails
        } else {
            val source = sourceRegistry.get(book.sourceId) ?: return
            withContext(Dispatchers.IO) {
                runCatching { source.getBookDetails(book.url) }.getOrNull()
            } ?: return
        }
        if (details.tracks.isEmpty()) return
        val progress = progressStore.load(bookKey)
        val savedTrack = progress?.trackIndex ?: 0
        val trackIndex = savedTrack.takeIf { it in details.tracks.indices } ?: 0
        val positionMs = if (trackIndex == savedTrack) progress?.positionMs ?: 0L else 0L
        val localUris = withContext(Dispatchers.IO) {
            val trackNames = com.yourapp.audiobook.download.trackFileNames(details)
            downloadManager.offlineTrackUris(bookKey, trackNames)
        }
        playerController.play(details, trackIndex, positionMs, localUris)
        ContextCompat.startForegroundService(
            this@AudioBookApplication,
            Intent(this@AudioBookApplication, PlaybackService::class.java),
        )
    }

    /**
     * Следующая книга серии (цикла): сначала список серии из деталей книги,
     * при неполноте — постраничный список серии с сайта источника.
     */
    private suspend fun nextSeriesBook(details: BookDetails): Book? {
        val current = details.book
        val ordered = details.seriesBooks.sortedWith(compareBy { it.seriesIndex ?: Int.MAX_VALUE })
        val detailIdx = ordered.indexOfFirst { it.id == current.id }
        if (detailIdx >= 0) {
            ordered.getOrNull(detailIdx + 1)?.let { return it }
        }
        val seriesUrl = current.seriesUrl ?: return null
        val source = sourceRegistry.get(current.sourceId) ?: return null
        var page = 1
        var currentSeen = false
        while (page <= SERIES_MAX_PAGES) {
            val books = runCatching { source.seriesBooks(seriesUrl, page) }.getOrNull() ?: return null
            if (books.isEmpty()) return null
            val idx = books.indexOfFirst { it.id == current.id }
            if (idx >= 0) {
                books.getOrNull(idx + 1)?.let { return it }
                if (currentSeen) return null
                currentSeen = true
            } else if (currentSeen) {
                return books.firstOrNull()
            }
            page++
        }
        return null
    }

suspend fun activeSource(): AudiobookSource? {
        val hidden = settingsStore.hiddenSourceIds()
        val selectedId = settingsStore.currentSourceId()
        if (selectedId != null && selectedId !in hidden) {
            sourceRegistry.get(selectedId)?.let { return it }
        }
        return sourceRegistry.sources.firstOrNull { it.id !in hidden }
    }

    /**
     * Новинки со всех источников, поддерживающих [AudiobookSource.supportsNew].
     * Каждый источник обрабатывается изолированно: сбой одного не отменяет остальные.
     */
    suspend fun newBooksAll(page: Int): List<Book> {
        val sources = sourceRegistry.sources.filter { it.supportsNew() }
        if (sources.isEmpty()) return emptyList()
        return coroutineScope {
            sources.map { source ->
                async {
                    try {
                        source.newBooks(page)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Жанры главного экрана, пересчитанные для текущего источника.
     * Выбранные по названию жанры применяются и после смены источника:
     * если в новом источнике есть жанр с таким же названием, используется его ссылка;
     * если такого жанра нет — жанр пропускается (и тогда на главном «Все книги»).
     */
    suspend fun resolveHomeGenres(): List<HomeGenre> {
        val selected = settingsStore.homeGenres.first()
        if (selected.isEmpty()) {
            homeGenreCache.clear()
            return emptyList()
        }
        val source = activeSource() ?: return emptyList()
        val names = selected.map { normalizeGenreName(it.name) }.distinct().sorted()
        homeGenreCache[source.id]?.let { (cachedNames, result) ->
            if (cachedNames == names) return result
        }
        val storedForSource = selected.filter { it.sourceId == source.id }
        val resolved = if (storedForSource.size == names.size) {
            storedForSource
        } else {
            val genreList = runCatching { source.genres() }.getOrNull()
            if (genreList == null) {
                return emptyList()
            }
            names.mapNotNull { name ->
                genreList.firstOrNull { normalizeGenreName(it.name) == name }
            }.map { HomeGenre(source.id, it.url, it.name) }
        }
        homeGenreCache[source.id] = names to resolved
        return resolved
    }

    fun normalizeGenreName(name: String): String =
        name.trim().replace(Regex("\\s+"), " ").lowercase()

    suspend fun searchAll(query: String, page: Int): List<Book> = coroutineScope {
        val hidden = settingsStore.hiddenSourceIds()
        sourceRegistry.sources.filter { it.id !in hidden }.map { source ->
            async {
                if (sourceCooldown.isCoolingDown(source.id)) return@async emptyList()
                withTimeoutOrNull(SEARCH_SOURCE_TIMEOUT_MS) {
                    runCatching { source.search(query, page) }
                        .onFailure { if (isBlockError(it)) sourceCooldown.mark(source.id) }
                        .getOrDefault(emptyList())
                }?.take(MAX_RESULTS_PER_SOURCE) ?: emptyList()
            }
        }.awaitAll().flatten()
    }

    fun isBlockError(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("HTTP 400") || message.contains("HTTP 403") || message.contains("HTTP 429")
    }

private companion object {
        const val SEARCH_SOURCE_TIMEOUT_MS = 25_000L
        const val MAX_RESULTS_PER_SOURCE = 10
        /** Максимум страниц серии, которые просматриваются в поиске следующей книги. */
        const val SERIES_MAX_PAGES = 20
    }

override fun onCreate() {
        super.onCreate()
        setupImageLoader()
sourceRegistry = SourceRegistry().apply {
            register(IziBukSource())
            register(KnigaVuheSource())
            register(AknigaSource())
            register(BazaKnigSource())
            register(KnigobludSource())
            register(PoleknigSource())
            register(UkNigSource())
            register(AudioknigiProSource())
            register(GolosomSource())
            register(BookishSource())
            register(AudioknigiFunSource())
            register(BookZvukSource())
            register(Aknigi24Source())
            register(Lis10bookSource())
            register(AudioknigaOneSource())
            register(AudiomirSource())
            register(OtrubSource())
            register(SlushatKnigiSource())
            register(AudioknigaLifeSource())
            register(AudioknigiTopSource())
            register(AknigaComSource())
            register(AuthorTodaySource())
            register(RuKnigaMeSource())
            register(TAudioknigiMp3Source())
            register(AudioLibSource())
            register(AknigXyzSource())
            register(KnigaAudioSource())
            register(KnigiAudioNetSource())
            register(AudioknigiOnlainSource())
        }
bookCache = BookCache()
        descriptionCache = BookDescriptionStore(this)
        progressStore = ProgressStore(this)
        deadBooksStore = DeadBooksStore(this)
        sourceCooldown = SourceCooldown()
        sourceHealth = SourceHealthTracker()
settingsStore = SettingsStore(this)
        searchHistory = SearchHistoryStore(this)
        downloadManager = DownloadManager(this, settingsStore)
        torrentManager = TorrentManager(this)
        updateManager = UpdateManager(this)
        backupManager = BackupManager()
        favoritesStore = FavoritesStore(this)
watchlistStore = WatchlistStore(this)
        historyStore = HistoryStore(this)
        bookmarksStore = BookmarksStore(this)
        equalizerStore = EqualizerStore(this)
        similarBooks = SourcesSimilarProvider(this)
syncManager = SyncManager(
            this,
            favoritesStore,
            watchlistStore,
            historyStore,
            progressStore,
            bookmarksStore,
            settingsStore,
        )
syncManager.start()
        playerController = PlayerController(this, historyStore, progressStore, bookmarksStore, equalizerStore, settingsStore)
        playerController.nextSeriesBookProvider = { details -> nextSeriesBook(details) }
        playerController.playBook = { book -> resumeBook(book) }
        selfHealIconState()
        applicationScope.launch {
            settingsStore.homeGenres.collect { homeGenreCache.clear() }
        }
        // Фоновая проверка работоспособности источников при каждом старте.
        applicationScope.launch {
            sourceHealth.checkAllSequentially(sourceRegistry.sources)
        }
    }

    /** Восстанавливает корректное состояние компонентов иконки лаунчера при старте. */
    private fun selfHealIconState() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                AppIconSwitcher.apply(this@AudioBookApplication, settingsStore.appIcon.first())
            }
        }
    }
}

