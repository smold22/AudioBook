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

class AudioknigaOneSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audiokniga_one"
    override val name = "Аудиокнига One"
    override val baseUrl = "https://audiokniga-one.com"

    private companion object {
        val SERIES_REGEX = Regex("""(.*?)[\s.]+(?:Том|Книга|Часть)\s*(\d+)\s*$""", RegexOption.IGNORE_CASE)
    }

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
        val author = metaLine(doc, "Автор")
        val h1 = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val title = author?.let { h1.removeSuffix(" - $it").trim() }?.takeIf { it.isNotBlank() } ?: h1
        val reader = metaLine(doc, "Читает")
        val genre = metaLine(doc, "Жанр")
        val cover = doc.selectFirst(".poster__img img")?.attr("src")
            ?: doc.selectFirst("img.xfieldimage")?.attr("src")
        val description = doc.selectFirst(".full-text")?.text().toNullIfBlank()
        val (seriesTitle, seriesIndex) = extractSeries(title)

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover?.let { if (it.startsWith("http")) it else baseUrl + it }.toNullIfBlank(),
            author = author,
            reader = reader,
            genre = genre,
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesTitle?.let {
                "$baseUrl/index.php?do=search&subaction=search&full_search=1&story=${URLEncoder.encode(it, "UTF-8")}"
            },
        )
        return BookDetails(book = book, description = description, tracks = parseTracks(html, title))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val story = java.net.URLDecoder.decode(seriesUrl.substringAfter("story=").substringBefore("&"), "UTF-8")
        val html = postForm(
            client = client,
            url = "$baseUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "search_start" to page.toString(),
                "full_search" to "1",
                "story" to story,
            ),
            referer = baseUrl,
        )
        return parseBooks(html)
    }

    private fun extractSeries(title: String): Pair<String?, Int?> {
        val match = SERIES_REGEX.find(title) ?: return null to null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() } to
            match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else url.replace(Regex("""/page/\d+""")) { "/page/$page" }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        return doc.select(".side-block__menu a").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/")) return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = href)
        }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "a.poster.grid-item")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a.poster.grid-item").mapNotNull { item ->
            val href = item.absUrl("href").ifBlank { return@mapNotNull null }
            val rawTitle = item.selectFirst(".poster__title")?.text()?.trim() ?: return@mapNotNull null
            val author = rawTitle.substringAfterLast(" - ", "").takeIf { it.isNotBlank() }.toNullIfBlank()
            val title = author?.let { rawTitle.removeSuffix(" - $it").trim() } ?: rawTitle
            val cover = item.selectFirst("img.xfieldimage")?.attr("src")
            val genre = item.selectFirst(".poster__subtitle li")?.text().toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover?.let { if (it.startsWith("http")) it else baseUrl + it }.toNullIfBlank(),
                author = author,
                genre = genre,
            )
        }
    }

    private fun metaLine(doc: org.jsoup.nodes.Document, label: String): String? =
        doc.select(".pmovie__genres").firstOrNull { it.text().startsWith(label) }
            ?.selectFirst("a")?.text().toNullIfBlank()

    private suspend fun parseTracks(html: String, bookName: String): List<AudioTrack> {
        val doc = Jsoup.parse(html, baseUrl)
        for (script in doc.select("script")) {
            val raw = script.toString()
            val idx = raw.indexOf("new Playerjs")
            if (idx < 0) continue
            val fileMatch = Regex("""file:\s*"([^"]+)"""").find(raw.substring(idx)) ?: continue
            val playlistUrl = fileMatch.groupValues[1]
            if (playlistUrl.isBlank()) continue
            return try {
                val body = getHtml(client, playlistUrl, referer = baseUrl)
                val array = JsonParser.parseString(body.trim()).asJsonArray
                array.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val obj = el.asJsonObject
                    val file = obj.get("file")?.asString ?: return@mapNotNull null
                    val title = obj.get("title")?.asString ?: bookName
                    AudioTrack(title = title, url = file)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }
}
