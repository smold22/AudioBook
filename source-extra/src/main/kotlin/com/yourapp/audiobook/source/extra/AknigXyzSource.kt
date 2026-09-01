package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Источник «Акниг»: https://s2.aknig.xyz
 * DataLife Engine + плеер Playerjs. Плейлист .txt (JSON) требует заголовок Referer
 * со страницы книги; сами MP3 лежат на archive.org и реферер не требуют.
 */
class AknigXyzSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "aknigxyz"
    override val name = "Акниг (s2)"
    override val baseUrl = "https://s2.aknig.xyz"

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
        val title = doc.selectFirst("div.full-news-title h1")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("div.full-news-image a.highslide img[src], div.full-news-image img[src]")
            ?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
        val genre = doc.select("div.full-news-category a").eachText().joinToString(", ").toNullIfBlank()
        val duration = doc.selectFirst("div.main-news-stat-r")
            ?.text()?.trim()?.removePrefix("Длительность:")?.trim()?.toNullIfBlank()
        val author = doc.selectFirst("div.full-news-author")?.text()?.trim()?.removePrefix("Автор:")
            ?.trim()?.toNullIfBlank()
        val reader = doc.selectFirst("div.full-news-wread")?.text()?.trim()?.removePrefix("Читает:")
            ?.trim()?.toNullIfBlank()
        val description = doc.selectFirst("div.full-news-text.obr")?.text()?.trim().toNullIfBlank()

        val tracks = fetchTracks(html, url)

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
        doc.select("a[href]").forEach { link ->
            val href = link.absUrl("href")
            val path = href.removePrefix("$baseUrl/")
            if (path.isNotBlank() && path.count { it == '/' } == 1 &&
                !path.startsWith("page") && !path.startsWith("index") && seen.add(href)
            ) {
                val name = link.text().trim()
                if (name.isNotBlank() && name.length <= 50) result += Genre(name = name, url = href)
            }
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, cardSelector = "div.main-news.ajax-news")

    private suspend fun fetchTracks(html: String, bookUrl: String): List<com.yourapp.audiobook.source.api.AudioTrack> {
        val playlist = playerjsPlaylistUrl(html) ?: return emptyList()
        val playlistUrl = if (playlist.startsWith("http")) playlist else baseUrl + playlist
        val json = runCatching { getHtml(client, playlistUrl, referer = bookUrl) }.getOrNull() ?: return emptyList()
        return parsePlayerjsJson(json)
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.main-news.ajax-news").mapNotNull { item ->
            val link = item.selectFirst("div.main-news-title a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = item.selectFirst("div.main-news-l a.main-news-image img[src]")
                ?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
            val info = item.selectFirst("div.main-news-info")?.text().orEmpty()
            val author = metaValue(info, "Автор:")
            val reader = metaValue(info, "Читает:")
            val genre = metaValue(info, "Жанр:")
            val duration = item.selectFirst("div.main-news-stat-r")
                ?.text()?.trim()?.removePrefix("Длительность:")?.trim()?.toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                reader = reader,
                durationText = duration,
                genre = genre,
            )
        }
    }

    private companion object {
        val META_KEYS = listOf("Автор:", "Читает:", "Жанр:", "Длительность:", "Год:")

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