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

class AknigaComSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "akniga_com"
    override val name = "A-kniga"
    override val baseUrl = "https://a-kniga.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) "$baseUrl/" else "$baseUrl/?page=$page"))

    override suspend fun search(query: String, page: Int): List<Book> {
        val html = getHtml(client, "$baseUrl/search?text=${URLEncoder.encode(query, "UTF-8")}&page=$page")
        val doc = Jsoup.parse(html, baseUrl)
        val section = doc.selectFirst("div.b-statictop-search ul.b-statictop__items") ?: return emptyList()
        return section.select("li.b-statictop__items_item a[href*=/audio-]").mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.ownText()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val addInfo = link.selectFirst(".add_info")?.text().orEmpty()
            val author = addInfoRegex(addInfo, "Автор")
            val reader = addInfoRegex(addInfo, "Чтец") ?: addInfoRegex(addInfo, "Исполнитель")
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                author = author,
                reader = reader,
            )
        }
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val rawTitle = doc.selectFirst("h1.b-maintitle")?.text()?.trim() ?: ""
        val author = doc.selectFirst(".abook-infobook a[rel=author]")?.text().toNullIfBlank()
        val title = author?.let { stripAuthor(rawTitle, it) } ?: rawTitle
        val cover = doc.selectFirst(".abook_image")?.attr("src")
            ?.let { if (it.startsWith("http")) it else baseUrl + it }.toNullIfBlank()
        val reader = doc.selectFirst(".abook-infobook a[href*=/ispolnitel-]")?.text().toNullIfBlank()
        val genre = doc.selectFirst(".abook-infobook .genrebook a")?.text().toNullIfBlank()
        val duration = doc.selectFirst(".panel-clock")?.text()?.trim().toNullIfBlank()
        val description = jsonLdDescription(html)

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
        )
        return BookDetails(
            book = book,
            description = description,
            tracks = loadTracks(html, url),
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (url.contains("?")) url.replace(Regex("""page=\d+""")) { "page=$page" } else "$url?page=$page"
        }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres"), baseUrl)
        val seen = mutableSetOf<String>()
        return doc.select("section.b-statictop__items_item").mapNotNull { item ->
            val link = item.selectFirst("a[href*=/genre-]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/genre-")) return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null
            val count = item.selectFirst(".rate b")?.text()?.filter(Char::isDigit)?.toLongOrNull()
            Genre(name = name, url = href, bookCount = count)
        }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "article.abook-item")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("article.abook-item").mapNotNull { item ->
            if (item.selectFirst(".abook-title span")?.text()?.contains("фрагмент") == true) return@mapNotNull null
            val link = item.selectFirst(".abook-title a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val rawTitle = link.text().trim()
            if (rawTitle.isBlank()) return@mapNotNull null
            val author = item.selectFirst(".content-abook-info a[href*=/avtor-]")?.text().toNullIfBlank()
            val title = author?.let { stripAuthor(rawTitle, it) } ?: rawTitle
            val cover = item.selectFirst("img.b-showshort__cover_image")?.attr("src")
                ?.let { if (it.startsWith("http")) it else baseUrl + it }
            val reader = item.selectFirst(".content-abook-info a[href*=/ispolnitel-]")?.text().toNullIfBlank()
            val genre = item.selectFirst(".abook-genre a")?.text().toNullIfBlank()
            val duration = item.select(".content-abook-info .a-info-item").firstOrNull { info ->
                info.selectFirst("i.fa-clock-o") != null
            }?.text()?.trim().toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover.toNullIfBlank(),
                author = author,
                reader = reader,
                durationText = duration,
                genre = genre,
            )
        }
    }

    private fun addInfoRegex(text: String, label: String): String? =
        Regex("""$label\s+([^()]+)""").find(text)?.groupValues?.get(1)?.trim().toNullIfBlank()

    private fun jsonLdDescription(html: String): String? {
        val doc = Jsoup.parse(html)
        for (script in doc.select("script[type=application/ld+json]")) {
            val data = script.data()
            if (!data.contains("Book")) continue
            return try {
                val obj = JsonParser.parseString(data).asJsonObject
                val main = if (obj.has("mainEntity")) obj.getAsJsonObject("mainEntity") else obj
                main.get("description")?.asString?.trim().toNullIfBlank()
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private suspend fun loadTracks(html: String, bookUrl: String): List<AudioTrack> {
        for (script in Jsoup.parse(html).select("script")) {
            val raw = script.data()
            val marker = "file:"
            val idx = raw.indexOf(marker)
            if (idx < 0) continue
            val fileStart = raw.indexOf('"', idx)
            if (fileStart < 0) continue
            val fileEnd = raw.indexOf('"', fileStart + 1)
            if (fileEnd < 0) continue
            val playlistUrl = raw.substring(fileStart + 1, fileEnd)
            if (!playlistUrl.endsWith(".txt")) continue
            return try {
                val playlist = getHtml(client, playlistUrl, referer = bookUrl)
                val array = JsonParser.parseString(playlist).asJsonArray
                array.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val o = el.asJsonObject
                    val url = o.get("file")?.asString ?: return@mapNotNull null
                    AudioTrack(title = o.get("title")?.asString ?: "", url = url)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }

    private fun stripAuthor(name: String, author: String): String =
        name.removePrefix("$author - ").removePrefix("$author -").trim()
}
