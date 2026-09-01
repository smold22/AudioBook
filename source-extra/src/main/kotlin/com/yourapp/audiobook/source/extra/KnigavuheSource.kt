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

class KnigaVuheSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "knigavuhe"
    override val name = "Книга в ухе"
    override val baseUrl = "https://knigavuhe.org"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, "${baseUrl}/new/?page=$page"))

    override suspend fun search(query: String, page: Int): List<Book> =
        parseBooks(getHtml(client, "${baseUrl}/search/?q=${URLEncoder.encode(query, "UTF-8")}&page=$page"))

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst(".book_title_elem.book_title_name")?.text()?.trim() ?: ""
        val cover = doc.selectFirst(".book_cover img")?.attr("src")?.substringBefore("?")?.toNullIfBlank()
        val author = doc.select("[itemprop=author] a").eachText().joinToString(", ").toNullIfBlank()
        val artist = doc.select(".book_title_elem").firstOrNull { el ->
            el.text().contains("читает") && el.selectFirst("a") != null
        }?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()
        val description = doc.selectFirst("[itemprop=description]")?.text().toNullIfBlank()

        val seriesTitle = doc.selectFirst(".book_serie_block_title a")?.text().toNullIfBlank()
        val seriesUrl = doc.selectFirst(".book_serie_block_title a")?.absUrl("href").toNullIfBlank()
        val seriesIndex = doc.select(".book_serie_block_item").firstOrNull { item ->
            item.selectFirst("a")?.absUrl("href") == url
        }?.selectFirst(".book_serie_block_item_index")?.text()
            ?.filter(Char::isDigit)?.toIntOrNull()

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover,
            author = author,
            reader = artist,
            genre = doc.selectFirst(".book_genre_pretitle a")?.text().toNullIfBlank(),
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        val related = doc.select("a.suggested_book").mapNotNull { card ->
            val href = card.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title2 = card.selectFirst(".suggested_book_name")?.text()?.trim()
                ?: return@mapNotNull null
            val cover2 = card.selectFirst(".suggested_book_cover_img")?.attr("src").toNullIfBlank()
            val author2 = card.selectFirst(".suggested_book_author_href")?.text().toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title2,
                url = href,
                coverUrl = cover2,
                author = author2,
            )
        }
        val seriesBooks = doc.select(".book_serie_block_item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val idx = item.selectFirst(".book_serie_block_item_index")?.text()
                ?.filter(Char::isDigit)?.toIntOrNull()
            Book(
                sourceId = id,
                id = href,
                title = link.text().trim(),
                url = href,
                coverUrl = item.selectFirst("img")?.attr("src")?.substringBefore("?")?.toNullIfBlank(),
                seriesTitle = seriesTitle,
                seriesIndex = idx,
                seriesUrl = seriesUrl,
            )
        }
        return BookDetails(
            book = book,
            description = description,
            tracks = parsePlayerTracks(html),
            related = related,
            seriesBooks = seriesBooks,
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            when {
                url.contains("/genre/") -> url.trimEnd('/') + "/$page/"
                url.contains("?") -> "$url&page=$page"
                else -> "$url?page=$page"
            }
        }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres/"), baseUrl)
        return doc.select(".genre2_item").mapNotNull { item ->
            val link = item.selectFirst(".genre2_item_name") ?: return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/genre/")) return@mapNotNull null
            val count = item.selectFirst(".genre2_item_books_count")?.text()
                ?.filter(Char::isDigit)?.toLongOrNull()
            Genre(name = name, url = href, bookCount = count)
        }
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        val container = doc.selectFirst("#books_updates_list, #books_list") ?: return emptyList()
        return container.select(".bookkitem").mapNotNull { item ->
            if (item.selectFirst(".bookkitem_litres_icon") != null) return@mapNotNull null
            val nameEl = item.selectFirst(".bookkitem_name")
            val link = nameEl?.child(0) ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() }
                ?: (baseUrl + if (link.attr("href").startsWith("/")) link.attr("href") else "/" + link.attr("href"))
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = item.selectFirst("img")?.attr("src")?.substringBefore("?")?.toNullIfBlank()
            val author = item.select(".bookkitem_author a").eachText().joinToString(", ").toNullIfBlank()
            val reader = item.select(".bookkitem_meta_block a[href*=/reader/]")
                .eachText().joinToString(", ").toNullIfBlank()
            val genre = item.selectFirst(".bookkitem_genre a")?.text().toNullIfBlank()
            val time = item.selectFirst(".bookkitem_meta_time")?.text().toNullIfBlank()
            val seriesLink = item.selectFirst(".bookkitem_meta_block a[href*=/series/]")
            val seriesIndex = item.selectFirst(".bookkitem_serie_index")?.text()
                ?.filter(Char::isDigit)?.toIntOrNull()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                reader = reader,
                durationText = time,
                genre = genre,
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesIndex = seriesIndex,
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else seriesUrl.trimEnd('/') + "/$page/"
        return parseBooks(getHtml(client, target))
    }

    private fun parsePlayerTracks(html: String): List<AudioTrack> {
        for (script in Jsoup.parse(html).select("script")) {
            val raw = script.toString()
            if (!raw.contains("new BookPlayer")) continue
            val start = raw.indexOf("new BookPlayer")
            if (start < 0) continue
            val jsonStart = raw.indexOf('[', start)
            if (jsonStart < 0) continue
            val jsonEnd = findJsonEnd(raw, jsonStart)
            if (jsonEnd < 0) continue
            return parseTrackArray(raw.substring(jsonStart, jsonEnd + 1))
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

    private fun parseTrackArray(json: String): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
        try {
            val array = JsonParser.parseString(json).asJsonArray
            for (element in array) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                val title = obj.get("title")?.asString ?: continue
                val url = obj.get("url")?.asString ?: continue
                result += AudioTrack(
                    title = title,
                    url = url,
                    durationSeconds = obj.get("duration")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt,
                )
            }
        } catch (_: Exception) {
        }
        return result
    }
}