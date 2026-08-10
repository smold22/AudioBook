package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class BookishSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "bookish"
    override val name = "Bookish"
    override val baseUrl = "https://bookish.site"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/page/$page/"))

    override suspend fun search(query: String, page: Int): List<Book> {
        val html = postForm(
            client = client,
            url = "$baseUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "search_start" to page.toString(),
                "full_search" to "0",
                "story" to query,
            ),
            referer = baseUrl,
        )
        return parseBooks(html)
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val h1 = doc.selectFirst("h1.short-title")?.text()?.trim() ?: ""
        val author = liValue(doc, "Автор")
        val title = author?.let { h1.removeSuffix(" - $it").trim() }?.takeIf { it.isNotBlank() } ?: h1
        val reader = liValue(doc, "Читает")
        val duration = liValue(doc, "Время")
        val description = doc.selectFirst(".ftext.full-text")?.text().toNullIfBlank()
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".short-img img")?.attr("src")
                ?.let { if (it.startsWith("http")) it else baseUrl + it }
        val coverUrl = cover.toNullIfBlank()
        val cycleLi = doc.select(".short-list li").firstOrNull { it.text().contains("Цикл") }
        val seriesLink = cycleLi?.selectFirst("a")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = cycleLi?.ownText()?.substringAfter("№", "")?.trim()
            ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
            ?: cycleLi?.text()?.substringAfter("№", "")?.trim()
                ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = coverUrl,
            author = author,
            reader = reader,
            durationText = duration,
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        return BookDetails(book = book, description = description, tracks = parseTracks(html, title))
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else url.replace(Regex("""/page/\d+"""), "/page/$page")
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else "$seriesUrl?cstart=${(page - 1) * 20}"
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        return doc.select("ul.nav-menu a").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/")) return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = href)
        }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "div.short-item")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".short-item").mapNotNull { item ->
            val link = item.selectFirst("a.short-title") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val rawTitle = link.text().trim()
            val author = item.select(".short-list li").firstOrNull { it.text().contains("Автор") }
                ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()
            val title = author?.let { rawTitle.removeSuffix(" - $it").trim() }
                ?.takeIf { it.isNotBlank() } ?: rawTitle
            val reader = item.select(".short-list li").firstOrNull { it.text().contains("Читает") }
                ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()
            val duration = item.select(".short-list li").firstOrNull { it.text().contains("Время") }
                ?.ownText()?.trim().toNullIfBlank()
            val cover = item.selectFirst(".short-img img")?.attr("src")
                ?.let { if (it.startsWith("http")) it else baseUrl + it }.toNullIfBlank()
            val cycleLi = item.select(".short-list li").firstOrNull { it.text().contains("Цикл") }
            val seriesLink = cycleLi?.selectFirst("a")
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                reader = reader,
                durationText = duration,
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesIndex = cycleLi?.text()?.substringAfter("№", "")?.trim()
                    ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 },
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    private fun liValue(doc: org.jsoup.nodes.Document, label: String): String? =
        doc.select(".short-list li").firstOrNull { it.text().contains(label) }
            ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()

    private fun parseTracks(html: String, bookName: String): List<AudioTrack> {
        for (script in Jsoup.parse(html).select("script")) {
            val raw = script.toString()
            val playlistStart = raw.indexOf("var playlist = [")
            if (playlistStart < 0) continue
            val arrStart = raw.indexOf('[', playlistStart)
            val arrEnd = findArrayEnd(raw, arrStart)
            if (arrStart < 0 || arrEnd < 0) continue
            return try {
                val array = JsonParser.parseString(raw.substring(arrStart, arrEnd + 1)).asJsonArray
                array.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val obj = el.asJsonObject
                    val title = obj.get("title")?.asString ?: bookName
                    val file = obj.get("file")?.asString ?: return@mapNotNull null
                    AudioTrack(title = title, url = file)
                }
            } catch (_: Exception) {
                continue
            }
        }
        return emptyList()
    }

    private fun findArrayEnd(raw: String, start: Int): Int {
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
