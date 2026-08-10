package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

class PoleknigSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "poleknig"
    override val name = "Полекниг"
    override val baseUrl = "https://poleknig.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, "$baseUrl/?p=$page"))

    override suspend fun search(query: String, page: Int): List<Book> =
        parseBooks(getHtml(client, "$baseUrl/?q=${URLEncoder.encode(query, "UTF-8")}&p=$page"))

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("h1[itemprop=name], h1")?.text()?.trim() ?: ""
        val cover = doc.selectFirst("img[src*=/covers/]")?.attr("src").toNullIfBlank()
        val author = doc.selectFirst("h2.book-author a")?.text().toNullIfBlank()
        val reader = doc.selectFirst(".book-reader a, .book-reader > div > a")?.text().toNullIfBlank()
        val genre = doc.selectFirst(".book-genres a, .book-genres > div > a")?.text().toNullIfBlank()
        val durationText = doc.selectFirst(".book-duration")?.text().toNullIfBlank()
        val description = doc.selectFirst("#description[itemprop=description], .book-description")
            ?.text().toNullIfBlank()
        val seriesRow = doc.selectFirst(".book-main-info .book-series, .book-details .book-series")
        val seriesLink = seriesRow?.selectFirst("a[href*=/series/]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = seriesRow?.text()?.substringAfter("(#", "")?.substringBefore(")")?.toIntOrNull()
            ?.takeIf { it > 0 }

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover,
            author = author,
            reader = reader,
            durationText = durationText,
            genre = genre,
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        return BookDetails(book = book, description = description, tracks = parseTracks(html, title))
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            val updated = url.replace(Regex("""[?&]p=\d+"""), "&p=$page")
            if (updated == url) "$url?p=$page" else updated
        }
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else {
            val updated = seriesUrl.replace(Regex("""[?&]p=\d+"""), "&p=$page")
            if (updated == seriesUrl) "$seriesUrl?p=$page" else updated
        }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres"), baseUrl)
        return doc.select(".genre a.heading").mapNotNull { link ->
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            Genre(name = name, url = href)
        }
    }

    override suspend fun genreBookCount(url: String): Long? {
        val doc = Jsoup.parse(getHtml(client, url), url)
        val count = doc.select("div.book-item").size
        return if (count > 0) count.toLong() else null
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".row.grid div.book-item, div.book-item").mapNotNull { item ->
            val link = item.selectFirst("a.book-title") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim()
            val author = item.selectFirst(".book-author a")?.text().toNullIfBlank()
            val reader = item.selectFirst(".book-reader a")?.text().toNullIfBlank()
            val genre = item.selectFirst(".book-genres a")?.text().toNullIfBlank()
            val duration = item.selectFirst(".book-duration")?.text().toNullIfBlank()
            val cover = (item.selectFirst("img.cover")?.attr("data-original")
                ?: item.selectFirst("img.cover")?.attr("src")).toNullIfBlank()
            if (title.isBlank()) return@mapNotNull null
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
                seriesIndex = item.selectFirst(".number-in-series")?.text()
                    ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
    }

    private suspend fun parseTracks(html: String, bookName: String): List<AudioTrack> {
        for (script in Jsoup.parse(html).select("script")) {
            val raw = script.html()
            if (!raw.contains("Playerjs")) continue
            var fileKey = raw.indexOf("\"file\"")
            if (fileKey < 0) fileKey = raw.indexOf("'file'")
            if (fileKey < 0) fileKey = raw.indexOf("file:")
            if (fileKey < 0) continue
            val keyEnd = if (raw[fileKey] == 'f') fileKey + 5 else fileKey + 6
            val startQuote = raw.indexOf('"', keyEnd)
            val startApostrophe = raw.indexOf('\'', keyEnd)
            val start = when {
                startQuote <= 0 && startApostrophe > 0 -> startApostrophe + 1
                startQuote > 0 && (startApostrophe <= 0 || startQuote < startApostrophe) -> startQuote + 1
                else -> continue
            }
            val end = raw.indexOf('"', start).takeIf { it > start }
                ?: raw.indexOf('\'', start)
            if (end <= start) continue
            var playlistUrl = raw.substring(start, end)
            if (!playlistUrl.startsWith("http")) playlistUrl = baseUrl + playlistUrl
return try {
                val json = getHtml(client, playlistUrl)
                val array = JsonParser.parseString(json).asJsonArray
                array.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val o = el.asJsonObject
                    val title = o.get("title")?.asString ?: bookName
                    val file = o.get("file")?.asString ?: return@mapNotNull null
                    AudioTrack(title = title, url = file)
                }
            } catch (_: Exception) {
                continue
            }
        }
        return emptyList()
    }
}