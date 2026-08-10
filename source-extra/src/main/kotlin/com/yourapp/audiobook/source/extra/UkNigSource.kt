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

class UkNigSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "uknig"
    override val name = "уКниг"
    override val baseUrl = "https://uknig.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/?p=$page"))

    override suspend fun search(query: String, page: Int): List<Book> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return parseBooks(getHtml(client, if (page <= 1) "$baseUrl/?q=$encoded" else "$baseUrl/?q=$encoded&p=$page"))
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst(".book-details h1")?.text()?.trim() ?: ""
        val cover = doc.selectFirst(".book-details .cover img")?.attr("src").toNullIfBlank()
        val author = doc.selectFirst(".book-main-info .book-author a")?.text().toNullIfBlank()
        val reader = doc.selectFirst(".book-main-info .book-reader a")?.text().toNullIfBlank()
        val description = doc.selectFirst(".book-details .description")?.text().toNullIfBlank()
        val genre = doc.select(".book-main-info a[href*=/genres/]").firstOrNull()?.text().toNullIfBlank()
        val duration = doc.selectFirst(".book-main-info .book-duration")?.ownText()?.trim().toNullIfBlank()
        val seriesRow = doc.selectFirst(".book-main-info .book-series, .book-details .book-series")
        val seriesLink = seriesRow?.selectFirst("a[href*=/series/]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = seriesRow?.ownText()?.substringAfter("#", "")?.trim()
            ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
            ?: seriesRow?.text()?.substringAfter("(#", "")?.substringBefore(")")?.toIntOrNull()
                ?.takeIf { it > 0 }

        val book = Book(
            sourceId = id,
            id = Regex("""/books/(\d+)""").find(url)?.groupValues?.get(1) ?: url,
            title = title,
            url = url,
            coverUrl = cover,
            author = author,
            reader = reader,
            durationText = duration,
            genre = genre,
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        val related = runCatching {
            val bookId = Regex("""/books/(\d+)""").find(url)?.groupValues?.get(1).orEmpty()
            if (bookId.isBlank()) emptyList() else parseCarousel(getHtml(client, "$baseUrl/api/books/$bookId/authorbooks"))
        }.getOrDefault(emptyList())
        return BookDetails(
            book = book,
            description = description,
            tracks = fetchTracks(html),
            related = related,
        )
    }

    private fun parseCarousel(json: String): List<Book> {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return emptyList()
        val books = root.get("books")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return books.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val bookUrl = o.get("url")?.asString ?: return@mapNotNull null
            val title = o.get("title")?.asString ?: return@mapNotNull null
            val author = o.get("author")?.asString.toNullIfBlank()
            val reader = o.get("reader")?.asString.toNullIfBlank()
            val cover = o.get("thumb_url")?.asString.toNullIfBlank()
            Book(
                sourceId = id,
                id = Regex("""/books/(\d+)""").find(bookUrl)?.groupValues?.get(1) ?: bookUrl,
                title = title,
                url = bookUrl,
                coverUrl = cover,
                author = author,
                reader = reader,
            )
        }
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres"), baseUrl)
        return doc.select(".genre").mapNotNull { item ->
            val link = item.selectFirst("a.heading") ?: return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/genres/")) return@mapNotNull null
            val count = item.selectFirst(".books-count")?.text()
                ?.filter(Char::isDigit)?.toLongOrNull()
            Genre(name = name, url = href, bookCount = count)
        }
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (url.contains("?")) url.replace(Regex("""p=\d+"""), "p=$page") else "$url?p=$page"
        }
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else {
            if (seriesUrl.contains("?")) seriesUrl.replace(Regex("""p=\d+"""), "p=$page") else "$seriesUrl?p=$page"
        }
        return parseBooks(getHtml(client, target))
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".book-item").mapNotNull { item ->
            val titleEl = item.selectFirst(".book-title") ?: return@mapNotNull null
            val href = titleEl.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val author = item.selectFirst(".book-author a")?.text().toNullIfBlank()
            val reader = item.selectFirst(".book-reader a")?.text().toNullIfBlank()
            val duration = item.selectFirst(".book-duration")?.ownText()?.trim().toNullIfBlank()
            val genre = item.select("a[href*=/genres/]").firstOrNull()?.text().toNullIfBlank()
            Book(
                sourceId = id,
                id = Regex("""/books/(\d+)""").find(href)?.groupValues?.get(1) ?: href,
                title = title,
                url = href,
                coverUrl = item.selectFirst("img.cover")?.attr("data-original").toNullIfBlank(),
                author = author,
                reader = reader,
                durationText = duration,
                genre = genre,
                seriesIndex = item.selectFirst(".number-in-series")?.text()
                    ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
    }

    private suspend fun fetchTracks(html: String): List<AudioTrack> {
        val match = Regex("""createPlayer\(["']([^"']*playlist\.txt[^"']*)["']\)""").find(html)
            ?: return emptyList()
        val playlistUrl = match.groupValues[1].replace("&amp;", "&")
        val json = try {
            getHtml(client, playlistUrl)
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            val array = JsonParser.parseString(json).asJsonArray
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val trackTitle = obj.get("title")?.asString ?: return@mapNotNull null
                val file = obj.get("file")?.asString ?: return@mapNotNull null
                AudioTrack(title = trackTitle, url = file)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
