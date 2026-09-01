package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

class AuthorTodaySource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "author_today"
    override val name = "Author.Today"
    override val baseUrl = "https://author.today"

    private val filterQuery = "access=free&usingNeuralNetworks=any&eg=-&fnd=false"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, catalogUrl(page)))

    override suspend fun search(query: String, page: Int): List<Book> {
        val url = "$baseUrl/search?category=works&q=" +
            URLEncoder.encode(query, "UTF-8") +
            if (page > 1) "&page=$page" else ""
        return parseBooks(getHtml(client, url))
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("h1.book-title span[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("h1.book-title")?.text()?.trim() ?: ""
        val author = doc.selectFirst(".book-authors a")?.text()?.trim().toNullIfBlank()
        val reader = doc.selectFirst("a[href*='field=reciter']")?.text()?.trim().toNullIfBlank()
        val genre = doc.select(".book-genres a").mapNotNull { a ->
            val href = a.absUrl("href")
            if (href.contains("/work/genre/all/")) null else a.text().trim().takeIf { it.isNotBlank() }
        }.distinct().joinToString(", ").toNullIfBlank()
        val cover = doc.selectFirst(".book-action-panel .book-cover img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val duration = Regex("""\d+\s*ч\.\s*\d+\s*мин\.""").find(html)?.value
        val seriesLink = doc.selectFirst("a[href*='/work/series/']")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = seriesLink?.parent()?.text().toNullIfBlank()
            ?.let { Regex("""#(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val description = doc.selectFirst("script[type=application/ld+json]")?.data()
            ?.let { ld ->
                runCatching {
                    val o = JsonParser.parseString(ld).asJsonObject
                    o.get("description")?.asString
                }.getOrNull()
            }
            ?.let { Jsoup.parse(it).text().trim() }
            ?: doc.selectFirst(".annotation .rich-content")?.text()?.trim().toNullIfBlank()

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
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        val seriesBooks = if (seriesUrl != null) {
            runCatching { parseBooks(getHtml(client, seriesUrl)) }
                .getOrDefault(emptyList())
                .filterNot { it.url == url }
        } else {
            emptyList()
        }
        return BookDetails(
            book = book,
            description = description,
            tracks = parseTracks(html),
            seriesBooks = seriesBooks,
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (Regex("""[?&]page=\d+""").containsMatchIn(url)) {
                url.replace(Regex("""(?<=[?&]page=)\d+""")) { page.toString() }
            } else {
                url + if (url.contains("?")) "&" else "?" + "page=$page"
            }
        }
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> =
        if (page <= 1) parseBooks(getHtml(client, seriesUrl)) else emptyList()

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, catalogUrl(1)), baseUrl)
        val seen = mutableSetOf<String>()
        return doc.select("select.main-filter").firstOrNull { it.attr("name") != "format" }
            ?.select("option")?.mapNotNull { option ->
                val value = option.attr("value")
                if (value.isBlank()) return@mapNotNull null
                val name = option.text().trim()
                if (name.isBlank()) return@mapNotNull null
                val href = "$baseUrl/work/genre/$value/audiobook?access=free"
                if (!seen.add(href)) return@mapNotNull null
                Genre(name = name, url = href)
            } ?: emptyList()
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "div.book-row")

    private fun catalogUrl(page: Int): String =
        "$baseUrl/work/genre/all/audiobook?$filterQuery" + if (page > 1) "&page=$page" else ""

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.book-row").mapNotNull { item ->
            val link = item.selectFirst(".book-cover-content") ?: item.selectFirst(".book-title a")
            val href = link?.absUrl("href").toNullIfBlank() ?: return@mapNotNull null
            if (!href.startsWith("$baseUrl/audiobook/") && !href.startsWith("$baseUrl/work/")) {
                return@mapNotNull null
            }
            val title = item.selectFirst(".book-title a")?.text()?.trim().toNullIfBlank()
                ?: return@mapNotNull null
            val img = item.selectFirst(".book-cover-content img")
            val cover = img?.attr("src")?.ifBlank { img.attr("data-src") }.toNullIfBlank()
            val author = item.selectFirst(".book-author a")?.text()?.trim().toNullIfBlank()
            val genre = item.select(".book-genres a").mapNotNull { a ->
                val h = a.absUrl("href")
                if (h.contains("/work/genre/all/")) null else a.text().trim().takeIf { it.isNotBlank() }
            }.distinct().joinToString(", ").toNullIfBlank()
            val duration = item.select(".book-details div").firstOrNull { div ->
                div.selectFirst("i.icon-2-player-play") != null
            }?.ownText()?.trim().toNullIfBlank()
            val seriesLink = item.selectFirst("a[href*='/work/series/']")
            val seriesIndex = item.selectFirst("span.label-row-index")?.text()?.trim()?.toIntOrNull()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                durationText = duration,
                genre = genre,
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesIndex = seriesIndex,
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    private suspend fun parseTracks(html: String): List<AudioTrack> = coroutineScope {
        val doc = Jsoup.parse(html)
        val script = doc.select("script").firstOrNull { it.data().contains("audiobookView") }
            ?: return@coroutineScope emptyList()
        val data = script.data()
        val key = data.indexOf("chapters:")
        if (key < 0) return@coroutineScope emptyList()
        val start = data.indexOf('[', key)
        val end = findArrayEnd(data, start)
        if (start < 0 || end < 0) return@coroutineScope emptyList()

        val chapters = try {
            JsonParser.parseString(data.substring(start, end + 1)).asJsonArray.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val path = o.get("url")?.asString ?: return@mapNotNull null
                AudioTrack(
                    title = o.get("title")?.asString ?: "",
                    url = path,
                    durationSeconds = o.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (chapters.isEmpty()) return@coroutineScope emptyList()

        chapters.map { track ->
            async { signedAudioUrl(track.url)?.let { track.copy(url = it) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun signedAudioUrl(path: String): String? {
        return try {
            val body = getHtml(
                client,
                "$baseUrl/audiobook/gets3url?path=" + URLEncoder.encode(path, "UTF-8"),
            )
            val o = JsonParser.parseString(body).asJsonObject
            if (o.get("isSuccessful")?.asBoolean == true) o.get("data")?.asString else null
        } catch (_: Exception) {
            null
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