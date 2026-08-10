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

class BookZvukSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "book_zvuk"
    override val name = "Бук-звук"
    override val baseUrl = "https://book-zvuk.ru"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/?page=$page"))

    override suspend fun search(query: String, page: Int): List<Book> =
        parseSearchResults(getHtml(client, "$baseUrl/search?text=${URLEncoder.encode(query, "UTF-8")}&page=$page"))

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val rawTitle = doc.selectFirst("h1.b-maintitle")?.text()?.trim() ?: ""
        val author = doc.selectFirst("a[href*=/author/]")?.text().toNullIfBlank()
        val title = author?.let { stripAuthor(rawTitle, it) } ?: rawTitle
        val reader = doc.selectFirst("a[href*=/performer/]")?.text().toNullIfBlank()
        val genre = doc.selectFirst("a[href*=/section/]")?.text().toNullIfBlank()
        val cover = doc.selectFirst(".abook-left > img")?.attr("src").toNullIfBlank()
        val description = doc.selectFirst(".descriptiontext")?.text().toNullIfBlank()

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
        return BookDetails(book = book, description = description, tracks = parseTracks(html, url, title))
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (url.contains("?")) url.replace(Regex("""[?&]page=\d+"""), "&page=$page") else "$url/?page=$page"
        }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/sections"), baseUrl)
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        doc.select("a[href*=/section/]").forEach { link ->
            val href = link.absUrl("href").ifBlank { return@forEach }
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
            if (seen.add(href)) result += Genre(name = name, url = href)
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, ".abook-item")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".abook-item").mapNotNull { item ->
            val link = item.selectFirst(".abook-title a") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val rawTitle = link.text().trim()
            val author = item.selectFirst(".a-info-item a[href*=/author/]")?.text().toNullIfBlank()
            val title = author?.let { stripAuthor(rawTitle, it) } ?: rawTitle
            val reader = item.selectFirst(".a-info-item a[href*=/site/performer]")?.text().toNullIfBlank()
            val genre = item.selectFirst(".book_snippet_genre1")?.text().toNullIfBlank()
            val cover = item.selectFirst(".b-showshort__cover_image")?.attr("src").toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                reader = reader,
                genre = genre,
            )
        }
    }

    private fun parseSearchResults(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".b-statictop__items_item .cell.title a").mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
            )
        }
    }

    private suspend fun parseTracks(html: String, bookUrl: String, bookName: String): List<AudioTrack> {
        val doc = Jsoup.parse(html, bookUrl)
        for (script in doc.select("script")) {
            val raw = script.toString()
            val idx = raw.indexOf("new Playerjs")
            if (idx < 0) continue
            val fileMatch = Regex("""file:\s*"([^"]+)"""").find(raw.substring(idx)) ?: continue
            val playlistUrl = fileMatch.groupValues[1]
            if (playlistUrl.isBlank()) continue
            return try {
                val body = getHtml(client, playlistUrl, referer = "https://book-zvuk.com/")
                val array = JsonParser.parseString(body).asJsonArray
                array.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val obj = el.asJsonObject
                    val file = obj.get("file")?.asString ?: return@mapNotNull null
                    val title = obj.get("title")?.asString ?: bookName
                    val start = obj.get("start")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    val end = obj.get("end")?.takeIf { it.isJsonPrimitive }?.asInt
                    AudioTrack(
                        title = title,
                        url = file,
                        durationSeconds = end?.let { (it - start).coerceAtLeast(0) },
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }

    private fun stripAuthor(name: String, author: String): String =
        name.removePrefix("$author - ").removePrefix("$author -").trim()
}
