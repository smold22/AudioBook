package com.yourapp.audiobook.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.source.api.BookDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { DOWNLOADING, COMPLETE, ERROR }

data class DownloadState(
    val bookKey: String,
    val status: DownloadStatus,
    val percent: Int = 0,
    val tracksDone: Int = 0,
    val tracksTotal: Int = 0,
    val errorMessage: String? = null,
)

class DownloadManager(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
) {

    private val app = appContext.applicationContext as com.yourapp.audiobook.AudioBookApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val _downloadedBooks = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadedBooks: StateFlow<Map<String, String>> = _downloadedBooks.asStateFlow()

    private val _downloadedKeys = MutableStateFlow<Set<String>>(emptySet())
    val downloadedKeys: StateFlow<Set<String>> = _downloadedKeys.asStateFlow()

    private val cancelling = ConcurrentHashMap.newKeySet<String>()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Скачивание",
                NotificationManager.IMPORTANCE_LOW,
            )
            appContext.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
        scope.launch {
            val books = settingsStore.downloadedBooks()
            _downloadedBooks.update { it + books }
            _downloadedKeys.update { it + books.keys }
            migrateLegacyDownloads()
        }
    }

    private suspend fun migrateLegacyDownloads() {
        for (bookKey in settingsStore.legacyDownloadedKeys()) {
            if (isDownloaded(bookKey)) continue
            val sourceId = bookKey.substringBefore(":")
            val bookId = bookKey.substringAfter(":")
            val source = app.sourceRegistry.get(sourceId)
            if (source == null || bookId.isBlank() || bookId == bookKey) {
                settingsStore.removeLegacyDownloadedKey(bookKey)
                continue
            }
            val details = runCatching { source.getBookDetails(source.urlForId(bookId)) }.getOrNull()
            if (details == null || details.tracks.isEmpty()) continue
            val dirName = DownloadService.sanitize(details.book.title)
            val folderRef = findBookFolder(dirName)
            if (folderRef != null) {
                finishDownload(bookKey, folderRef, OfflineMeta.encode(details))
            } else {
                settingsStore.removeLegacyDownloadedKey(bookKey)
            }
        }
    }

    private suspend fun findBookFolder(dirName: String): String? {
        val appDir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), dirName)
        if (hasMp3Files(appDir)) return APP_FOLDER
        val tree = settingsStore.currentDownloadFolder() ?: return null
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(tree)) ?: return null
        val dir = root.findFile(dirName)
        val hasFiles = dir?.listFiles()?.any { it.isFile && it.name?.endsWith(".mp3") == true } == true
        return if (hasFiles) tree else null
    }

    private fun hasMp3Files(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".mp3") }?.isNotEmpty() == true
    }

    fun startDownload(context: Context, bookKey: String) {
        if (_states.value.values.any { it.status == DownloadStatus.DOWNLOADING }) return
        if (isDownloaded(bookKey)) return
        cancelling.remove(bookKey)
        _states.update {
            it + (bookKey to DownloadState(bookKey, DownloadStatus.DOWNLOADING))
        }
        try {
            context.startForegroundService(
                Intent(context, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_DOWNLOAD)
                    .putExtra(DownloadService.EXTRA_BOOK_KEY, bookKey),
            )
        } catch (e: Exception) {
            _states.update {
                it + (bookKey to DownloadState(bookKey, DownloadStatus.ERROR, errorMessage = "Не удалось запустить скачивание"))
            }
        }
    }

    fun cancel(bookKey: String) {
        if (_states.value[bookKey]?.status != DownloadStatus.DOWNLOADING) return
        cancelling.add(bookKey)
        appContext.startService(
            Intent(appContext, DownloadService::class.java)
                .setAction(DownloadService.ACTION_CANCEL),
        )
    }

    fun isCancelling(bookKey: String): Boolean = bookKey in cancelling

    fun isDownloaded(bookKey: String): Boolean = bookKey in _downloadedKeys.value

    fun setState(bookKey: String, state: DownloadState) {
        _states.update { it + (bookKey to state) }
    }

    suspend fun finishDownload(bookKey: String, folder: String, metaJson: String) {
        cancelling.remove(bookKey)
        runCatching { settingsStore.saveDownloadedBook(bookKey, folder, metaJson) }
        _downloadedBooks.update { it + (bookKey to folder) }
        _downloadedKeys.update { it + bookKey }
        _states.update { it + (bookKey to DownloadState(bookKey, DownloadStatus.COMPLETE)) }
    }

    fun cancelFinished(bookKey: String) {
        cancelling.remove(bookKey)
        _states.update { it - bookKey }
    }

    suspend fun offlineDetails(bookKey: String): BookDetails? {
        if (!isDownloaded(bookKey)) return null
        val meta = settingsStore.bookMeta(bookKey) ?: return null
        return OfflineMeta.decode(meta)
    }

    suspend fun deleteDownloadedBook(bookKey: String): Boolean {
        val folderRef = _downloadedBooks.value[bookKey] ?: return false
        val dirName = offlineDetails(bookKey)?.book?.title?.let(DownloadService::sanitize)
        if (dirName != null) {
            withContext(Dispatchers.IO) {
                if (folderRef == APP_FOLDER) {
                    val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), dirName)
                    dir.deleteRecursively()
                } else {
                    DocumentFile.fromTreeUri(appContext, Uri.parse(folderRef))
                        ?.findFile(dirName)
                        ?.delete()
                }
            }
        }
        runCatching { settingsStore.removeDownloadedBook(bookKey) }
        _downloadedBooks.update { it - bookKey }
        _downloadedKeys.update { it - bookKey }
        _states.update { it - bookKey }
        return true
    }

    suspend fun offlineTrackUris(bookKey: String, trackCount: Int): List<Uri>? {
        val folderRef = _downloadedBooks.value[bookKey] ?: return null
        val details = offlineDetails(bookKey) ?: return null
        val dirName = DownloadService.sanitize(details.book.title)
        val files: List<Pair<String, Uri>>? = if (folderRef == APP_FOLDER) {
            val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), dirName)
            if (!dir.isDirectory) return null
            dir.listFiles { file -> file.isFile && file.name.endsWith(".mp3") }
                ?.map { it.name to Uri.fromFile(it) }
        } else {
            val root = DocumentFile.fromTreeUri(appContext, Uri.parse(folderRef))
            if (root == null || !root.isDirectory) return null
            root.findFile(dirName)
                ?.listFiles()
                ?.filter { it.isFile && it.name?.endsWith(".mp3") == true }
                ?.map { it.name.orEmpty() to it.uri }
        }
        if (files == null || files.isEmpty()) return null
        val sorted = files.sortedBy { fileIndex(it.first) }.map { it.second }
        return sorted.takeIf { trackCount > 0 && it.size == trackCount }
    }

    /** Индекс трека из имени файла вида "01 - Глава.mp3". */
    private fun fileIndex(name: String): Int =
        name.substringBefore(" -").trim().toIntOrNull() ?: Int.MAX_VALUE

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1001
        const val APP_FOLDER = "app"
    }
}