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

/**
 * Источник «Audio-Lib»: https://audio-lib.club
 * Next.js. Треки не в HTML — грузятся через API: /api/p/<slug> → chapters[].src (/api/s/<token>)
 * → 302 на CDN (uknig.com). Referer не требуется.
 */
class AudioLibSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audiolib"
    override val name = "Audio-Lib"
    override val baseUrl = "https://audio-lib.club"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, "$baseUrl/audiobooks?page=$page"))

    override suspend fun search(query: String, page: Int): List<Book> {
        val url = "$baseUrl/api/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=50"
        return runCatching { parseApiSearch(getHtml(client, url)) }.getOrDefault(emptyList())
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val hero = doc.selectFirst(".book-hero__info")
        val title = doc.selectFirst("h1.book-hero__title")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("div.book-hero__cover img[src]")?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
        val author = hero?.selectFirst("a[href^=\"/author/\"]")?.text()?.trim().toNullIfBlank()
        val reader = hero?.selectFirst("a[href^=\"/chtets/\"]")?.text()?.trim().toNullIfBlank()
        val genre = hero?.select("a[href^=\"/genre/\"]")?.eachText()?.joinToString(", ").toNullIfBlank()
        val duration = hero?.selectFirst("span")?.text()?.trim()?.toNullIfBlank()
        val description = hero?.selectFirst("p")?.text()?.trim()?.toNullIfBlank()

        val slug = url.substringAfter("/book/", "").substringBefore('?')
        val tracks = if (slug.isBlank()) emptyList() else fetchTracks(slug)

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
        return BookDetails(book = book, description = description, tracks = tracks)
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else "$url?page=$page"
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres"), baseUrl)
        doc.select("a.genre-card[href^=\"/genre/\"]").forEach { card ->
            val name = card.selectFirst("span.genre-card__name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@forEach
            val href = card.absUrl("href").ifBlank { return@forEach }
            if (seen.add(href)) result += Genre(name = name, url = href)
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, cardSelector = "a[href^=\"/book/\"]")

    private suspend fun fetchTracks(slug: String): List<AudioTrack> {
        val json = runCatching { getHtml(client, "$baseUrl/api/p/$slug") }.getOrNull() ?: return emptyList()
        return runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            val chapters = obj.getAsJsonArray("chapters")
            chapters.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val src = o.get("src")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val title = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "Глава ${o.get("position")?.asInt ?: ""}"
                AudioTrack(title = title, url = if (src.startsWith("http")) src else baseUrl + src)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseApiSearch(json: String): List<Book> {
        return runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            val items = obj.getAsJsonArray("items")
            items.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val slug = o.get("slug")?.asString ?: return@mapNotNull null
                val title = o.get("title")?.asString ?: return@mapNotNull null
                val author = o.get("authorName")?.takeIf { it.isJsonPrimitive }?.asString?.toNullIfBlank()
                Book(sourceId = id, id = "$baseUrl/book/$slug", title = title, url = "$baseUrl/book/$slug", author = author)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a[href^=\"/book/\"]").mapNotNull { card ->
            val href = card.absUrl("href").ifBlank { return@mapNotNull null }
            val title = card.selectFirst("div[style*='font-weight:600']")?.text()?.trim()
                ?: return@mapNotNull null
            val cover = card.selectFirst("img[src*='/covers/']")?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
            val author = card.selectFirst("div[style*='color:var(--muted)']")?.text()?.trim().toNullIfBlank()
            val faint = card.selectFirst("div[style*='color:var(--faint)']")
            val duration = faint?.select("span")?.lastOrNull()?.text()?.trim().toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                durationText = duration,
            )
        }
    }
}