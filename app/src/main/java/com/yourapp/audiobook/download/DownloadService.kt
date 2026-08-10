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
import com.yourapp.audiobook.MainActivity
import com.yourapp.audiobook.R
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.izibuk.IziBukSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentBookKey: String? = null
    private val httpClient: OkHttpClient = IziBukSource.defaultClient()

    private val manager get() = (application as AudioBookApplication).downloadManager
    private val registry get() = (application as AudioBookApplication).sourceRegistry
    private val settings get() = (application as AudioBookApplication).settingsStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val bookKey = intent.getStringExtra(EXTRA_BOOK_KEY)
                if (bookKey == null || currentBookKey != null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
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
            ACTION_CANCEL -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        currentBookKey = null
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
            val storage = DownloadStorage(this, folderUri, bookTitle)
            val tracks = details.tracks
            if (tracks.isEmpty()) throw IOException("В книге нет доступных аудиофайлов")

            val total = tracks.size
            var done = 0
            for (index in tracks.indices) {
                if (manager.isCancelling(bookKey)) return
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
                        DownloadState(bookKey, DownloadStatus.DOWNLOADING, percent = 100, tracksDone = done, tracksTotal = total),
                    )
                    continue
                }
                downloadTrackFile(httpClient, storage, index, track) { percent ->
                    manager.setState(bookKey, DownloadState(bookKey, DownloadStatus.DOWNLOADING, percent, done, total))
                    updateNotification(
                        title = "Скачивание книги",
                        text = "$bookTitle · трек ${index + 1} из $total · $percent%",
                        percent = percent,
                        indeterminate = false,
                        ongoing = true,
                    )
                }
                done += 1
                manager.setState(
                    bookKey,
                    DownloadState(bookKey, DownloadStatus.DOWNLOADING, percent = 100, tracksDone = done, tracksTotal = total),
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
            if (manager.isCancelling(bookKey)) manager.cancelFinished(bookKey)
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
            stopSelf()
        }
    }

    private suspend fun downloadTrackFile(
        client: OkHttpClient,
        storage: DownloadStorage,
        index: Int,
        track: AudioTrack,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(track.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} при скачивании «${track.title}»")
            val body = response.body ?: throw IOException("Пустой ответ при скачивании «${track.title}»")
            val contentLength = body.contentLength()
            storage.openOutput(index, track).use { output ->
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var readCount = 0L
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    readCount += read
                    if (contentLength > 0) {
                        val percent = ((readCount * 100) / contentLength).toInt().coerceIn(0, 100)
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
            Intent(this, MainActivity::class.java)
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
            if (root == null || !root.canWrite() && !root.isDirectory) {
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

        fun openOutput(index: Int, track: AudioTrack): OutputStream {
            val name = fileName(index, track)
            if (bookDoc != null) {
                val part = bookDoc.findFile("$name.part") ?: bookDoc.createFile("audio/mpeg", "$name.part")
                if (part == null) throw IOException("Не удалось создать файл в выбранной папке")
                return BufferedOutputStream(
                    context.contentResolver.openOutputStream(part.uri)
                        ?: throw IOException("Не удалось открыть файл для записи"),
                )
            }
            val dir = bookFile ?: throw IOException("Не удалось создать папку для книги")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать папку для книги")
            return BufferedOutputStream(FileOutputStream(File(dir, "$name.part")))
        }

        fun finishFile(index: Int, track: AudioTrack) {
            val name = fileName(index, track)
            if (bookDoc != null) {
                val part = bookDoc.findFile("$name.part") ?: return
                if (bookDoc.findFile(name) != null) {
                    part.delete()
                    return
                }
                part.renameTo(name)
                return
            }
            val dir = bookFile ?: return
            val target = File(dir, name)
            if (target.exists()) return
            File(dir, "$name.part").renameTo(target)
        }

        private fun fileName(index: Int, track: AudioTrack): String =
            "%02d - %s.mp3".format(index + 1, sanitize(track.title))
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.yourapp.audiobook.action.DOWNLOAD"
        const val ACTION_CANCEL = "com.yourapp.audiobook.action.CANCEL"
        const val EXTRA_BOOK_KEY = "bookKey"

        private val FORBIDDEN = Regex("""[\\/:*?"<>|\r\n\t]""")

        fun sanitize(name: String): String {
            val cleaned = name.replace(FORBIDDEN, " ").replace(Regex("""\s+"""), " ").trim()
            return if (cleaned.isEmpty()) "book" else cleaned.take(80)
        }
    }
}