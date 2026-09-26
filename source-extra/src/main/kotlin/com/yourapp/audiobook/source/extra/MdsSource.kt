package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Источник «Модель для сборки» (mds-club.ru).
 * Данные берутся из публичного зеркала на GitHub (поле `following` в raw-данных
 * является внутренним групповым полем и не используется как серия).
 */
class MdsSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "mds"
    override val name = "Модель для сборки"
    override val baseUrl = "https://raw.githubusercontent.com/Sicness/mds-pub/refs/heads/main"

    override fun urlForId(bookId: String): String = "$baseUrl/records.json#id=$bookId"

    private val mutex = Mutex()
    @Volatile private var cachedRecords: List<MdsRecord>? = null
    @Volatile private var cachedFiles: List<MdsFile>? = null
    @Volatile private var cachedDurations: Map<Long, Int>? = null

    private fun catalogPages(records: List<MdsRecord>, page: Int, perPage: Int = 50): List<Book> {
        val start = (page - 1) * perPage
        if (start >= records.size) return emptyList()
        return records.drop(start).take(perPage).map { it.toBook() }
    }

    override suspend fun home(page: Int): List<Book> = withContext(Dispatchers.IO) {
        catalogPages(loadRecords(), page)
    }

    override suspend fun search(query: String, page: Int): List<Book> = withContext(Dispatchers.IO) {
        val records = loadRecords()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        val filtered = records.filter { rec ->
            rec.name.lowercase().contains(q) ||
                rec.author.lowercase().contains(q) ||
                rec.radioStation.lowercase().contains(q)
        }
        catalogPages(filtered, page)
    }

    override suspend fun getBookDetails(url: String): BookDetails = withContext(Dispatchers.IO) {
        val recordId = url.substringAfterLast("#id=", url.substringAfterLast("/"))
            .trim().toLongOrNull()
            ?: throw IllegalArgumentException("Некорректный URL: $url")
        val records = loadRecords()
        val record = records.find { it.id == recordId }
            ?: throw IllegalArgumentException("Книга не найдена: $recordId")
        val files = loadFiles()
        val recordFiles = files.filter { it.recordId == record.id }
        val bestFile = pickBestFile(recordFiles)
        val trackUrl = bestFile?.url ?: throw IllegalStateException("Нет аудиофайлов для книги: ${record.name}")

        val durationSeconds = loadDurations()[record.id]
        val durationText = durationSeconds?.let(::formatDuration)
        val mdsBook = record.toBook().copy(durationText = durationText)

        BookDetails(
            book = mdsBook,
            description = record.radioStation.ifBlank { null },
            tracks = listOf(
                AudioTrack(
                    title = record.name,
                    url = trackUrl,
                    durationSeconds = durationSeconds,
                ),
            ),
            seriesBooks = emptyList(),
        )
    }

    override suspend fun genres(): List<Genre> = withContext(Dispatchers.IO) {
        val records = loadRecords()
        records.groupBy { it.radioStation }
            .filter { it.key.isNotBlank() }
            .map { (station, items) ->
                Genre(
                    name = station,
                    url = "mds://genre/${java.net.URLEncoder.encode(station, "UTF-8")}",
                    bookCount = items.size.toLong(),
                )
            }
            .sortedBy { it.name }
    }

    override suspend fun books(url: String, page: Int): List<Book> = withContext(Dispatchers.IO) {
        if (!url.startsWith("mds://genre/")) return@withContext emptyList()
        val genre = java.net.URLDecoder.decode(url.substringAfter("mds://genre/"), "UTF-8")
        catalogPages(loadRecords().filter { it.radioStation == genre }, page)
    }

    override suspend fun newBooks(page: Int): List<Book> = withContext(Dispatchers.IO) {
        catalogPages(loadRecords().sortedByDescending { it.createAt }, page)
    }

    override fun supportsNew(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> = emptyList()

    private suspend fun loadRecords(): List<MdsRecord> {
        cachedRecords?.let { return it }
        return mutex.withLock {
            cachedRecords?.let { return it }
            val json = getHtml(client, "$baseUrl/records.json")
            val arr = JsonParser.parseString(json).asJsonArray
            val records = arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                MdsRecord(
                    id = obj.get("id")?.asLong ?: return@mapNotNull null,
                    name = (obj.get("name")?.asString ?: "").cleanHtml(),
                    author = (obj.get("author")?.asString ?: "").cleanHtml(),
                    radioStation = (obj.get("radioStation")?.asString ?: "").cleanHtml(),
                    createAt = obj.get("createAt")?.asString ?: "",
                )
            }
            cachedRecords = records
            records
        }
    }

    private suspend fun loadFiles(): List<MdsFile> {
        cachedFiles?.let { return it }
        return mutex.withLock {
            cachedFiles?.let { return it }
            val json = getHtml(client, "$baseUrl/files.json")
            val arr = JsonParser.parseString(json).asJsonArray
            val files = arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                MdsFile(
                    id = obj.get("id")?.asLong ?: return@mapNotNull null,
                    recordId = obj.get("recordId")?.asLong ?: return@mapNotNull null,
                    url = obj.get("url")?.asString ?: return@mapNotNull null,
                )
            }
            cachedFiles = files
            files
        }
    }

    private suspend fun loadDurations(): Map<Long, Int> {
        cachedDurations?.let { return it }
        return mutex.withLock {
            cachedDurations?.let { return it }
            val csv = getHtml(client, "$baseUrl/mds-books-duration.csv")
            val map = csv.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split(';')
                    if (parts.size < 2) return@mapNotNull null
                    val id = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                    val seconds = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                    id to seconds
                }
                .toMap()
            cachedDurations = map
            map
        }
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours ч $minutes мин"
            hours > 0 -> "$hours ч"
            minutes > 0 -> "$minutes мин"
            else -> "${seconds} сек"
        }
    }

    private fun pickBestFile(files: List<MdsFile>): MdsFile? {
        if (files.isEmpty()) return null
        val httpFiles = files.filter { it.url.startsWith("http") }
        val mirror = httpFiles.firstOrNull { it.url.contains("mds.mds-club.ru") }
            ?: httpFiles.firstOrNull { it.url.contains("mds.kallisto.ru") }
            ?: httpFiles.firstOrNull()
        return mirror ?: files.firstOrNull()
    }

    private fun MdsRecord.toBook(): Book = Book(
        sourceId = this@MdsSource.id,
        id = this.id.toString(),
        title = this.name,
        url = "mds://record/${this.id}#id=${this.id}",
        coverUrl = coverForTitle(this.name),
        author = this.author.ifBlank { null },
        reader = null,
        durationText = null,
        genre = this.radioStation.ifBlank { null },
        seriesTitle = null,
        seriesIndex = null,
        seriesUrl = null,
    )

    /** Локальная генерируемая обложка с инициалами (обрабатывается InitialsCoverFetcher). */
    private fun coverForTitle(title: String): String = "mdscover://" + title.percentEncode()

    private fun String.percentEncode(): String = buildString {
        val bytes = this@percentEncode.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A) {
                append(c.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[c ushr 4])
                append("0123456789ABCDEF"[c and 0x0F])
            }
        }
    }

    private fun String.cleanHtml(): String =
        replace("&#34;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
}

private data class MdsRecord(
    val id: Long,
    val name: String,
    val author: String,
    val radioStation: String,
    val createAt: String,
)

private data class MdsFile(
    val id: Long,
    val recordId: Long,
    val url: String,
)