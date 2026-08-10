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
import java.net.URLEncoder

class OtrubSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "otrub"
    override val name = "Отруб"
    override val baseUrl = "https://otrub.in"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/?p=$page"))

    override suspend fun search(query: String, page: Int): List<Book> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/search?q=$encoded" else "$baseUrl/search?q=$encoded&p=$page"
        return parseBooks(getHtml(client, url))
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("[itemprop=name]")?.text()?.trim() ?: ""
        val cover = doc.selectFirst("[itemprop=image], ._5e0b77 img")?.attr("src").toNullIfBlank()
        val description = doc.selectFirst("[itemprop=description]")?.text().toNullIfBlank()
        val genre = doc.select(".d982d6 ._5bef20").firstOrNull { it.text().startsWith("Жанр") }
            ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()
        val author = doc.select("[itemprop=author] a").eachText().joinToString(", ").toNullIfBlank()
        val reader = doc.select(".d982d6 ._5bef20").firstOrNull { it.text().startsWith("Читает") }
            ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()
        val duration = doc.select(".d982d6 ._5bef20").firstOrNull { it.text().startsWith("Продолжительность") }
            ?.ownText()?.trim().toNullIfBlank()

        val seriesHeader = doc.selectFirst("._49ba4c._065892")
        val seriesLink = seriesHeader?.selectFirst("a[href*=/series/]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = doc.select("._f61db9").firstOrNull { item ->
            item.selectFirst("a[href]")?.absUrl("href") == url ||
                item.selectFirst("strong")?.text()?.contains(title) == true
        }?.selectFirst("._bb8bca")?.text()?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
        val seriesBooks = doc.select("._f61db9").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Book(
                sourceId = id,
                id = href,
                title = link.text().trim(),
                url = href,
                seriesTitle = seriesTitle,
                seriesIndex = item.selectFirst("._bb8bca")?.text()?.filter(Char::isDigit)?.toIntOrNull()
                    ?.takeIf { it > 0 },
                seriesUrl = seriesUrl,
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
            durationText = duration,
            genre = genre,
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        return BookDetails(
            book = book,
            description = description,
            tracks = parseTracks(html),
            seriesBooks = seriesBooks,
        )
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
            if (seriesUrl.contains("?")) seriesUrl.replace(Regex("""p=\d+"""), "p=$page")
            else "$seriesUrl?p=$page"
        }
        val html = getHtml(client, target)
        val title = Jsoup.parse(html, target).selectFirst("h1")?.text()
            ?.substringAfter("«", "")?.substringBefore("»")?.toNullIfBlank()
        return parseBooks(html).map { it.copy(seriesTitle = title ?: it.seriesTitle, seriesUrl = seriesUrl) }
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres.html"), baseUrl)
        return doc.select("a[href*=/genres/]").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/genres/") || href.contains("?")) return@mapNotNull null
            val name = link.selectFirst("._28f7ab")?.text()?.trim()
                ?: link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val count = link.selectFirst("._fb36fb")?.text()?.filter(Char::isDigit)?.toLongOrNull()
            Genre(name = name, url = href, bookCount = count)
        }.distinctBy { it.url }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "._dad4fa")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("#books_updates_list ._dad4fa, #books_list ._dad4fa").mapNotNull { item ->
            val link = item.selectFirst("._3dc935 a, a[href*=.html]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = item.selectFirst("._3dc935")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val seriesLink = item.selectFirst("a[href*=/series/]")
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = item.selectFirst("._76d12c, img")?.attr("src").toNullIfBlank(),
                author = item.select("._eeab32").firstOrNull { (it.selectFirst("span")?.text() ?: "").startsWith("Автор") }
                    ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank(),
                reader = item.select("._eeab32").firstOrNull { (it.selectFirst("span")?.text() ?: "").startsWith("Читает") }
                    ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank(),
                genre = item.selectFirst("._eeab32 b a")?.text().toNullIfBlank(),
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }.distinctBy { it.id }
    }

    private fun parseTracks(html: String): List<AudioTrack> {
        val start = html.indexOf("window.XSPlayer(")
        if (start < 0) return emptyList()
        val jsonStart = html.indexOf('{', start)
        val jsonEnd = findJsonEnd(html, jsonStart)
        if (jsonStart < 0 || jsonEnd < 0) return emptyList()
        return try {
            val obj = JsonParser.parseString(html.substring(jsonStart, jsonEnd + 1)).asJsonObject
            if (obj.get("blocked")?.asBoolean == true) return emptyList()
            val playlist = obj.getAsJsonArray("playlist")
            playlist.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val title = o.get("title")?.asString ?: return@mapNotNull null
                val src = o.get("src")?.asString ?: return@mapNotNull null
                AudioTrack(
                    title = title,
                    url = src,
                    durationSeconds = o.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
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
