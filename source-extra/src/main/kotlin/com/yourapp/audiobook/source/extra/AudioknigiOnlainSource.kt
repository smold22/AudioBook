package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Источник «Аудиокниги онлайн»: https://audioknigi-onlain.com
 * DataLife Engine + Playerjs. Плейлист и MP3 на CDN redirectto.cc требуют
 * заголовок Referer с этого сайта — треки помечаются меткой ref.
 */
class AudioknigiOnlainSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audioknigi_onlain"
    override val name = "Аудиокниги Онлайн"
    override val baseUrl = "https://audioknigi-onlain.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        fetchPage(if (page <= 1) baseUrl else "$baseUrl/page/$page/")

    override suspend fun search(query: String, page: Int): List<Book> {
        val url = "$baseUrl/index.php?do=search&subaction=search&story=${URLEncoder.encode(query, "UTF-8")}"
        return fetchPage(url)
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("article.page h1")?.text()?.trim().orEmpty()
        val cover = (doc.selectFirst("div.page__poster img[data-src]")?.attr("data-src")
            ?: doc.selectFirst("div.page__poster img[src]")?.attr("src"))?.absUrl(baseUrl)?.toNullIfBlank()
        val genre = doc.select("span.page__meta-item--genres a").eachText().joinToString(", ").toNullIfBlank()
        val detailsItems = doc.select("ul.page__details-list li")
        val author = detailsItems.mapNotNull { li ->
            val spans = li.select("span")
            if (spans.size >= 2 && spans[0].text().contains("Автор")) {
                spans[1].text().trim().toNullIfBlank()
            } else {
                null
            }
        }.firstOrNull()
        val reader = detailsItems.mapNotNull { li ->
            val spans = li.select("span")
            if (spans.size >= 2 && spans[0].text().contains("Озвучивает")) {
                spans[1].text().trim().toNullIfBlank()
            } else {
                null
            }
        }.firstOrNull()
        val description = doc.selectFirst("div.page__text.full-text")?.text()?.trim().toNullIfBlank()

        val tracks = fetchTracks(html, url)

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover,
            author = author,
            reader = reader,
            genre = genre,
        )
        return BookDetails(book = book, description = description, tracks = tracks)
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else "${url.trimEnd('/')}/page/$page/"
        return fetchPage(target)
    }

    override suspend fun genres(): List<Genre> {
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        doc.select("a[href]").forEach { link ->
            val href = link.absUrl("href")
            val path = href.removePrefix("$baseUrl/")
            if (path.isNotBlank() && path.count { it == '/' } == 1 &&
                !path.startsWith("page") && !path.startsWith("index") &&
                !path.startsWith("lastnews") && !path.startsWith("top") && seen.add(href)
            ) {
                val name = link.text().trim()
                if (name.isNotBlank() && name.length <= 50) result += Genre(name = name, url = href)
            }
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, cardSelector = "a.poster-item.grid-item")

    private suspend fun fetchTracks(html: String, bookUrl: String): List<com.yourapp.audiobook.source.api.AudioTrack> {
        val playlist = playerjsPlaylistUrl(html) ?: return emptyList()
        val json = runCatching { getHtml(client, playlist, referer = bookUrl) }.getOrNull() ?: return emptyList()
        return parsePlayerjsJson(json).map { track ->
            track.copy(url = track.url.withRefererRef("audioknigi-onlain.com"))
        }
    }

    /** HTTP 404 означает конец пагинации — возвращаем пустой список. */
    private suspend fun fetchPage(url: String): List<Book> =
        runCatching { parseBooks(getHtml(client, url)) }
            .getOrElse { e ->
                if (e.message?.contains("HTTP 404") == true) emptyList() else throw e
            }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a.poster-item.grid-item").mapNotNull { item ->
            val href = item.absUrl("href").ifBlank { return@mapNotNull null }
            val fullTitle = item.selectFirst("div.poster-item__title")?.text()?.trim()
                ?: return@mapNotNull null
            val parsed = parseTitleAuthor(fullTitle)
            val cover = (item.selectFirst("img[data-src]")?.attr("data-src")
                ?: item.selectFirst("img[src]")?.attr("src"))?.absUrl(baseUrl)?.toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = parsed.first,
                url = href,
                coverUrl = cover,
                author = parsed.second,
            )
        }
    }

    private companion object {
        /** «Название - Автор» → пара (Название, Автор). */
        fun parseTitleAuthor(fullTitle: String): Pair<String, String?> {
            val author = fullTitle.substringBefore(" - ").trim()
                .takeIf { it.isNotBlank() && it != fullTitle }
            val title = if (author != null) {
                fullTitle.substringAfter(" - ").trim().ifBlank { fullTitle }
            } else {
                fullTitle
            }
            return title to author
        }
    }
}