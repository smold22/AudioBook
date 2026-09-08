package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class BazaKnigSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "baza_knig"
    override val name = "База книг"
    override val baseUrl = "https://baza-knig.top"

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
        if (html.contains("Just a moment")) {
            throw java.io.IOException("Защита от ботов (Cloudflare)")
        }
        val doc = Jsoup.parse(html, url)
        val h1 = doc.selectFirst("h1")?.ownText()?.trim()
        val title = h1?.substringBefore(" - ") ?: ""
        val cover = doc.selectFirst(".full-img img, .img-responsive")?.attr("src").toNullIfBlank()
        val description = doc.selectFirst(".short-text")?.ownText().toNullIfBlank()

        var author: String? = null
        var reader: String? = null
        var genre: String? = null
        var durationText: String? = null
        var seriesTitle: String? = null
        var seriesUrl: String? = null
        var seriesIndex: Int? = null
        doc.select("ul.full-items li").forEach { li ->
            val text = li.text()
            when {
                text.contains("Автор") -> author = li.select("a").eachText().joinToString(", ").toNullIfBlank()
                text.contains("Читает") -> reader = li.select("a").eachText().joinToString(", ").toNullIfBlank()
                text.contains("Жанр") -> genre = li.select("a").eachText().joinToString(", ").toNullIfBlank()
                text.contains("Длительность") -> durationText = li.selectFirst("b")?.text().toNullIfBlank()
                text.contains("Цикл") -> {
                    seriesTitle = li.select("a").eachText().joinToString(", ").toNullIfBlank()
                    seriesUrl = li.selectFirst("a")?.absUrl("href").toNullIfBlank()
                    seriesIndex = li.select("b").lastOrNull()?.text()?.filter(Char::isDigit)
                        ?.toIntOrNull()?.takeIf { it > 0 }
                }
            }
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
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        return BookDetails(book = book, description = description, tracks = parseTracks(doc, title))
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (Regex("""/page/\d+""").containsMatchIn(url)) {
                url.replace(Regex("""/page/\d+""")) { "/page/$page" }
            } else {
                url + "?cstart=${(page - 1) * 20}"
            }
        }
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else "$seriesUrl?cstart=${(page - 1) * 20}"
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        val slug = Regex("""/[a-z0-9_-]+/""")
        return doc.select("ul.left-menu-items a").mapNotNull { link ->
            val href = link.attr("href")
            if (!slug.matches(href)) return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = link.absUrl("href"))
        }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "div.short")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("#dle-content .short").mapNotNull { item ->
            val link = item.selectFirst(".short-title a") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim()
            val img = item.selectFirst(".short-img img")
            val cover = img?.attr("src")?.let {
                if (it.startsWith("http")) it else baseUrl + it
            }.toNullIfBlank()
            val author = item.select(".short-items li").firstOrNull { it.text().contains("Автор") }
                ?.selectFirst("b")?.text().toNullIfBlank()
            val reader = item.select(".short-items li").firstOrNull { it.text().contains("Читает") }
                ?.selectFirst("b")?.text().toNullIfBlank()
            val cycleLi = item.select(".short-items li").firstOrNull { it.text().contains("Цикл") }
            val genre = item.select(".short-items li").firstOrNull { it.text().contains("Жанр") }
                ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()
            if (title.isBlank()) return@mapNotNull null
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                reader = reader,
                durationText = null,
                genre = genre,
                seriesTitle = cycleLi?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank(),
                seriesIndex = cycleLi?.select("b")?.lastOrNull()?.text()?.filter(Char::isDigit)
                    ?.toIntOrNull()?.takeIf { it > 0 },
                seriesUrl = cycleLi?.selectFirst("a")?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    private suspend fun parseTracks(doc: org.jsoup.nodes.Document, bookName: String): List<AudioTrack> {
        // 1. strDecode(...) в <script>
        for (script in doc.select("script")) {
            val raw = script.toString()
            if (raw.contains("strDecode(")) {
                val start = raw.indexOf("strDecode(")
                val keyStart = raw.indexOf('"', start) + 1
                if (keyStart > 0) {
                    val keyEnd = raw.indexOf('"', keyStart)
                    if (keyEnd > keyStart) {
                        val encoded = raw.substring(keyStart, keyEnd)
                        return try {
                            val decoded = JsRuntime.strDecode(encoded)
                            parseJsonTracks(decoded)
                        } catch (_: Exception) {
                            continue
                        }
                    }
                }
            }
        }
        // 2) iframe #fr data->playlist
        val fr = doc.getElementById("fr")
        if (fr != null) {
            val data = fr.attr("data")
            val srcStart = data.indexOf("src=")
            if (srcStart >= 0) {
                val playlistUrl = data.substring(srcStart).substringAfter("src=").substringBefore("\"")
                val playlistHtml = getHtml(client, playlistUrl)
                val player = Jsoup.parse(playlistHtml).selectFirst(".js-play8-playlist")
                if (player != null) {
                    return try {
                        val array = JsonParser.parseString(player.attr("value")).asJsonArray
                        array.mapNotNull { el ->
                            if (!el.isJsonObject) return@mapNotNull null
                            val obj = el.asJsonObject
                            val sources = obj.getAsJsonArray("sources")
                            val file = sources?.get(0)?.asJsonObject?.get("file")?.asString ?: return@mapNotNull null
                            AudioTrack(
                                title = obj.get("title")?.asString ?: "",
                                url = if (file.startsWith("http")) file else "https://archive.org$file",
                                durationSeconds = obj.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt,
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }
        // 3. массив с id:"player" в <script>
        for (script in doc.select("script")) {
            val raw = script.toString()
            if (raw.contains("id:\"player\"")) {
                val start = raw.indexOf('[')
                val end = raw.lastIndexOf(']')
                if (start >= 0 && end > start) {
                    return try {
                        parseJsonTracks(raw.substring(start, end + 1))
                    } catch (_: Exception) {
                        continue
                    }
                }
            }
        }
        return emptyList()
    }

    private fun parseJsonTracks(json: String): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
        val array = JsonParser.parseString(json).asJsonArray
        for (el in array) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val title = obj.get("title")?.asString ?: continue
            val file = obj.get("file")?.asString ?: obj.get("src")?.asString ?: continue
            result += AudioTrack(title = title, url = file)
        }
        return result
    }
}