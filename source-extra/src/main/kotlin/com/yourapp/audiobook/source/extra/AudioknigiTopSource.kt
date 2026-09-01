package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class AudioknigiTopSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audioknigi_top"
    override val name = "Audioknigi.Top"
    override val baseUrl = "https://audioknigi.top"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"))

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
        val title = doc.selectFirst("h1.section__title")?.text()?.trim() ?: ""
        val cover = doc.selectFirst(".card--details .card__cover img")
            ?.attr("data-src")?.ifBlank { doc.selectFirst(".card--details .card__cover img")?.attr("src") }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val author = metaValue(doc, "Автор")
        val reader = metaValue(doc, "Исполнитель")
        val genre = metaValue(doc, "Жанр")
        val duration = metaValue(doc, "Длительность")
        val description = doc.selectFirst(".card__description")?.text().toNullIfBlank()
        val seriesLink = doc.selectFirst("a[href*=/cikl/]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover.toNullIfBlank(),
            author = author,
            reader = reader,
            durationText = duration,
            genre = genre,
            seriesTitle = seriesTitle,
            seriesUrl = seriesUrl,
        )
        return BookDetails(
            book = book,
            description = description,
            tracks = parseTracks(html, title),
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (Regex("""/page/\d+""").containsMatchIn(url)) {
                url.replace(Regex("""/page/\d+""")) { "/page/$page" }
            } else {
                url.trimEnd('/') + "/page/$page/"
            }
        }
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> =
        books(seriesUrl, page)

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/"), baseUrl)
        val seen = mutableSetOf<String>()
        return doc.select(".header__dropdown-menu a[href*=/zhanr/]").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/zhanr/")) return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null
            Genre(name = name, url = href)
        }
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.short").mapNotNull { item ->
            val link = item.selectFirst("a.name-kniga") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val name = link.text().trim()
            if (name.isBlank()) return@mapNotNull null
            val img = item.selectFirst(".short-img img")
            val cover = img?.attr("data-src")?.ifBlank { img.attr("src") }
                ?.let { if (it.startsWith("http")) it else baseUrl + it }
            val author = infoValue(item, "author")
            val reader = infoValue(item, "reader")
            val duration = item.selectFirst(".short-info .time")?.text()?.trim().toNullIfBlank()
            val seriesLink = item.selectFirst(".short-info .cikl a[href*=/cikl/]")
            Book(
                sourceId = id,
                id = href,
                title = name,
                url = href,
                coverUrl = cover.toNullIfBlank(),
                author = author,
                reader = reader,
                durationText = duration,
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    private fun infoValue(item: org.jsoup.nodes.Element, cssClass: String): String? =
        item.selectFirst(".short-info .info-kniga .$cssClass a")?.text()?.trim().toNullIfBlank()

    private fun metaValue(doc: org.jsoup.nodes.Document, label: String): String? {
        val li = doc.select(".card--details .card__meta li").firstOrNull { item ->
            (item.selectFirst("span")?.text() ?: "").startsWith(label)
        } ?: return null
        return li.select("a").eachText().joinToString(", ").toNullIfBlank()
            ?: li.ownText()?.trim().toNullIfBlank()
    }

    private fun parseTracks(html: String, bookName: String): List<AudioTrack> {
        val doc = Jsoup.parse(html)
        val raw = doc.selectFirst("textarea.ak-top-player__raw")?.text()
        if (!raw.isNullOrBlank()) {
            val result = mutableListOf<AudioTrack>()
            val pattern = Regex("""(?s)"title":"(.*?)","file":"(.*?)"""")
            for (m in pattern.findAll(raw)) {
                val title = m.groupValues[1].ifBlank { bookName }
                result += AudioTrack(title = title, url = m.groupValues[2])
            }
            if (result.isNotEmpty()) return result
        }
        return doc.select("script").firstOrNull { it.data().contains("\"audio\"") }
            ?.let { parseJsonLdAudio(it.data(), bookName) }
            ?: emptyList()
    }

    private fun parseJsonLdAudio(scriptData: String, bookName: String): List<AudioTrack> {
        val audioKey = scriptData.indexOf("\"audio\":")
        if (audioKey < 0) return emptyList()
        val arrayStart = scriptData.indexOf('[', audioKey)
        val arrayEnd = findArrayEnd(scriptData, arrayStart)
        if (arrayStart < 0 || arrayEnd < 0) return emptyList()
        return try {
            val array = JsonParser.parseString(scriptData.substring(arrayStart, arrayEnd + 1)).asJsonArray
            array.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val url = o.get("contentUrl")?.asString ?: return@mapNotNull null
                AudioTrack(title = o.get("name")?.asString ?: bookName, url = url)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findArrayEnd(raw: String, start: Int): Int {
        if (start < 0) return -1
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
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }
}
