package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Источник «Книга Аудио»: https://knigaaudio.com
 * DataLife Engine + Playerjs. Плейлист и MP3 лежат на CDN redirectto.cc и
 * требуют заголовок Referer с этого сайта — треки помечаются меткой ref.
 */
class KnigaAudioSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "knigaaudio"
    override val name = "Книга Аудио"
    override val baseUrl = "https://knigaaudio.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/page/$page/"))

    override suspend fun search(query: String, page: Int): List<Book> {
        val url = "$baseUrl/index.php?do=search&subaction=search&story=${URLEncoder.encode(query, "UTF-8")}"
        return parseBooks(getHtml(client, url))
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("article.full div.fright h1, div.fright h1")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("div.fposter img[src]")?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
        val genre = doc.select("ul.flist a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (href.contains("/tags/") || href.contains("/xfsearch/")) null else a.text().trim().takeIf { it.isNotBlank() }
        }.joinToString(", ").toNullIfBlank()
        val author = doc.selectFirst("ul.flist a[href*='/tags/']")?.text()?.trim().toNullIfBlank()
        val reader = doc.selectFirst("ul.flist a[href*='/xfsearch/performer/']")?.text()?.trim().toNullIfBlank()
        val description = doc.selectFirst("div.fdesc.full-text")?.text()?.trim().toNullIfBlank()

        val tracks = fetchTracks(html, url)

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover,
            author = author,
            reader = reader,
            genre = genre,
        )
        return BookDetails(book = book, description = description, tracks = tracks)
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else "${url.trimEnd('/')}/page/$page/"
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres.html"), baseUrl)
        doc.select("a.ser-item[href]").forEach { link ->
            val href = link.absUrl("href").ifBlank { return@forEach }
            val name = link.selectFirst("div.ser-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@forEach
            if (seen.add(href)) result += Genre(name = name, url = href)
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, cardSelector = "div.th-item")

    private suspend fun fetchTracks(html: String, bookUrl: String): List<com.yourapp.audiobook.source.api.AudioTrack> {
        val playlist = playerjsPlaylistUrl(html) ?: return emptyList()
        val json = runCatching { getHtml(client, playlist, referer = bookUrl) }.getOrNull() ?: return emptyList()
        return parsePlayerjsJson(json).map { track ->
            track.copy(url = track.url.withRefererRef("knigaaudio.com"))
        }
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.th-item").mapNotNull { item ->
            val link = item.selectFirst("a.th-in[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val fullTitle = link.text().trim()
            if (fullTitle.isBlank()) return@mapNotNull null
            val parsed = parseTitleAuthor(fullTitle)
            val cover = item.selectFirst("div.th-img img[src]")?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
            val tip = item.selectFirst("div.th-tip")?.text().orEmpty()
            val author = metaValue(tip, "Автор:") ?: parsed.second
            val genre = metaValue(tip, "Жанр:")
            Book(
                sourceId = id,
                id = href,
                title = parsed.first,
                url = href,
                coverUrl = cover,
                author = author,
                genre = genre,
            )
        }
    }

    private companion object {
        val META_KEYS = listOf("Автор:", "Жанр:", "Серия:")

        /** «Название - Автор» → пара (Название, Автор). */
        fun parseTitleAuthor(fullTitle: String): Pair<String, String?> {
            val author = fullTitle.substringBefore(" - ").trim()
                .takeIf { it.isNotBlank() && it != fullTitle }
            val title = if (author != null) {
                fullTitle.substringAfter(" - ").trim().ifBlank { fullTitle }
            } else {
                fullTitle
            }
            return title to author
        }

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