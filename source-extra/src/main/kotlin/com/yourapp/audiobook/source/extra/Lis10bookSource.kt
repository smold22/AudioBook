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
import java.util.Base64

class Lis10bookSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "lis10book"
    override val name = "Лис10бук"
    override val baseUrl = "https://lis10book.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) "$baseUrl/audio/" else "$baseUrl/audio/page/$page/"))

    override suspend fun search(query: String, page: Int): List<Book> = try {
        parseBooks(getHtml(client, "$baseUrl/?s=${URLEncoder.encode(query, "UTF-8")}"))
    } catch (_: Exception) {
        emptyList()
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("h1.d-inline-block")?.text()?.trim() ?: ""
        val author = tableValue(doc, "Автор")
        val reader = tableValue(doc, "Читает")
        val genre = doc.select("a.btn[href*=/genres/]").eachText().joinToString(", ").toNullIfBlank()
        val duration = tableValue(doc, "Длительность")
        val cover = doc.selectFirst("img.poster")?.attr("data-lazy-src")
            ?: doc.selectFirst("img.poster")?.attr("src")
        val description = doc.select("h5").firstOrNull { it.text().contains("Описание") }
            ?.nextElementSibling()?.text().toNullIfBlank()
        val seriesRow = doc.select(".table tr").firstOrNull { (it.selectFirst("th")?.text() ?: "").startsWith("Серия") }
        val seriesLink = seriesRow?.selectFirst("td a[href*=/serie/]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = seriesRow?.selectFirst("td")?.ownText()?.substringAfter("#", "")?.trim()
            ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
            ?: seriesRow?.selectFirst("td")?.text()?.substringAfter("#", "")?.trim()
                ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }

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
        return BookDetails(book = book, description = description, tracks = parseTracks(html, url, title))
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else url.replace(Regex("""/page/\d+"""), "/page/$page")
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else "$seriesUrl?page=$page"
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres/"), baseUrl)
        return doc.select("a[href*=/genres/]").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/genres/")) return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = href)
        }.distinctBy { it.url }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "div.post")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".post").mapNotNull { item ->
            val link = item.selectFirst("h6 a") ?: item.selectFirst("h5 a")
                ?: item.selectFirst("a[href*=/audio/]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/audio/")) return@mapNotNull null
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val img = item.selectFirst("img.poster")
            val cover = img?.attr("data-lazy-src")?.ifBlank { img.attr("src") }.toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
            )
        }
    }

    private fun tableValue(doc: org.jsoup.nodes.Document, label: String): String? =
        doc.select(".table tr").firstOrNull { (it.selectFirst("th")?.text() ?: "").startsWith(label) }
            ?.selectFirst("td a")?.text().toNullIfBlank()

    private suspend fun parseTracks(html: String, bookUrl: String, bookName: String): List<AudioTrack> {
        val doc = Jsoup.parse(html, bookUrl)
        for (script in doc.select("script")) {
            val raw = script.toString()
            val idx = raw.indexOf("new Playerjs")
            if (idx < 0) continue
            val fileMatch = Regex("""file:"([^"]+)"""").find(raw.substring(idx)) ?: continue
            val playlistUrl = LisDecoder.decode(fileMatch.groupValues[1]) ?: continue
            if (playlistUrl.isBlank()) continue
            return try {
                val body = getHtml(client, playlistUrl)
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

private object LisDecoder {
    private val TOKENS = listOf("IXE4Km0", "JTUmMkA", "OSNwJCo", "QDMkaCE", "JDcmayM")

    fun decode(file: String): String? {
        return try {
            var s = if (file.startsWith("#")) file.substring(1) else file
            s = s.replace("//", "")
            var changed = true
            while (changed) {
                changed = false
                for (t in TOKENS) {
                    for (form in listOf("$t==", "$t=", t)) {
                        val n = s.replace(form, "")
                        if (n != s) {
                            changed = true
                            s = n
                        }
                    }
                }
            }
            if (s.isEmpty()) return null
            s = s.substring(1)
            String(Base64.getDecoder().decode(s), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
