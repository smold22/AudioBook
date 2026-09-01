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

class KnigobludSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "knigoblud"
    override val name = "Книгоблуд"
    override val baseUrl = "https://www.knigoblud.club"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/$page"))

    override suspend fun search(query: String, page: Int): List<Book> =
        parseBooks(getHtml(client, "$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}&page=$page"))

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val cover = doc.getElementById("BookCoverImage")?.attr("src").toNullIfBlank()
        val description = doc.selectFirst(".BookDescriptionContent")?.ownText().toNullIfBlank()
        val durationText = doc.selectFirst(".PageTitle_Subtitle")?.ownText()?.toNullIfBlank()

        var author: String? = null
        var reader: String? = null
        var genre: String? = null
        doc.select(".BookMetaBlock .BookMetaBlockLine").forEach { info ->
            val text = info.text()
            when {
                text.contains("✍") -> author = info.select("a").eachText().joinToString(", ").toNullIfBlank()
                text.contains("\uD83C\uDF99") -> reader = info.select("a").eachText().joinToString(", ").toNullIfBlank()
                text.contains("\uD83D\uDCD5") -> genre = info.select("a").eachText().joinToString(", ").toNullIfBlank()
            }
        }

        val seriesLabel = doc.selectFirst(".BookDescriptionSeries .BookDescriptionLabel a, .BookDescriptionLabel a")
            ?.text()?.toNullIfBlank()
            ?: doc.selectFirst(".BookDescriptionLabel")?.text().toNullIfBlank()
        val seriesItems = doc.select(".BookDescriptionSeriesItem").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Book(
                sourceId = id,
                id = href,
                title = item.selectFirst(".BookDescriptionSeriesItemName")?.text()?.trim()
                    ?: link.text().trim(),
                url = href,
                seriesTitle = seriesLabel,
                seriesIndex = item.selectFirst(".BookDescriptionSeriesItemIndex")?.text()
                    ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 },
            )
        }

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
            seriesTitle = seriesLabel,
        )
        return BookDetails(
            book = book,
            description = description,
            tracks = parseTracks(html, title),
            seriesBooks = seriesItems,
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (url.contains("?")) url.replace(Regex("""page=\d+""")) { "page=$page" } else "$url/$page"
        }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        val urls = listOf(
            "$baseUrl/",
            "$baseUrl/trending/",
            "$baseUrl/finished/",
            "$baseUrl/popular/?period=week",
            "$baseUrl/popular/?period=month",
        )
        for (url in urls) {
            val doc = Jsoup.parse(getHtml(client, url), baseUrl)
            doc.select(".bookListItemGenreLink").forEach { link ->
                val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
                val href = link.absUrl("href").ifBlank { return@forEach }
                if (seen.add(href)) result += Genre(name = name, url = href)
            }
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? {
        val doc = Jsoup.parse(getHtml(client, url), url)
        val count = doc.select(".bookListItem").size
        return if (count > 0) count.toLong() else null
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("#BL .bookListItem").mapNotNull { item ->
            val cover = item.selectFirst(".bookListItemCover")
            val link = cover ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = item.selectFirst(".bookListItemCoverNameText")?.text()?.trim()
                ?: link.text().trim()
            val author = item.select(".bookListItemMetaBlock a")
                .filter { it.previousElementSibling()?.text()?.contains("✍") == true }
                .joinToString(", ") { it.text() }.toNullIfBlank()
            val reader = item.select(".bookListItemMetaBlock a")
                .filter { it.previousElementSibling()?.text()?.contains("\uD83C\uDF99") == true }
                .joinToString(", ") { it.text() }.toNullIfBlank()
            val genre = item.select(".bookListItemMetaBlock a")
                .filter { it.previousElementSibling()?.text()?.contains("\uD83D\uDCD5") == true }
                .joinToString(", ") { it.text() }.toNullIfBlank()
            val duration = item.selectFirst(".bookListItemNameDur")?.text().toNullIfBlank()
            val coverUrl = (item.selectFirst(".bookListItemCoverImg, .bookListItemCover img")?.attr("data-img")
                ?: item.selectFirst(".bookListItemCoverImg, .bookListItemCover img")?.attr("src")).toNullIfBlank()
            if (title.isBlank()) return@mapNotNull null
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = coverUrl,
                author = author,
                reader = reader,
                durationText = duration,
                genre = genre,
            )
        }
    }

    private fun parseTracks(html: String, bookName: String): List<AudioTrack> {
        for (script in Jsoup.parse(html).select("script")) {
            val raw = script.toString()
            if (!raw.contains("KB.playerInit")) continue
            val start = raw.indexOf("KB.playerInit(")
            if (start < 0) continue
            val jsonStart = raw.indexOf('{', start)
            if (jsonStart < 0) continue
            val jsonEnd = findJsonEnd(raw, jsonStart)
            if (jsonEnd < 0) continue
            return try {
                val obj = JsonParser.parseString(raw.substring(jsonStart, jsonEnd + 1)).asJsonObject
                val playlist = obj.getAsJsonArray("playlist")
                playlist.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val o = el.asJsonObject
                    val title = o.get("title")?.asString ?: bookName
                    val src = o.get("src")?.asString ?: return@mapNotNull null
                    AudioTrack(
                        title = title,
                        url = src,
                        durationSeconds = o.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt,
                    )
                }
            } catch (_: Exception) {
                continue
            }
        }
        return emptyList()
    }

    private fun findJsonEnd(raw: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }
}