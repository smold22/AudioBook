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
import java.util.Base64

class AudiomirSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audiomir"
    override val name = "Аудиомир"
    override val baseUrl = "https://m1.audiomir.xyz"

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
        val author = fnscValue(doc, "Автор")
        val h1 = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val titleTail = h1.substringAfterLast(" - ", "")
        val title = titleTail.takeIf { it.isNotBlank() }
            ?.let { h1.removeSuffix(" - $it").trim() }
            ?: author?.let { h1.removeSuffix(" - $it").trim() }?.takeIf { it.isNotBlank() }
            ?: h1
        val reader = fnscValue(doc, "Читает")
        val genre = doc.select(".main-news-c a").eachText().joinToString(", ").toNullIfBlank()
        val duration = fnscValue(doc, "Время")
        val cover = doc.selectFirst(".main-news-image img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else baseUrl + it }.toNullIfBlank()
        val description = doc.selectFirst(".full-news-text, .main-news-text")?.text().toNullIfBlank()
        val seriesDiv = doc.select(".fnsc-left div").firstOrNull {
            (it.selectFirst("i")?.text() ?: "").startsWith("Серия") ||
                (it.selectFirst("i")?.text() ?: "").startsWith("Цикл")
        }
        val seriesLink = seriesDiv?.selectFirst("a")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = seriesDiv?.ownText()?.substringAfter("№", "")?.trim()
            ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
            ?: seriesDiv?.text()?.substringAfter("№", "")?.trim()
                ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }

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
        return BookDetails(book = book, description = description, tracks = parseTracks(html, url, title))
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
        return doc.select(".main-news-c a, .side-block a").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/") || href.contains("/xfsearch/") || href.contains("/page/")) {
                return@mapNotNull null
            }
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = href)
        }.distinctBy { it.url }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "div.main-news")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".main-news").mapNotNull { item ->
            val link = item.selectFirst(".main-news-title a") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val rawTitle = link.text().trim()
            val titleTail = rawTitle.substringAfterLast(" - ", "")
            val displayAuthor = infoValue(item, "Автор")
            val title = titleTail.takeIf { it.isNotBlank() }
                ?.let { rawTitle.removeSuffix(" - $it").trim() }
                ?: displayAuthor?.let { rawTitle.removeSuffix(" - $it").trim() }
                ?: rawTitle
            val reader = infoValue(item, "Читает")
            val genre = item.select(".main-news-c a").eachText().joinToString(", ").toNullIfBlank()
            val cover = item.selectFirst(".main-news-image img")?.attr("data-src")
                ?: item.selectFirst(".main-news-image img")?.attr("src")
            val seriesDiv = item.select("div").firstOrNull {
                (it.selectFirst("i")?.text() ?: "").startsWith("Серия") ||
                    (it.selectFirst("i")?.text() ?: "").startsWith("Цикл")
            }
            val seriesLink = seriesDiv?.selectFirst("a")
            val seriesIndex = seriesDiv?.text()?.substringAfter("№", "")?.trim()
                ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover?.let { if (it.startsWith("http")) it else baseUrl + it }.toNullIfBlank(),
                author = displayAuthor,
                reader = reader,
                genre = genre,
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesIndex = seriesIndex,
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    private fun infoValue(item: Element, label: String): String? =
        item.select("div").firstOrNull { (it.selectFirst("i")?.text() ?: "").startsWith(label) }
            ?.selectFirst("a")?.text()?.takeIf { it.isNotBlank() }
            ?: item.select("div").firstOrNull { (it.selectFirst("i")?.text() ?: "").startsWith(label) }
                ?.selectFirst(".gjty")?.text().toNullIfBlank()

    private fun fnscValue(doc: org.jsoup.nodes.Document, label: String): String? {
        val div = doc.select(".fnsc-left div").firstOrNull { (it.selectFirst("i")?.text() ?: "").startsWith(label) }
            ?: return null
        return div.selectFirst("a")?.text()
            ?: div.selectFirst(".gjty")?.text()
            ?: div.ownText()?.trim().toNullIfBlank()
    }

    private suspend fun parseTracks(html: String, bookUrl: String, bookName: String): List<AudioTrack> {
        val doc = Jsoup.parse(html, bookUrl)
        for (script in doc.select("script")) {
            val raw = script.toString()
            val idx = raw.indexOf("new Playerjs")
            if (idx < 0) continue
            val fileMatch = Regex("""file:"([^"]+)"""").find(raw.substring(idx)) ?: continue
            val playlistUrl = AudiomirDecoder.decode(fileMatch.groupValues[1]) ?: continue
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

private object AudiomirDecoder {
    private val TOKENS = listOf(
        "TDduWDJ3WjV0UTFZcA",
        "RjJnNlQ5Ylk0blgzSGo",
        "TTNrRzhyVDZ5Sjl4QnY",
        "UjV2UThtTmM3WnAyTHhB",
        "QjdkSDRqUDFxVDlzVnc",
    )

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
