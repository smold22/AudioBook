package com.yourapp.audiobook.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.LauncherActivity
import com.yourapp.audiobook.R
import com.yourapp.audiobook.player.stripRefParam
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.extra.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentBookKey: String? = null
    private var pendingBookKey: String? = null

    /** Нейтральный клиент без Referer: CDN разных источников могут блокировать чужие referer. */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build(),
            )
        }
        .build()

    private val manager get() = (application as AudioBookApplication).downloadManager
    private val registry get() = (application as AudioBookApplication).sourceRegistry
    private val settings get() = (application as AudioBookApplication).settingsStore
    private val torrentManager get() = (application as AudioBookApplication).torrentManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val bookKey = intent.getStringExtra(EXTRA_BOOK_KEY)
                if (bookKey == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (currentBookKey != null) {
                    pendingBookKey = bookKey
                    return START_STICKY
                }
                startDownload(bookKey)
            }
            ACTION_CANCEL -> {
                pendingBookKey = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startDownload(bookKey: String) {
        currentBookKey = bookKey
        startForeground(
            DownloadManager.NOTIFICATION_ID,
            buildNotification(
                title = "Подготовка скачивания…",
                text = "Загрузка книги",
                percent = null,
                indeterminate = true,
                ongoing = true,
            ),
        )
        serviceScope.launch { runDownload(bookKey) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        currentBookKey = null
        torrentManager.cancelAll()
        super.onDestroy()
    }

    private suspend fun runDownload(bookKey: String) {
        var bookTitle = ""
        try {
            val sourceId = bookKey.substringBefore(":")
            val bookId = bookKey.substringAfter(":")
            val source = registry.get(sourceId) ?: throw IOException("Источник скачивания не найден")
            val details = source.getBookDetails(source.urlForId(bookId))
            bookTitle = details.book.title
            val folderUri = settings.currentDownloadFolder()?.let(Uri::parse)
            val folderRef = folderUri?.toString() ?: DownloadManager.APP_FOLDER
            val torrentUrl = details.torrentUrl
            if (source.supportsTorrent() && torrentUrl != null) {
                runTorrentDownload(bookKey, source, torrentUrl, details, bookTitle, folderUri, folderRef)
                return
            }
            val storage = DownloadStorage(this, folderUri, bookTitle)
            val tracks = details.tracks
            if (tracks.isEmpty()) throw IOException("В книге нет доступных аудиофайлов")

            val total = tracks.size
            var done = 0
            for (index in tracks.indices) {
                if (manager.isCancelling(bookKey)) {
                    manager.cancelFinished(bookKey)
                    return
                }
                val track = tracks[index]
                updateNotification(
                    title = "Скачивание книги",
                    text = "$bookTitle · трек ${index + 1} из $total",
                    percent = null,
                    indeterminate = true,
                    ongoing = true,
                )
                if (storage.exists(index, track)) {
                    done += 1
                    manager.setState(
                        bookKey,
                        DownloadState(bookKey, DownloadStatus.DOWNLOADING, percent = overallPercent(done, total), tracksDone = done, tracksTotal = total),
                    )
                    continue
                }
                downloadTrackFile(httpClient, storage, index, track) { percent ->
                    val overall = if (total > 0) (done * 100 + percent) / total else 0
                    manager.setState(bookKey, DownloadState(bookKey, DownloadStatus.DOWNLOADING, overall, done, total))
                    updateNotification(
                        title = "Скачивание книги",
                        text = "$bookTitle · трек ${index + 1} из $total · $overall%",
                        percent = overall,
                        indeterminate = false,
                        ongoing = true,
                    )
                }
                done += 1
                manager.setState(
                    bookKey,
                    DownloadState(bookKey, DownloadStatus.DOWNLOADING, percent = overallPercent(done, total), tracksDone = done, tracksTotal = total),
                )
            }

            manager.finishDownload(bookKey, folderRef, OfflineMeta.encode(details))
            updateNotification(
                title = "Книга скачана",
                text = bookTitle,
                percent = 100,
                indeterminate = false,
                ongoing = false,
            )
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (cancelException: CancellationException) {
            if (manager.isCancelling(bookKey)) {
                manager.cancelFinished(bookKey)
            } else {
                manager.setState(bookKey, DownloadState(bookKey, DownloadStatus.ERROR, errorMessage = "Скачивание прервано"))
            }
            throw cancelException
        } catch (e: Exception) {
            if (manager.isCancelling(bookKey)) {
                manager.cancelFinished(bookKey)
            } else {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось скачать книгу"
                manager.setState(bookKey, DownloadState(bookKey, DownloadStatus.ERROR, errorMessage = message))
                updateNotification(
                    title = "Ошибка скачивания",
                    text = "$bookTitle ${message}".trim(),
                    percent = null,
                    indeterminate = false,
                    ongoing = false,
                )
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        } finally {
            currentBookKey = null
            val next = pendingBookKey
            pendingBookKey = null
            if (next != null) {
                startDownload(next)
            } else {
                stopSelf()
            }
        }
    }

    private suspend fun downloadTrackFile(
        client: OkHttpClient,
        storage: DownloadStorage,
        index: Int,
        track: AudioTrack,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadTrackAttempt(client, storage, index, track, onProgress)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= MAX_TRACK_ATTEMPTS) throw e
                delay(RETRY_BACKOFF_MS * attempt)
            }
        }
    }

    private fun downloadTrackAttempt(
        client: OkHttpClient,
        storage: DownloadStorage,
        index: Int,
        track: AudioTrack,
        onProgress: (Int) -> Unit,
    ) {
        val resumeFrom = storage.partLength(index, track)
        val (cleanUrl, ref) = Uri.parse(track.url).stripRefParam()
        val requestBuilder = Request.Builder().url(cleanUrl.toString())
        if (ref != null) {
            requestBuilder.header("Referer", ref)
        }
        if (resumeFrom > 0) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            when {
                response.code == 206 -> {
                    val rangeStart = parseContentRangeStart(response.header("Content-Range"))
                    if (rangeStart < 0) {
                        // Заголовок Content-Range неразбираем — нельзя доверять смещению, скачиваем с нуля.
                        storage.deletePart(index, track)
                        val total = response.body?.contentLength() ?: -1L
                        writeBody(response, storage, index, track, 0L, total, onProgress)
                    } else if (rangeStart != resumeFrom) {
                        // Сервер проигнорировал наш Range — скачиваем файл с нуля.
                        storage.deletePart(index, track)
                        val total = response.body?.contentLength() ?: -1L
                        writeBody(response, storage, index, track, 0L, total, onProgress)
                    } else {
                        val total = totalLength(response, resumeFrom)
                        writeBody(response, storage, index, track, resumeFrom, total, onProgress)
                    }
                }
                response.code == 416 -> {
                    // Частичный файл уже полный — обрыва не было, просто не переименовался.
                    storage.finishFile(index, track)
                }
                response.isSuccessful -> {
                    // Сервер не поддержал Range — скачиваем с нуля.
                    writeBody(response, storage, index, track, 0L, response.body?.contentLength() ?: -1L, onProgress)
                }
                else -> throw IOException("HTTP ${response.code} при скачивании «${track.title}»")
            }
        }
    }

    private fun overallPercent(done: Int, total: Int): Int =
        if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0

    /** Скачивание книги через торрент: получает .torrent, качает через jlibtorrent, раскладывает файлы. */
    private suspend fun runTorrentDownload(
        bookKey: String,
        source: com.yourapp.audiobook.source.api.AudiobookSource,
        torrentUrl: String,
        details: com.yourapp.audiobook.source.api.BookDetails,
        bookTitle: String,
        folderUri: Uri?,
        folderRef: String,
    ) {
        val tm = torrentManager
        if (!tm.available()) throw IOException("Торрент-движок недоступен на этом устройстве")
        updateNotification("Подготовка торрента…", bookTitle, percent = null, indeterminate = true, ongoing = true)
        val torrentBytes = withContext(Dispatchers.IO) {
            source.fetchTorrentBytes(torrentUrl)
        } ?: throw IOException("Не удалось загрузить торрент-файл")
        val torrentsRoot = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ".torrents")
        val tempDir = File(torrentsRoot, "${bookKey.hashCode()}_${sanitize(bookTitle)}")
        tempDir.deleteRecursively()
        if (!tempDir.mkdirs()) throw IOException("Не удалось создать временную папку")
        tm.start(bookKey, torrentBytes, tempDir)
        var lastPercent = -1
        while (true) {
            if (manager.isCancelling(bookKey)) {
                tm.cancel(bookKey)
                manager.cancelFinished(bookKey)
                return
            }
            val state = tm.states.value[bookKey] ?: throw IOException("Торрент-загрузка не запустилась")
            state.error?.let { throw IOException(it) }
            val percent = state.percent
            if (percent != lastPercent) {
                lastPercent = percent
                manager.setState(
                    bookKey,
                    DownloadState(bookKey, DownloadStatus.DOWNLOADING, percent = percent, tracksDone = percent, tracksTotal = 100),
                )
                updateNotification(
                    title = "Скачивание через торрент",
                    text = "$bookTitle · $percent%",
                    percent = percent,
                    indeterminate = false,
                    ongoing = true,
                )
            }
            if (state.finished) break
            delay(1000)
        }
        manager.setState(
            bookKey,
            DownloadState(bookKey, DownloadStatus.DOWNLOADING, percent = 100, tracksDone = 100, tracksTotal = 100),
        )
        updateNotification("Скачивание через торрент", "$bookTitle · подготовка файлов…", percent = null, indeterminate = true, ongoing = true)
        val files = withContext(Dispatchers.IO) { normalizeTorrentFiles(tempDir) }
        val dirName = sanitize(bookTitle)
        if (folderUri == null) {
            val finalDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), dirName)
            finalDir.deleteRecursively()
            if (!finalDir.mkdirs()) throw IOException("Не удалось создать папку книги")
            files.forEach { (name, file) ->
                if (!file.renameTo(File(finalDir, name))) throw IOException("Не удалось сохранить файл «$name»")
            }
        } else {
            val root = DocumentFile.fromTreeUri(this, folderUri)
            if (root == null || !root.canWrite()) throw IOException("Не удалось получить доступ к выбранной папке")
            val bookDir = root.findFile(dirName) ?: root.createDirectory(dirName)
                ?: throw IOException("Не удалось создать папку книги в выбранной папке")
            files.forEach { (name, file) ->
                val target = bookDir.createFile("audio/mpeg", name)
                    ?: throw IOException("Не удалось создать файл «$name» в выбранной папке")
                contentResolver.openOutputStream(target.uri)?.use { output ->
                    file.inputStream().use { it.copyTo(output) }
                } ?: throw IOException("Не удалось открыть файл «$name» для записи")
            }
        }
        tempDir.deleteRecursively()
        val tracks = files.mapIndexed { index, (name, _) ->
            AudioTrack(title = name.removeSuffix(".mp3"), url = name)
        }
        manager.finishDownload(bookKey, folderRef, OfflineMeta.encode(details.copy(tracks = tracks)))
        updateNotification("Книга скачана", bookTitle, percent = 100, indeterminate = false, ongoing = false)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    /**
     * Собирает аудиофайлы из папки раздачи (возможно, во вложенных папках),
     * сортирует по имени и переименовывает в «01 - Название.mp3» в корне [dir].
     */
    private fun normalizeTorrentFiles(dir: File): List<Pair<String, File>> {
        val audioExtensions = setOf("mp3", "ogg", "m4a", "m4b", "aac", "flac", "opus", "wav")
        val files = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in audioExtensions }
            .sortedBy { it.absolutePath.lowercase() }
            .toList()
        val result = mutableListOf<Pair<String, File>>()
        files.forEachIndexed { index, file ->
            val base = file.nameWithoutExtension
                .replace(Regex("""^\s*\d{1,3}[\s._\-]+"""), "")
                .trim()
            val safe = sanitize(if (base.isBlank()) "Трек ${index + 1}" else base)
            val newName = "%02d - %s.mp3".format(index + 1, safe)
            val target = File(dir, newName)
            if (file.absolutePath != target.absolutePath) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
            result.add(newName to target)
        }
        return result
    }

    private fun parseContentRangeStart(header: String?): Long {
        if (header.isNullOrEmpty()) return -1L
        // Формат: "bytes START-END/TOTAL"
        val start = header.substringAfter("bytes ", "").substringBefore("-").trim()
        return start.toLongOrNull() ?: -1L
    }

    private fun totalLength(response: okhttp3.Response, resumeFrom: Long): Long {
        val contentRange = response.header("Content-Range")
        contentRange?.let { range ->
            val total = range.substringAfter('/', "").toLongOrNull()
            if (total != null && total > 0) return total
        }
        val remaining = response.body?.contentLength() ?: -1L
        return if (remaining >= 0 && resumeFrom > 0) resumeFrom + remaining else remaining
    }

    private fun writeBody(
        response: okhttp3.Response,
        storage: DownloadStorage,
        index: Int,
        track: AudioTrack,
        startOffset: Long,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
    ) {
        val body = response.body ?: throw IOException("Пустой ответ при скачивании «${track.title}»")
        storage.openOutput(index, track, append = startOffset > 0).use { output ->
            val input = body.byteStream()
            val buffer = ByteArray(64 * 1024)
            var readCount = startOffset
            var lastPercent = -1
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                readCount += read
                if (totalBytes > 0) {
                    val percent = ((readCount * 100) / totalBytes).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
            output.flush()
        }
        storage.finishFile(index, track)
    }

    private fun updateNotification(title: String, text: String, percent: Int?, indeterminate: Boolean, ongoing: Boolean) {
        NotificationManagerCompat.from(this)
            .notify(DownloadManager.NOTIFICATION_ID, buildNotification(title, text, percent, indeterminate, ongoing))
    }

    private fun buildNotification(title: String, text: String, percent: Int?, indeterminate: Boolean, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, DownloadManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .apply {
                if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, indeterminate)
            }
            .build()

    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private class DownloadStorage(
        private val context: Context,
        folderUri: Uri?,
        bookTitle: String,
    ) {
        private val bookDirName = sanitize(bookTitle)
        private val bookFile: File? =
            if (folderUri == null) File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), bookDirName) else null
        private val bookDoc: DocumentFile? = if (folderUri == null) null else {
            val root = DocumentFile.fromTreeUri(context, folderUri)
            if (root == null || !root.canWrite()) {
                throw IOException("Не удалось получить доступ к выбранной папке")
            }
            root.findFile(bookDirName) ?: root.createDirectory(bookDirName)
        }

        init {
            if (folderUri != null && bookDoc == null) {
                throw IOException("Не удалось создать папку книги в выбранной папке")
            }
            if (bookFile != null && !bookFile.exists()) {
                bookFile.mkdirs()
            }
        }

        fun exists(index: Int, track: AudioTrack): Boolean {
            val name = fileName(index, track)
            val doc = bookDoc?.findFile(name)?.takeIf { it.length() > 0 }
            if (doc != null) return true
            val file = bookFile?.let { File(it, name) }
            return file?.exists() == true && file.length() > 0
        }

        fun partLength(index: Int, track: AudioTrack): Long {
            val name = fileName(index, track)
            if (bookDoc != null) {
                return bookDoc.findFile("$name.part")?.length() ?: 0L
            }
            val dir = bookFile ?: return 0L
            return runCatching { File(dir, "$name.part").length() }.getOrDefault(0L)
        }

        fun openOutput(index: Int, track: AudioTrack, append: Boolean): OutputStream {
            val name = fileName(index, track)
            if (bookDoc != null) {
                val part = bookDoc.findFile("$name.part") ?: bookDoc.createFile("audio/mpeg", "$name.part")
                if (part == null) throw IOException("Не удалось создать файл в выбранной папке")
                val mode = if (append) "wa" else "w"
                return BufferedOutputStream(
                    context.contentResolver.openOutputStream(part.uri, mode)
                        ?: throw IOException("Не удалось открыть файл для записи"),
                )
            }
            val dir = bookFile ?: throw IOException("Не удалось создать папку для книги")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать папку для книги")
            return BufferedOutputStream(FileOutputStream(File(dir, "$name.part"), append))
        }

        fun deletePart(index: Int, track: AudioTrack) {
            val name = fileName(index, track)
            val doc = bookDoc?.findFile("$name.part")
            if (doc != null) {
                doc.delete()
                return
            }
            val dir = bookFile ?: return
            runCatching { File(dir, "$name.part").delete() }
        }

        fun finishFile(index: Int, track: AudioTrack) {
            val name = fileName(index, track)
            if (bookDoc != null) {
                val part = bookDoc.findFile("$name.part") ?: return
                val existing = bookDoc.findFile(name)
                if (existing != null) {
                    if (existing.length() == 0L) existing.delete() else { part.delete(); return }
                }
                if (!part.renameTo(name)) {
                    throw IOException("Не удалось переименовать файл «$name» в выбранной папке")
                }
                return
            }
            val dir = bookFile ?: return
            val target = File(dir, name)
            if (target.exists()) {
                if (target.length() == 0L) target.delete() else return
            }
            val part = File(dir, "$name.part")
            if (part.exists() && !part.renameTo(target)) {
                throw IOException("Не удалось переименовать файл «$name»")
            }
        }

        private fun fileName(index: Int, track: AudioTrack): String =
            "%02d - %s.mp3".format(index + 1, sanitize(track.title))
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.yourapp.audiobook.action.DOWNLOAD"
        const val ACTION_CANCEL = "com.yourapp.audiobook.action.CANCEL"
        const val EXTRA_BOOK_KEY = "bookKey"

        private val FORBIDDEN = Regex("""[\\/:*?"<>|\r\n\t]""")

        private const val MAX_TRACK_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 2_000L
        private const val READ_TIMEOUT_SECONDS = 60L

        fun sanitize(name: String): String {
            val cleaned = name.replace(FORBIDDEN, " ").replace(Regex("""\s+"""), " ").trim()
            return if (cleaned.isEmpty()) "book" else cleaned.take(80)
        }
    }
}