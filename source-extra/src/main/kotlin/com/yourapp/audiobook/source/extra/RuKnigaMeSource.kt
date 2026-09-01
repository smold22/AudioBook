package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Торрент-источник «Книга.ме» (зеркало a8): https://a8.ru.kniga.me/audioknigi
 * Книги распространяются только через торрент-файлы, прямых MP3 нет.
 */
class RuKnigaMeSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "ruknigame"
    override val name = "Книга.ме (торрент)"
    override val baseUrl = "https://a8.ru.kniga.me"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override fun supportsTorrent(): Boolean = true

    override suspend fun fetchTorrentBytes(url: String): ByteArray? = getBytes(client, url)

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            val catId = categoryIds.getOrPut(url) {
                val html = getHtml(client, url)
                Regex("""/pages/1/(\d+)/page/""").find(html)?.groupValues?.get(1).orEmpty()
            }
            if (catId.isBlank()) url else "$baseUrl/pages/1/$catId/page/$page"
        }
        return fetchPage(target)
    }

    override suspend fun home(page: Int): List<Book> =
        fetchPage(if (page <= 1) "$baseUrl/audioknigi" else "$baseUrl/pages/1/page/$page")

    override suspend fun search(query: String, page: Int): List<Book> {
        val q = URLEncoder.encode(query, "UTF-8")
        return fetchPage(
            if (page <= 1) "$baseUrl/search/$q" else "$baseUrl/search/page/$page/$q",
        )
    }

    /** HTTP 404 означает конец пагинации — возвращаем пустой список. */
    private suspend fun fetchPage(url: String): List<Book> =
        runCatching { parseBooks(getHtml(client, url)) }
            .getOrElse { e ->
                if (e.message?.contains("HTTP 404") == true) emptyList() else throw e
            }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val box2 = doc.selectFirst("div.box2") ?: doc
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("div.box2 h1, h2")?.text()?.trim()
            ?: ""
        val cover = box2.selectFirst("img[src]")?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
        val boxText = box2.text()
        val author = metaValue(boxText, "Автор:")
        val reader = metaValue(boxText, "Исполнитель:")
        val genre = metaValue(boxText, "Жанр:")
        val duration = metaValue(boxText, "Продолжительность:")
        val description = doc.selectFirst("div.box2 span[style*='green']")?.text()?.trim()?.toNullIfBlank()
            ?: metaValue(boxText, "Описание:")
        val torrent = doc.selectFirst("div.download a[href]")?.attr("href")?.absUrl(baseUrl)?.toNullIfBlank()

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover,
            author = author,
            reader = reader,
            durationText = duration,
            genre = genre,
        )
        return BookDetails(
            book = book,
            description = description,
            torrentUrl = torrent,
        )
    }

    override suspend fun genres(): List<Genre> {
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/audioknigi"), baseUrl)
        doc.select("td.submenu a[href]").forEach { link ->
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
            val href = link.absUrl("href").ifBlank { return@forEach }
            if (href.contains("/audioknigi/") && seen.add(href)) {
                result += Genre(name = name, url = href)
            }
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, cardSelector = "div.box1")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.box1").mapNotNull { item ->
            val link = item.selectFirst("h2 a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val post = item.selectFirst("div.post")
            val postText = post?.text().orEmpty()
            val author = metaValue(postText, "Автор:")
            val reader = metaValue(postText, "Исполнитель:")
            val genre = metaValue(postText, "Жанр:")
            val duration = metaValue(postText, "Продолжительность:")
            val cover = item.selectFirst("img[src]")?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
            val breadcrumbGenre = item.selectFirst("div.reeedmore span a[href]")?.text()?.trim()?.toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                reader = reader,
                durationText = duration,
                genre = genre ?: breadcrumbGenre,
            )
        }
    }

    private val categoryIds = ConcurrentHashMap<String, String>()

    private companion object {
        val META_KEYS = listOf(
            "Автор:", "Год выпуска:", "Жанр:", "Издательство:",
            "Исполнитель:", "Продолжительность:", "Размер:", "Формат:", "Описание:",
        )

        fun metaValue(text: String, key: String): String? {
            val start = text.indexOf(key)
            if (start < 0) return null
            val after = text.substring(start + key.length)
            val end = META_KEYS.filter { it != key }
                .mapNotNull { after.indexOf(it).takeIf { index -> index >= 0 } }
                .minOrNull() ?: after.length
            return after.substring(0, end).trim().toNullIfBlank()
        }
    }
}