package com.yourapp.audiobook

import android.app.Application
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
import com.yourapp.audiobook.data.sync.SyncManager
import com.yourapp.audiobook.download.DownloadManager
import com.yourapp.audiobook.player.PlayerController
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.SourceRegistry
import com.yourapp.audiobook.source.extra.AknigaSource
import com.yourapp.audiobook.source.extra.Aknigi24Source
import com.yourapp.audiobook.source.extra.ArchiveOrgSource
import com.yourapp.audiobook.source.extra.AudioknigiFunSource
import com.yourapp.audiobook.source.extra.AudioknigaOneSource
import com.yourapp.audiobook.source.extra.AudioknigiProSource
import com.yourapp.audiobook.source.extra.AudiomirSource
import com.yourapp.audiobook.source.extra.BazaKnigSource
import com.yourapp.audiobook.source.extra.BookZvukSource
import com.yourapp.audiobook.source.extra.BookishSource
import com.yourapp.audiobook.source.extra.GolosomSource
import com.yourapp.audiobook.source.extra.KnigaVuheSource
import com.yourapp.audiobook.source.extra.KnigobludSource
import com.yourapp.audiobook.source.extra.Lis10bookSource
import com.yourapp.audiobook.source.extra.OtrubSource
import com.yourapp.audiobook.source.extra.PoleknigSource
import com.yourapp.audiobook.source.extra.UkNigSource
import com.yourapp.audiobook.source.izibuk.IziBukSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class AudioBookApplication : Application() {

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

    suspend fun activeSource(): AudiobookSource? {
        val selectedId = settingsStore.currentSourceId()
        return selectedId?.let { sourceRegistry.get(it) }
            ?: sourceRegistry.sources.firstOrNull()
    }

    suspend fun searchAll(query: String, page: Int): List<Book> = coroutineScope {
        sourceRegistry.sources.map { source ->
            async {
                if (sourceCooldown.isCoolingDown(source.id)) return@async emptyList()
                withTimeoutOrNull(SEARCH_SOURCE_TIMEOUT_MS) {
                    runCatching { source.search(query, page) }
                        .onFailure { if (isBlockError(it)) sourceCooldown.mark(source.id) }
                        .getOrDefault(emptyList())
                } ?: emptyList()
            }
        }.awaitAll().flatten()
    }

    fun isBlockError(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("HTTP 400") || message.contains("HTTP 403") || message.contains("HTTP 429")
    }

    private companion object {
        const val SEARCH_SOURCE_TIMEOUT_MS = 25_000L
    }

    override fun onCreate() {
        super.onCreate()
        sourceRegistry = SourceRegistry().apply {
            register(IziBukSource())
            register(KnigaVuheSource())
            register(AknigaSource())
            register(BazaKnigSource())
            register(KnigobludSource())
            register(PoleknigSource())
            register(UkNigSource())
            register(AudioknigiProSource())
            register(ArchiveOrgSource())
            register(GolosomSource())
            register(BookishSource())
            register(AudioknigiFunSource())
            register(BookZvukSource())
            register(Aknigi24Source())
            register(Lis10bookSource())
            register(AudioknigaOneSource())
            register(AudiomirSource())
            register(OtrubSource())
        }
        bookCache = BookCache()
        progressStore = ProgressStore(this)
        deadBooksStore = DeadBooksStore(this)
        sourceCooldown = SourceCooldown()
        settingsStore = SettingsStore(this)
        downloadManager = DownloadManager(this, settingsStore)
        backupManager = BackupManager()
        favoritesStore = FavoritesStore(this)
        historyStore = HistoryStore(this)
        bookmarksStore = BookmarksStore(this)
        equalizerStore = EqualizerStore(this)
        syncManager = SyncManager(
            this,
            favoritesStore,
            historyStore,
            progressStore,
            bookmarksStore,
            settingsStore,
        )
        syncManager.start()
        playerController = PlayerController(this, historyStore, progressStore, bookmarksStore, equalizerStore)
    }
}