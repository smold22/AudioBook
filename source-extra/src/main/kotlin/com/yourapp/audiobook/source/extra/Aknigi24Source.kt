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

class Aknigi24Source(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "aknigi24"
    override val name = "Акниги24"
    override val baseUrl = "https://aknigi24.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl?page=$page"))

    override suspend fun search(query: String, page: Int): List<Book> =
        parseBooks(getHtml(client, "$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}&page=$page"))

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val author = doc.selectFirst("a[href*=/author/]")?.text().toNullIfBlank()
        val h1 = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val title = doc.selectFirst(".breadcrumb-item.active")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: author?.let { h1.removePrefix("$it - ").trim() }?.takeIf { it.isNotBlank() }
            ?: h1
        val cover = doc.selectFirst("img.img-fluid.rounded")?.attr("src").toNullIfBlank()
        val reader = doc.selectFirst("a[href*=/reader/]")?.text().toNullIfBlank()
        val genre = doc.selectFirst("a[href*=/genre/]")?.text().toNullIfBlank()
        val duration = doc.select(".text-muted.small span").firstOrNull { it.text().contains("ч") || it.text().contains("мин") }
            ?.text()?.trim().toNullIfBlank()
        val description = doc.selectFirst("meta[name=description]")?.attr("content").toNullIfBlank()
        val seriesLink = doc.select("p.mb-2.small strong").firstOrNull { it.text().startsWith("Серия") }
            ?.parent()?.selectFirst("a[href*=/serie/]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()

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
            seriesUrl = seriesUrl,
        )
        return BookDetails(book = book, description = description, tracks = parseTracks(doc, title))
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (url.contains("?")) url.replace(Regex("""page=\d+""")) { "page=$page" } else "$url?page=$page"
        }
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else {
            if (seriesUrl.contains("?")) seriesUrl.replace(Regex("""page=\d+""")) { "page=$page" }
            else "$seriesUrl?page=$page"
        }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        return doc.select("a[href*=/genre/]").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/genre/")) return@mapNotNull null
            val name = link.selectFirst("span")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val count = link.selectFirst(".genre-count")?.text()?.filter(Char::isDigit)?.toLongOrNull()
            Genre(name = name, url = href, bookCount = count)
        }.distinctBy { it.url }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "div.books-grid div.card")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".books-grid .card").mapNotNull { item ->
            val link = item.selectFirst(".card-title a") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = item.selectFirst(".book-cover img")?.attr("src").toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = metaP(item, "Автор"),
                reader = metaP(item, "Исполнитель"),
                genre = metaP(item, "Жанр"),
                durationText = item.selectFirst(".cover-badge-duration")?.text().toNullIfBlank(),
            )
        }
    }

    private fun metaP(item: Element, label: String): String? =
        item.select(".book-meta p").firstOrNull { (it.selectFirst("strong")?.text() ?: "").startsWith(label) }
            ?.selectFirst("a")?.text().toNullIfBlank()

    private fun parseTracks(doc: org.jsoup.nodes.Document, bookName: String): List<AudioTrack> {
        val data = doc.selectFirst("#player-data") ?: return emptyList()
        return try {
            val obj = JsonParser.parseString(data.data()).asJsonObject
            val chapterBase = obj.get("chapterBase")?.asString ?: return emptyList()
            val chapters = obj.getAsJsonArray("chapters")
            chapters.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val n = o.get("n")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
                AudioTrack(
                    title = "$bookName. Глава $n",
                    url = "$chapterBase$n",
                    durationSeconds = runCatching { o.get("d")?.asString?.toDoubleOrNull()?.toInt() }
                        .getOrNull(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
