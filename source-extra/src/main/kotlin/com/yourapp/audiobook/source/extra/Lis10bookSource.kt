package com.yourapp.audiobook.source.extra

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Источник «Лис10бук»: https://lis10book.com
 * Сайт переписан с WordPress на Next.js: каталог рендерится из HTML-карточек .mcard,
 * плейлисты отдаёт внутренний API /api/p/{slug}, поиск — /api/search.
 */
class Lis10bookSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "lis10book"
    override val name = "Лис10бук"
    override val baseUrl = "https://lis10book.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) "$baseUrl/audio/" else "$baseUrl/audio?audio=full&page=$page"))

    override suspend fun search(query: String, page: Int): List<Book> = try {
        parseSearch(getHtml(client, "$baseUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}&page=$page"))
    } catch (_: Exception) {
        emptyList()
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("h1.btitle")?.text()?.trim().orEmpty()
        val meta = jsonLd(doc)
        val author = meta?.nestedText("author", "name") ?: bmetaValue(doc, "Автор")
        val reader = meta?.nestedText("readBy", "name") ?: bmetaValue(doc, "Читает")
        val genre = doc.select(".ochip").eachText().joinToString(", ").toNullIfBlank()
        val duration = bmetaValue(doc, "Длительность") ?: meta?.string("duration")
        val cover = doc.selectFirst(".bcov__img[src]")?.attr("src")?.absUrl(baseUrl)
            ?: meta?.string("image")
        val description = (doc.selectFirst(".book-desc__body")?.text()
            ?: meta?.string("description")?.replace(Regex("<[^>]+>"), " "))
            .toNullIfBlank()
        val seriesRow = doc.select(".bmeta__row").firstOrNull {
            (it.selectFirst(".bmeta__k")?.text() ?: "").startsWith("Серия")
        }
        val seriesLink = seriesRow?.selectFirst(".bmeta__v a[href*=/serie/]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = seriesRow?.selectFirst(".bmeta__v")?.text()?.substringAfter("#", "")?.trim()
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
        return BookDetails(
            book = book,
            description = description,
            tracks = fetchTracks(url, title),
            related = doc.select(".hscroll a.mcard").mapNotNull { mcard(it) },
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else paginatedUrl(url, page)
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else "${seriesUrl.trimEnd('/')}?page=$page"
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

    override suspend fun genreBookCount(url: String): Long? {
        val doc = Jsoup.parse(getHtml(client, url), url)
        val countText = doc.selectFirst(".loadmore__count")?.text() ?: return null
        return Regex("""из\s+([\d\s]+)""", RegexOption.IGNORE_CASE).find(countText)
            ?.groupValues?.get(1)?.replace(" ", "")?.toLongOrNull()
    }

    private fun parseBooks(html: String): List<Book> =
        Jsoup.parse(html, baseUrl).select("a.mcard").mapNotNull { mcard(it) }

    private fun mcard(card: Element): Book? {
        val href = card.absUrl("href")
        if (!href.startsWith("$baseUrl/audio/")) return null
        val title = card.selectFirst(".mcard-t")?.text()?.trim() ?: return null
        if (title.isBlank()) return null
        val cover = card.selectFirst(".mcov img[src]")?.attr("src")?.absUrl(baseUrl).toNullIfBlank()
        val author = card.selectFirst(".mcard-a")?.text()?.trim().toNullIfBlank()
        return Book(
            sourceId = id,
            id = href,
            title = title,
            url = href,
            coverUrl = cover,
            author = author,
        )
    }

    private fun parseSearch(json: String): List<Book> = try {
        val root = JsonParser.parseString(json).asJsonObject
        val items = root.getAsJsonArray("items") ?: return emptyList()
        items.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject
            val slug = obj.string("slug") ?: return@mapNotNull null
            val title = obj.string("title") ?: return@mapNotNull null
            val coverPath = obj.string("coverPath")
            Book(
                sourceId = id,
                id = "$baseUrl/audio/$slug/",
                title = title,
                url = "$baseUrl/audio/$slug/",
                coverUrl = coverPath?.let { "$baseUrl/wp-content/uploads/$it" }.toNullIfBlank(),
                author = obj.string("authorName"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun paginatedUrl(url: String, page: Int): String {
        val base = url.trimEnd('/')
        val audioFull = base.endsWith("/genres") || base.endsWith("/audio") || base.contains("/genres/")
        return if (audioFull) "$base?audio=full&page=$page" else "$base?page=$page"
    }

    private fun bmetaValue(doc: Document, label: String): String? =
        doc.select(".bmeta__row").firstOrNull { (it.selectFirst(".bmeta__k")?.text() ?: "").startsWith(label) }
            ?.selectFirst(".bmeta__v")?.text()?.trim().toNullIfBlank()

    private fun jsonLd(doc: Document): JsonObject? = try {
        doc.select("script[type='application/ld+json']").asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("\"Audiobook\"") }
            ?.let { JsonParser.parseString(it).asJsonObject }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.nestedText(objKey: String, field: String): String? = try {
        getAsJsonObject(objKey)?.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchTracks(bookUrl: String, bookName: String): List<AudioTrack> {
        val slug = bookUrl.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return emptyList()
        return try {
            val body = getHtml(client, "$baseUrl/api/p/$slug")
            val obj = JsonParser.parseString(body).asJsonObject
            val chapters = obj.getAsJsonArray("chapters") ?: return emptyList()
            chapters.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val chapter = el.asJsonObject
                val src = chapter.string("src") ?: return@mapNotNull null
                val title = chapter.string("title") ?: bookName
                val duration = chapter.get("durationSec")?.takeIf { it.isJsonPrimitive && it.asLong > 0 }?.asInt
                AudioTrack(title = title, url = src, durationSeconds = duration)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}