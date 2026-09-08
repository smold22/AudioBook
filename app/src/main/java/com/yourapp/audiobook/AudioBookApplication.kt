package com.yourapp.audiobook

import android.app.Application
import android.net.Uri
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import com.yourapp.audiobook.data.BookCache
import com.yourapp.audiobook.data.BackupManager
import com.yourapp.audiobook.data.BookmarksStore
import com.yourapp.audiobook.data.DeadBooksStore
import com.yourapp.audiobook.data.EqualizerStore
import com.yourapp.audiobook.data.FavoritesStore
import com.yourapp.audiobook.data.HistoryStore
import com.yourapp.audiobook.data.ProgressStore
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.data.SourceCooldown
import com.yourapp.audiobook.data.WatchlistStore
import com.yourapp.audiobook.data.sync.SyncManager
import com.yourapp.audiobook.download.DownloadManager
import com.yourapp.audiobook.player.PlayerController
import com.yourapp.audiobook.player.stripRefParam
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
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
import com.yourapp.audiobook.source.extra.AumeSource
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
    lateinit var progressStore: ProgressStore
        private set
    lateinit var deadBooksStore: DeadBooksStore
        private set
    lateinit var sourceCooldown: SourceCooldown
        private set
    lateinit var settingsStore: SettingsStore
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
    lateinit var syncManager: SyncManager
        private set
    lateinit var playerController: PlayerController
        private set
    lateinit var torrentManager: TorrentManager
        private set
    lateinit var updateManager: UpdateManager
        private set

    suspend fun activeSource(): AudiobookSource? {
        val hidden = settingsStore.hiddenSourceIds()
        val selectedId = settingsStore.currentSourceId()
        if (selectedId != null && selectedId !in hidden) {
            sourceRegistry.get(selectedId)?.let { return it }
        }
        return sourceRegistry.sources.firstOrNull { it.id !in hidden }
    }

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
    }

override fun onCreate() {
        super.onCreate()
        setupImageLoader()
sourceRegistry = SourceRegistry().apply {
            register(IziBukSource())
            register(KnigaVuheSource())
            register(AumeSource())
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
        progressStore = ProgressStore(this)
        deadBooksStore = DeadBooksStore(this)
        sourceCooldown = SourceCooldown()
        settingsStore = SettingsStore(this)
        downloadManager = DownloadManager(this, settingsStore)
        torrentManager = TorrentManager(this)
        updateManager = UpdateManager(this)
        backupManager = BackupManager()
        favoritesStore = FavoritesStore(this)
watchlistStore = WatchlistStore(this)
        historyStore = HistoryStore(this)
        bookmarksStore = BookmarksStore(this)
        equalizerStore = EqualizerStore(this)
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
        selfHealIconState()
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

