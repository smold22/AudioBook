package com.yourapp.audiobook.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.Book
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Папка по умолчанию для скачанных книг: .../files/AudioBook */
fun Context.appDownloadsRoot(): File =
    getExternalFilesDir("AudioBook") ?: File(filesDir, "AudioBook")

/**
 * Имена mp3-файлов для поиска в папке книги.
 * Для обычных книг — по шаблону DownloadService, для локальных (sourceId = local)
 * — имя файла из track.url как есть (именно так они лежат в папке сканирования).
 */
fun trackFileNames(details: BookDetails): List<String> =
    details.tracks.mapIndexed { index, track ->
        if (track.url.startsWith("http")) DownloadService.trackFileName(index, track)
        else track.url
    }

enum class DownloadStatus { DOWNLOADING, COMPLETE, ERROR }

data class DownloadState(
    val bookKey: String,
    val status: DownloadStatus,
    val percent: Int = 0,
    val tracksDone: Int = 0,
    val tracksTotal: Int = 0,
    val errorMessage: String? = null,
    val trackIndex: Int? = null,
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

    private val _downloadedTracks = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val downloadedTracks: StateFlow<Map<String, Set<String>>> = _downloadedTracks.asStateFlow()

    /** Растёт при изменении метаданных книги (например, когда подгрузилась обложка). */
    private val _metaVersion = MutableStateFlow(0L)
    val metaVersion: StateFlow<Long> = _metaVersion.asStateFlow()

    private val cancelling = ConcurrentHashMap.newKeySet<String>()

    /** Книги, для которых обложка уже проверена в текущей сессии. */
    private val _coverVerified = ConcurrentHashMap.newKeySet<String>()

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
            migrateDownloadRoot()
            val books = settingsStore.downloadedBooks()
            _downloadedBooks.update { it + books }
            _downloadedKeys.update { it + books.keys }
            _downloadedTracks.update { settingsStore.downloadedTrackNames() }
            migrateLegacyDownloads()
        }
    }

    /** Переносит ранее скачанные книги из files/Download в files/AudioBook. */
    private fun migrateDownloadRoot() {
        val oldRoot = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val newRoot = appContext.appDownloadsRoot()
        if (oldRoot.absolutePath == newRoot.absolutePath) return
        if (!oldRoot.isDirectory) return
        newRoot.mkdirs()
        oldRoot.listFiles { f -> f.isDirectory }?.forEach { dir ->
            if (dir.name == ".torrents" || hasMp3Files(dir)) {
                val target = File(newRoot, dir.name)
                if (target.exists()) return@forEach
                if (!dir.renameTo(target)) {
                    runCatching { dir.copyRecursively(target) }
                    dir.deleteRecursively()
                }
            }
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
        val appDir = File(appContext.appDownloadsRoot(), dirName)
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

    /**
     * Сканирует выбранную папку: каждую подпапку с mp3-файлами регистрирует как
     * скачанную книгу (локальный источник) и пытается найти обложку по названию
     * через интернет. Возвращает список bookKey найденных книг.
     *
     * Дубликаты пропускаются: если набор имён mp3-файлов в подпапке совпадает
     * с треками уже зарегистрированной книги (из любого источника), такая
     * подпапка не добавляется повторно.
     */
    suspend fun scanFolderForBooks(folderUri: String): List<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = DocumentFile.fromTreeUri(appContext, Uri.parse(folderUri)) ?: return@runCatching emptyList<String>()

                // Снимок имён треков уже скачанных книг для проверки дубликатов.
                val existingTrackSets = settingsStore.downloadedBooks().keys.mapNotNull { key ->
                    offlineDetails(key)?.let { d -> trackFileNames(d).toSet() }
                }

                val found = mutableListOf<String>()

                // 1. Проверяем случай: если САМА выбранная папка содержит mp3-файлы книги напрямую.
                val directFiles = root.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".mp3", ignoreCase = true) == true }
                    ?.sortedBy { it.name }
                    ?: emptyList()

                if (directFiles.isNotEmpty()) {
                    val rootName = root.name?.takeIf { it.isNotBlank() } ?: "Аудиокнига"
                    val bookKey = localBookKey(rootName)
                    val fileNames = directFiles.mapNotNull { it.name }
                    val alreadyReg = isAlreadyRegistered(fileNames, existingTrackSets)

                    if (!alreadyReg || bookKey in _downloadedBooks.value || bookKey in _downloadedTracks.value) {
                        val tracks = directFiles.mapIndexedNotNull { index, file ->
                            val name = file.name ?: return@mapIndexedNotNull null
                            AudioTrack(
                                title = trackTitleFromFileName(name, index),
                                url = name,
                            )
                        }
                        if (tracks.isNotEmpty()) {
                            val coverUrl = findCoverInFolder(root, directFiles, bookKey)
                            val book = Book(
                                sourceId = LOCAL_SOURCE_ID,
                                id = Uri.encode(rootName),
                                title = friendlyTitleFromDirName(rootName),
                                url = "",
                                coverUrl = coverUrl,
                            )
                            finishDownload(bookKey, folderUri, OfflineMeta.encode(BookDetails(book = book, tracks = tracks)))
                            if (coverUrl == null) {
                                scope.launch { refreshLocalCover(bookKey) }
                            }
                            found.add(bookKey)
                        }
                    }
                }

                // 2. Проверяем вложенные папки (когда выбрана общая папка, содержащая папки с книгами).
                for (dir in root.listFiles().filter { it.isDirectory }) {
                    val dirName = dir.name ?: continue
                    val files = dir.listFiles()
                        ?.filter { it.isFile && it.name?.endsWith(".mp3", ignoreCase = true) == true }
                        ?.sortedBy { it.name }
                        ?: emptyList()
                    if (files.isEmpty()) continue

                    val bookKey = localBookKey(dirName)
                    val fileNames = files.mapNotNull { it.name }
                    val alreadyReg = isAlreadyRegistered(fileNames, existingTrackSets)

                    if (!alreadyReg || bookKey in _downloadedBooks.value || bookKey in _downloadedTracks.value) {
                        val tracks = files.mapIndexedNotNull { index, file ->
                            val name = file.name ?: return@mapIndexedNotNull null
                            AudioTrack(
                                title = trackTitleFromFileName(name, index),
                                url = name,
                            )
                        }
                        if (tracks.isEmpty()) continue
                        val coverUrl = findCoverInFolder(dir, files, bookKey)
                        val book = Book(
                            sourceId = LOCAL_SOURCE_ID,
                            id = Uri.encode(dirName),
                            title = friendlyTitleFromDirName(dirName),
                            url = dirName,
                            coverUrl = coverUrl,
                        )
                        finishDownload(bookKey, folderUri, OfflineMeta.encode(BookDetails(book = book, tracks = tracks)))
                        if (coverUrl == null) {
                            scope.launch { refreshLocalCover(bookKey) }
                        }
                        found.add(bookKey)
                    }
                }
                found
            }.getOrElse { emptyList() }
        }

    /** Сканирует стандартную папку приложения (appDownloadsRoot) для книг, помещённых туда вручную. */
    suspend fun scanAppDownloadsRoot(): List<String> =
        withContext(Dispatchers.IO) {
            val root = appContext.appDownloadsRoot()
            if (!root.isDirectory) return@withContext emptyList()
            val existingTrackSets = settingsStore.downloadedBooks().keys.mapNotNull { key ->
                offlineDetails(key)?.let { d -> trackFileNames(d).toSet() }
            }
            val found = mutableListOf<String>()

            // 1. Прямые файлы в корне appDownloadsRoot
            val directFiles = root.listFiles { f -> f.isFile && f.name.endsWith(".mp3", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList<File>()
            if (directFiles.isNotEmpty()) {
                val rootName = "Аудиокнига"
                val fileNames = directFiles.mapNotNull { it.name }
                val bookKey = localBookKey(rootName)
                if (bookKey in _downloadedBooks.value || bookKey in _downloadedTracks.value) {
                    refreshLocalFolderBook(bookKey)
                    found.add(bookKey)
                } else if (!isAlreadyRegistered(fileNames, existingTrackSets)) {
                    val tracks = fileNames.mapIndexed { index, name ->
                        AudioTrack(title = trackTitleFromFileName(name, index), url = name)
                    }
                    val coverUrl = findCoverInDirectory(root, directFiles, bookKey)
                    val book = Book(sourceId = LOCAL_SOURCE_ID, id = Uri.encode(rootName), title = friendlyTitleFromDirName(rootName), url = "", coverUrl = coverUrl)
                    finishDownload(bookKey, APP_FOLDER, OfflineMeta.encode(BookDetails(book = book, tracks = tracks)))
                    if (coverUrl == null) {
                        scope.launch { refreshLocalCover(bookKey) }
                    }
                    found.add(bookKey)
                }
            }

            // 2. Вложенные папки
            root.listFiles { f -> f.isDirectory }?.forEach { dir ->
                val dirName = dir.name ?: return@forEach
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp3", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    ?.toList()
                    ?: emptyList()
                val fileNames = files.mapNotNull { it.name }
                if (fileNames.isEmpty()) return@forEach
                val bookKey = localBookKey(dirName)
                if (bookKey in _downloadedBooks.value || bookKey in _downloadedTracks.value) {
                    refreshLocalFolderBook(bookKey)
                    found.add(bookKey)
                    return@forEach
                }
                if (isAlreadyRegistered(fileNames, existingTrackSets)) return@forEach
                val tracks = fileNames.mapIndexed { index, name ->
                    AudioTrack(title = trackTitleFromFileName(name, index), url = name)
                }
                val coverUrl = findCoverInDirectory(dir, files, bookKey)
                val book = Book(sourceId = LOCAL_SOURCE_ID, id = Uri.encode(dirName), title = friendlyTitleFromDirName(dirName), url = dirName, coverUrl = coverUrl)
                finishDownload(bookKey, APP_FOLDER, OfflineMeta.encode(BookDetails(book = book, tracks = tracks)))
                if (coverUrl == null) {
                    refreshLocalCover(bookKey)
                }
                found.add(bookKey)
            }
            found
        }

    /** Запускает сканирование текущей папки (или стандартной папки приложения, если папка не выбрана). */
    fun scanCurrentFolder(onResult: ((Int) -> Unit)? = null) {
        scope.launch {
            val folder = settingsStore.currentDownloadFolder()
            cleanUpOldLocalBooks(folder)
            val count = if (folder != null) scanFolderForBooks(folder).size else scanAppDownloadsRoot().size
            withContext(Dispatchers.Main) { onResult?.invoke(count) }
        }
    }

    /** Запускает сканирование конкретной папки (используется сразу после её выбора). */
    fun scanFolder(folderUri: String, onResult: ((Int) -> Unit)? = null) {
        scope.launch {
            cleanUpOldLocalBooks(folderUri)
            val count = scanFolderForBooks(folderUri).size
            withContext(Dispatchers.Main) { onResult?.invoke(count) }
        }
    }

    /**
     * Удаляет из базы локальные книги (sourceId == LOCAL_SOURCE_ID), которые были привязаны
     * к предыдущей выбранной папке, чтобы при смене папки не оставались книги из старой папки.
     */
    private suspend fun cleanUpOldLocalBooks(newFolderUri: String?) {
        withContext(Dispatchers.IO) {
            val allLocalKeys = _downloadedBooks.value.filter { (key, folder) ->
                key.startsWith("$LOCAL_SOURCE_ID:") && folder != newFolderUri && folder != APP_FOLDER
            }.keys

            for (key in allLocalKeys) {
                runCatching { settingsStore.removeDownloadedBook(key) }
                _downloadedBooks.update { it - key }
                _downloadedKeys.update { it - key }
                _downloadedTracks.update { it - key }
                _states.update { it - key }
            }
        }
    }

    private fun localBookKey(dirName: String): String = "$LOCAL_SOURCE_ID:${Uri.encode(dirName)}"

    /**
     * Проверяет, зарегистрированы ли файлы с такими именами уже в другой книге.
     * Сравнивает набор имён файлов с треками всех скачанных книг —
     * полное совпадение означает, что папка дублирует существующую запись.
     */
    private fun isAlreadyRegistered(fileNames: List<String>, existingTrackSets: List<Set<String>>): Boolean {
        val scannedSet = fileNames.toSet()
        return existingTrackSets.any { set -> set.size == scannedSet.size && set.all { it in scannedSet } }
    }

    /**
     * Ищет обложку книги в папке:
     * 1. Сначала проверяет графические файлы в самой папке (cover.jpg, folder.jpg, *.png, *.webp и т.д.).
     * 2. Если отдельного файла картинки нет — извлекает встроенную обложку из MP3 тегов (ID3 APIC).
     */
    private fun findCoverInFolder(dir: DocumentFile, mp3Files: List<DocumentFile>, bookKey: String): String? {
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp")
        // Получаем файлы ТОЛЬКО из конкретной директории книги, фильтруем подпапки
        val allFiles = dir.listFiles().filter { it.isFile }
        
        // 1. Приоритетные имена файлов обложек конкретно в папке этой книги
        val priorityNames = listOf("cover", "folder", "front", "poster", "artwork", "обложка")
        for (pName in priorityNames) {
            val img = allFiles.firstOrNull { file ->
                imageExtensions.any { ext -> file.name.equals("$pName$ext", ignoreCase = true) }
            }
            if (img != null) return img.uri.toString()
        }

        // 2. Любой графический файл конкретно в папке этой книги
        val anyImage = allFiles.firstOrNull { file ->
            imageExtensions.any { ext -> file.name?.endsWith(ext, ignoreCase = true) == true }
        }
        if (anyImage != null) return anyImage.uri.toString()

        // 3. Встроенная обложка из MP3 файлов этой конкретной книги
        return extractEmbeddedCover(mp3Files, bookKey)
    }

    private fun findCoverInDirectory(dir: File, mp3Files: List<File>, bookKey: String): String? {
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp")
        val allFiles = dir.listFiles { f -> f.isFile } ?: emptyArray()

        // 1. Приоритетные имена файлов обложек конкретно в папке этой книги
        val priorityNames = listOf("cover", "folder", "front", "poster", "artwork", "обложка")
        for (pName in priorityNames) {
            val img = allFiles.firstOrNull { file ->
                imageExtensions.any { ext -> file.name.equals("$pName$ext", ignoreCase = true) }
            }
            if (img != null) return Uri.fromFile(img).toString()
        }

        // 2. Любой графический файл конкретно в папке этой книги
        val anyImage = allFiles.firstOrNull { file ->
            imageExtensions.any { ext -> file.name.endsWith(ext, ignoreCase = true) }
        }
        if (anyImage != null) return Uri.fromFile(anyImage).toString()

        // 3. Встроенная обложка из MP3 файлов этой конкретной книги
        return extractEmbeddedCoverFromFiles(mp3Files, bookKey)
    }

    /**
     * Извлекает встроенную обложку книги (ID3 APIC-тег) из первого подходящего mp3 файла
     * и сохраняет её локально в кэш картинок под УНИКАЛЬНЫМ ключом книги, возвращая file:// URI.
     */
    private fun extractEmbeddedCover(files: List<DocumentFile>, bookKey: String): String? {
        val hash = bookKey.hashCode().toUInt().toString(16)
        val safeKey = bookKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
        val coverFile = File(appContext.cacheDir, "cover_${safeKey}_$hash.jpg")

        // Проверяем MP3 файлы только этой книги
        val sampleFiles = files.take(2)
        val retriever = MediaMetadataRetriever()
        try {
            for (file in sampleFiles) {
                try {
                    appContext.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        val picBytes: ByteArray? = retriever.embeddedPicture
                        if (picBytes != null && picBytes.isNotEmpty()) {
                            coverFile.outputStream().use { fos -> fos.write(picBytes) }
                            return Uri.fromFile(coverFile).toString()
                        }
                    }
                } catch (ignored: Exception) {
                }
            }
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    private fun extractEmbeddedCoverFromFiles(files: List<File>, bookKey: String): String? {
        val hash = bookKey.hashCode().toUInt().toString(16)
        val safeKey = bookKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
        val coverFile = File(appContext.cacheDir, "cover_${safeKey}_$hash.jpg")

        // Проверяем MP3 файлы только этой книги
        val sampleFiles = files.take(2)
        val retriever = MediaMetadataRetriever()
        try {
            for (file in sampleFiles) {
                try {
                    retriever.setDataSource(file.absolutePath)
                    val picBytes: ByteArray? = retriever.embeddedPicture
                    if (picBytes != null && picBytes.isNotEmpty()) {
                        coverFile.outputStream().use { fos -> fos.write(picBytes) }
                        return Uri.fromFile(coverFile).toString()
                    }
                } catch (ignored: Exception) {
                }
            }
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
        return null
    }
    private fun trackTitleFromFileName(name: String, index: Int): String =
        name.removeSuffix(".mp3")
            .replace(Regex("^\\s*\\d+\\s*[-._]?\\s*"), "")
            .replace(Regex("[-._]+"), " ")
            .trim()
            .ifEmpty { "Отрывок ${index + 1}" }

    /**
     * Сохраняет выбранную пользователем картинку (из галереи) как обложку для книги.
     */
    suspend fun setCustomCover(bookKey: String, imageUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val hash = bookKey.hashCode().toUInt().toString(16)
            val safeKey = bookKey.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30)
            val coverFile = File(appContext.cacheDir, "cover_${safeKey}_$hash.jpg")

            appContext.contentResolver.openInputStream(imageUri)?.use { input ->
                coverFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false

            val latest = offlineDetails(bookKey) ?: return@withContext false
            val fileUrl = Uri.fromFile(coverFile).toString()
            settingsStore.saveBookMeta(
                bookKey,
                OfflineMeta.encode(latest.copy(book = latest.book.copy(coverUrl = fileUrl))),
            )
            _metaVersion.update { it + 1 }
            true
        }.getOrDefault(false)
    }

    /**
     * Принудительно извлекает встроенную обложку из файлов книги и обновляет её в метаданных.
     * Возвращает true, если обложка была найдена и сохранена.
     */
    suspend fun extractAndSetEmbeddedCover(bookKey: String): Boolean = withContext(Dispatchers.IO) {
        val folderRef = _downloadedBooks.value[bookKey] ?: settingsStore.downloadedBooks()[bookKey] ?: return@withContext false
        val details = offlineDetails(bookKey) ?: return@withContext false
        val dirName = details.book.url

        val coverUrl: String? = if (folderRef == APP_FOLDER) {
            val root = appContext.appDownloadsRoot()
            val bookDir = if (dirName.isEmpty()) root else File(root, dirName)
            val files = bookDir.listFiles { f -> f.isFile && f.name.endsWith(".mp3", ignoreCase = true) }?.toList().orEmpty()
            extractEmbeddedCoverFromFiles(files, bookKey)
        } else {
            val rootDoc = DocumentFile.fromTreeUri(appContext, Uri.parse(folderRef)) ?: return@withContext false
            val targetDir = if (dirName.isEmpty()) rootDoc else rootDoc.findFile(dirName) ?: rootDoc
            val files = targetDir.listFiles().filter { it.isFile && it.name?.endsWith(".mp3", ignoreCase = true) == true }
            extractEmbeddedCover(files, bookKey)
        }

        if (coverUrl != null) {
            val latest = offlineDetails(bookKey) ?: return@withContext false
            settingsStore.saveBookMeta(
                bookKey,
                OfflineMeta.encode(latest.copy(book = latest.book.copy(coverUrl = coverUrl))),
            )
            _metaVersion.update { it + 1 }
            true
        } else {
            false
        }
    }

    /** Если у локальной книги нет обложки — ищет её по названию через поиск в источниках. */
    private suspend fun refreshLocalCover(bookKey: String) {
        withTimeoutOrNull(8_000) {
            val details = offlineDetails(bookKey) ?: return@withTimeoutOrNull
            if (details.book.coverUrl != null) return@withTimeoutOrNull
            val cover = findCoverUrl(details.book.title) ?: return@withTimeoutOrNull
            runCatching {
                settingsStore.saveBookMeta(
                    bookKey,
                    OfflineMeta.encode(details.copy(book = details.book.copy(coverUrl = cover))),
                )
                _metaVersion.update { it + 1 }
            }
        }
    }

    /**
     * Для уже зарегистрированных сканированных книг: если хранимое название —
     * транслитерация, переводит его в кириллицу и заново ищет обложку по
     * настоящему названию (старая могла быть найдена по неточному совпадению).
     */
    private suspend fun refreshLocalFolderBook(bookKey: String, files: List<DocumentFile>? = null) {
        val details = offlineDetails(bookKey) ?: return
        val expectedTitle = friendlyTitleFromDirName(details.book.url)

        // 1. Название — мгновенно, без сети. UI обновится сразу.
        if (details.book.title != expectedTitle) {
            runCatching {
                settingsStore.saveBookMeta(
                    bookKey,
                    OfflineMeta.encode(details.copy(book = details.book.copy(title = expectedTitle))),
                )
                _metaVersion.update { it + 1 }
            }
        }

        // 2. Если обложки нет, пробуем сначала извлечь локальную встроенную обложку для этой книги
        if (details.book.coverUrl.isNullOrBlank() && files != null) {
            val localCover = extractEmbeddedCover(files, bookKey)
            if (localCover != null) {
                val latest = offlineDetails(bookKey) ?: return
                runCatching {
                    settingsStore.saveBookMeta(
                        bookKey,
                        OfflineMeta.encode(latest.copy(book = latest.book.copy(coverUrl = localCover))),
                    )
                    _metaVersion.update { it + 1 }
                }
                return
            }
        }

        // 3. Онлайн-поиск обложки запускаем ТОЛЬКО если у книги до сих пор нет обложки
        if (details.book.coverUrl.isNullOrBlank() && !_coverVerified.contains(bookKey)) {
            val cover = withTimeoutOrNull(25_000) { findCoverUrl(expectedTitle) }
            if (cover != null) {
                val latest = offlineDetails(bookKey) ?: return
                runCatching {
                    settingsStore.saveBookMeta(
                        bookKey,
                        OfflineMeta.encode(latest.copy(book = latest.book.copy(coverUrl = cover))),
                    )
                    _metaVersion.update { it + 1 }
                }
            }
            _coverVerified.add(bookKey)
        }
    }

    private suspend fun findCoverUrl(title: String): String? {
        val normalized = normalizeSearch(title)
        val shortTitle = title.substringBefore(".").substringBefore(" - ").trim()
        val mediumTitle = title.substringBefore(" - ").trim()
        val queries = buildList {
            if (shortTitle.length >= 3) add(shortTitle)
            if (mediumTitle.length >= 3 && mediumTitle != shortTitle) add(mediumTitle)
            add(title.trim())
            // Доп. запрос: только первые 3-4 слова (название без автора/продолжения).
            val words = normalized.split(" ").take(4).joinToString(" ")
            if (words.length >= 5 && words != normalizeSearch(shortTitle)) add(words)
            // Транслит-вариант (имя папки как есть): некоторые источники хранят его так же.
            add(title)
        }.distinct()
        Log.d("CoverFind", "query=$title → $queries")
        for (query in queries) {
            for (source in app.sourceRegistry.sources) {
                val results = runCatching { source.search(query, 20) }.getOrNull() ?: continue
                val byCover = results.filter { it.coverUrl != null }
                val byTitle = byCover.filter { coverTitleMatches(it.title, query) }
                if (byTitle.isNotEmpty()) {
                    val choice = byTitle.first()
                    Log.d("CoverFind", "MATCH query='$query' source=${source.id} title='${choice.title}' cover=${choice.coverUrl}")
                    return choice.coverUrl
                }
                if (byCover.isNotEmpty()) {
                    Log.d("CoverFind", "no-match query='$query' source=${source.id} candidates=${byCover.take(3).map { "'${it.title}'" }}")
                }
            }
        }
        return null
    }

    private fun normalizeSearch(s: String): String =
        s.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun coverTitleMatches(sourceTitle: String, query: String): Boolean {
        val a = normalizeSearch(sourceTitle)
        val b = normalizeSearch(query)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.contains(b)) return b.length >= 5
        if (b.contains(a)) return a.length >= 5
        return false
    }

    /** Название книги из имени папки: обратная транслитерация «Zhrec-Haosa...» → «Жрец-Хаоса...». */
    private fun friendlyTitleFromDirName(dirName: String): String {
        val name = dirName.trim()
        if (name.isEmpty()) return name
        val base = if (name.none { it in 'А'..'я' } && looksLikeTransliteratedRussian(name)) {
            latinToCyrillic(name)
        } else {
            name
        }
        return base
            .replace(Regex("_\\d+\\s*$"), "")
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Эвристика: имя папки почти наверняка транслитерация русской книги, а не английское название. */
    private fun looksLikeTransliteratedRussian(name: String): Boolean {
        val lower = name.lowercase()
        val digraphs = listOf("shch", "zh", "kh", "ts", "ch", "sh", "yu", "ya", "yo")
        if (digraphs.any { it in lower }) return true
        return Regex("_\\d+").containsMatchIn(name)
    }

    private val cyrillicMap = mapOf(
        "shch" to "щ", "zh" to "ж", "kh" to "х", "ts" to "ц", "ch" to "ч",
        "sh" to "ш", "yu" to "ю", "ya" to "я", "yo" to "ё", "ye" to "е",
        "a" to "а", "b" to "б", "v" to "в", "g" to "г", "d" to "д", "e" to "е",
        "z" to "з", "i" to "и", "j" to "й", "k" to "к", "l" to "л", "m" to "м",
        "n" to "н", "o" to "о", "p" to "п", "r" to "р", "s" to "с", "t" to "т",
        "u" to "у", "f" to "ф", "h" to "х", "c" to "ц", "y" to "ы", "w" to "в",
        "q" to "к", "x" to "кс",
    )

    private fun latinToCyrillic(input: String): String {
        val roman = mutableListOf<String>()
        val masked = Regex("\\b[IVXLCDMivxlcdm]+\\b").replace(input) { m ->
            roman.add(m.value)
            "\uE000${roman.size - 1}\uE001"
        }
        val protected = Regex("\uE000(\\d+)\uE001").findAll(masked).toList()
        val sb = StringBuilder()
        var last = 0
        for (m in protected) {
            sb.append(transliteratePortion(masked, last, m.range.first))
            sb.append(roman[m.groupValues[1].toInt()])
            last = m.range.last + 1
        }
        sb.append(transliteratePortion(masked, last, masked.length))
        return sb.toString()
    }

    private fun transliteratePortion(s: String, start: Int, end: Int): String {
        val sb = StringBuilder()
        var i = start
        while (i < end) {
            var mapped: String? = null
            var lenUsed = 0
            for (len in 4 downTo 1) {
                if (i + len > end) continue
                val value = cyrillicMap[s.substring(i, i + len).lowercase()]
                if (value != null) {
                    mapped = value
                    lenUsed = len
                    break
                }
            }
            if (mapped != null) {
                sb.append(if (s[i].isUpperCase()) mapped.replaceFirstChar { it.uppercase() } else mapped)
                i += lenUsed
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
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

    /** Скачивание одного трека книги. */
    fun startDownloadTrack(context: Context, bookKey: String, trackIndex: Int) {
        if (_states.value.values.any { it.status == DownloadStatus.DOWNLOADING }) return
        if (isDownloaded(bookKey)) return
        cancelling.remove(bookKey)
        _states.update {
            it + (bookKey to DownloadState(bookKey, DownloadStatus.DOWNLOADING, tracksTotal = 1, trackIndex = trackIndex))
        }
        try {
            context.startForegroundService(
                Intent(context, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_DOWNLOAD)
                    .putExtra(DownloadService.EXTRA_BOOK_KEY, bookKey)
                    .putExtra(DownloadService.EXTRA_TRACK_INDEX, trackIndex),
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

    /** Отмечает отдельный трек книги как скачанный (используется при полном скачивании книги). */
    suspend fun recordTrackDownloaded(bookKey: String, fileName: String) {
        val updated = _downloadedTracks.value[bookKey].orEmpty() + fileName
        runCatching { settingsStore.saveDownloadedTracks(bookKey, updated) }
        _downloadedTracks.update { it + (bookKey to updated) }
    }

    /** Завершение скачивания одного трека: книга не помечается полностью скачанной. */
    suspend fun finishTrackDownload(bookKey: String, fileName: String, metaJson: String) {
        cancelling.remove(bookKey)
        if (settingsStore.bookMeta(bookKey) == null) {
            runCatching { settingsStore.saveBookMeta(bookKey, metaJson) }
        }
        val updated = _downloadedTracks.value[bookKey].orEmpty() + fileName
        runCatching { settingsStore.saveDownloadedTracks(bookKey, updated) }
        _downloadedTracks.update { it + (bookKey to updated) }
        _states.update { it + (bookKey to DownloadState(bookKey, DownloadStatus.COMPLETE)) }
    }

    fun cancelFinished(bookKey: String) {
        cancelling.remove(bookKey)
        _states.update { it - bookKey }
    }

    suspend fun offlineDetails(bookKey: String): BookDetails? {
        val meta = settingsStore.bookMeta(bookKey) ?: return null
        return OfflineMeta.decode(meta)
    }

    suspend fun deleteDownloadedBook(bookKey: String): Boolean {
        val folderRef = _downloadedBooks.value[bookKey]
        val dirNames = buildSet {
            offlineDetails(bookKey)?.book?.let { book ->
                if (book.url.isNotBlank()) add(book.url)
                if (book.title.isNotBlank()) {
                    add(DownloadService.sanitize(book.title))
                    if (book.title != DownloadService.sanitize(book.title)) add(book.title)
                }
            }
        }
        if (dirNames.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                // Удаляем папку книги из всех возможных мест хранения.
                val locationUris = buildList {
                    folderRef?.takeIf { it != APP_FOLDER }?.let { add(it) }
                    settingsStore.currentDownloadFolder()?.let { add(it) }
                }.distinct()
                for (uri in locationUris) {
                    for (dirName in dirNames) {
                        runCatching {
                            DocumentFile.fromTreeUri(appContext, Uri.parse(uri))
                                ?.findFile(dirName)
                                ?.delete()
                        }
                    }
                }
                val legacyRoot = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "")
                for (dirName in dirNames) {
                    File(appContext.appDownloadsRoot(), dirName).deleteRecursively()
                    File(legacyRoot, dirName)
                        .takeIf { it.absolutePath != File(appContext.appDownloadsRoot(), dirName).absolutePath }
                        ?.deleteRecursively()
                }
            }
        }
        runCatching { settingsStore.removeDownloadedBook(bookKey) }
        _downloadedBooks.update { it - bookKey }
        _downloadedKeys.update { it - bookKey }
        _downloadedTracks.update { it - bookKey }
        _states.update { it - bookKey }
        return true
    }

suspend fun offlineTrackUris(bookKey: String, trackNames: List<String>): List<Uri?>? {
        // Читаем сохранённое состояние прямо из DataStore, а не из памяти,
        // чтобы корректно работать сразу после старта процесса (до загрузки init).
        val folderRef = settingsStore.downloadedBooks()[bookKey]
        val downloaded = settingsStore.downloadedTrackNames()[bookKey].orEmpty()
        if (folderRef == null && downloaded.isEmpty()) return null
        val details = offlineDetails(bookKey) ?: return null

        // Имя папки книги: для локальных книг берём точное имя папки (book.url),
        // для скачанных — sanitize(title). Затем — сам title (может содержать индексы).
        val dirCandidates = linkedSetOf<String>()
        details.book.url.let { dirCandidates.add(it) } // Включаем пустую строку "", если файлы прямо в выбранной папке
        details.book.title.takeIf { it.isNotBlank() }?.let { dirCandidates.add(DownloadService.sanitize(it)) }
        details.book.title.takeIf { it.isNotBlank() }?.let { dirCandidates.add(it) }

        // Собираем кандидатов из всех известных мест хранения.
        val candidates = linkedMapOf<String, Uri>()
        fun addFile(name: String, uri: Uri) {
            val logical = name.removeSuffix(".part.mp3")
            if (logical.endsWith(".mp3")) candidates.putIfAbsent(logical, uri)
        }

        for (dirName in dirCandidates) {
            // 1. Папка, указанная в настройках на момент скачивания (content URI).
            if (folderRef != null && folderRef != APP_FOLDER) {
                runCatching {
                    val rootDoc = DocumentFile.fromTreeUri(appContext, Uri.parse(folderRef))
                    if (dirName.isEmpty()) {
                        // Файлы лежат напрямую в выбранной папке
                        rootDoc?.listFiles()?.forEach { file ->
                            file.name?.let { addFile(it, file.uri) }
                        }
                    } else {
                        rootDoc?.findFile(dirName)
                            ?.listFiles()
                            ?.forEach { file ->
                                file.name?.let { addFile(it, file.uri) }
                            }
                    }
                }
                // Резолвим URI в файловый путь (для встроенной памяти и SD-карт).
                runCatching {
                    resolveTreeUriToFile(Uri.parse(folderRef))?.let { rootDir ->
                        val bookDir = if (dirName.isEmpty()) rootDir else File(rootDir, dirName)
                        bookDir.listFiles { f -> f.isFile && f.name.endsWith(".mp3") }?.forEach { file ->
                            addFile(file.name, Uri.fromFile(file))
                        }
                    }
                }
            }

            // 2. Текущая выбранная папка (пользователь мог переключить её после скачивания).
            runCatching {
                val currentFolder = settingsStore.currentDownloadFolder()?.let(Uri::parse)
                if (currentFolder != null) {
                    val rootDoc = DocumentFile.fromTreeUri(appContext, currentFolder)
                    if (dirName.isEmpty()) {
                        rootDoc?.listFiles()?.forEach { file ->
                            file.name?.let { addFile(it, file.uri) }
                        }
                    } else {
                        rootDoc?.findFile(dirName)
                            ?.listFiles()
                            ?.forEach { file ->
                                file.name?.let { addFile(it, file.uri) }
                            }
                    }
                }
            }
        }

        for (dirName in dirCandidates) {
            // 3. Личная папка приложения.
            runCatching {
                File(appContext.appDownloadsRoot(), dirName)
                    .listFiles { f -> f.isFile && f.name.endsWith(".mp3") }?.forEach { file ->
                        addFile(file.name, Uri.fromFile(file))
                    }
            }

            // 4. Устаревшая папка загрузок приложения.
            runCatching {
                File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), dirName)
                    .listFiles { f -> f.isFile && f.name.endsWith(".mp3") }?.forEach { file ->
                        addFile(file.name, Uri.fromFile(file))
                    }
            }
        }

        if (candidates.isEmpty()) return null
        val uris = trackNames.map { candidates[it] }.toMutableList()
        // Если точного совпадения нет — пробуем нормализованное сравнение имён.
        if (uris.any { it == null }) {
            val normalized = candidates.entries.associateBy({ normalizeFileName(it.key) }, { it.value })
            trackNames.forEachIndexed { i, name ->
                if (uris[i] == null) uris[i] = normalized[normalizeFileName(name)]
            }
        }
        return uris
    }

    /** Преобразует content:// tree URI (ExternalStorageProvider) в файловый путь. */
    private fun resolveTreeUriToFile(treeUri: Uri): File? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        val path = parts[1]
        val root = when {
            volume == "primary" -> Environment.getExternalStorageDirectory()
            else -> runCatching {
                File("/storage").listFiles()?.firstOrNull { it.name == volume }
            }.getOrNull()
        } ?: return null
        return File(root, path)
    }

    /** Нормализует имя файла для неточного сопоставления. */
    private fun normalizeFileName(name: String): String =
        name.trim()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[_\-]"""), " ")
            .replace(Regex("""[""\u0027`]"""), "")
            .lowercase()

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1001
        const val APP_FOLDER = "app"

        /** Идентификатор локального источника для книг, найденных сканированием папки. */
        const val LOCAL_SOURCE_ID = "local"
    }
}