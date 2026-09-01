package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class SlushatKnigiSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "slushat-knigi"
    override val name = "Слушать Книги"
    override val baseUrl = "https://slushat-knigi.com"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/page/$page/"))

    override suspend fun newBooks(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) "$baseUrl/lastnews" else "$baseUrl/lastnews/page/$page/"))

    override fun supportsNew(): Boolean = true

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

        val meta = doc.select(".page__details-list li").mapNotNull { li ->
            val spans = li.select("span")
            if (spans.size < 2) return@mapNotNull null
            val label = spans[0].text().trim()
            val value = spans[1].text().trim()
            if (value.isEmpty()) null else label to value
        }.toMap()

        val title = meta["Название"]?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()
                ?.removePrefix("Слушать книгу -")?.trim()
                ?.removeSurrounding("\"")?.trim()
                .toNullIfBlank()
                ?: ""
        val author = meta["Автор"].toNullIfBlank()
        val reader = meta["Озвучивает"].toNullIfBlank()
        val duration = meta["Время озвучки"].toNullIfBlank()
        val genre = meta["Жанр"].toNullIfBlank()
        val cover = doc.selectFirst(".page__poster img[data-src]")?.absUrl("data-src")
            ?.ifBlank { doc.selectFirst(".page__poster")?.attr("data-poster") }
            .toNullIfBlank()
        val description = doc.selectFirst(".page__text.full-text")?.text().toNullIfBlank()

        val seriesLi = doc.select(".page__details-list li")
            .firstOrNull { it.selectFirst("span")?.text()?.trim() == "Серия (цикл)" }
        val seriesLink = seriesLi?.selectFirst("a[href]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesBooks = if (seriesUrl != null) {
            runCatching { parseBooks(getHtml(client, seriesUrl, referer = baseUrl)) }
                .getOrDefault(emptyList())
                .map { it.copy(seriesTitle = seriesTitle, seriesUrl = seriesUrl) }
        } else {
            emptyList()
        }

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
        return BookDetails(
            book = book,
            description = description,
            tracks = loadTracks(html),
            seriesBooks = seriesBooks,
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else url.trimEnd('/') + "/page/$page/"
        return try {
            parseBooks(getHtml(client, target))
        } catch (e: java.io.IOException) {
            // Страница за концом списка (404) — просто конец ленты.
            emptyList()
        }
    }

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> =
        books(seriesUrl, page)

    override fun supportsSeries(): Boolean = true

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        return doc.select("ul.header__menu-hidden li a[href]").mapNotNull { link ->
            val href = link.absUrl("href")
            val path = href.removePrefix("$baseUrl/").trimEnd('/')
            if (path.isBlank() || path == href.trimEnd('/') || path.contains('/') || path.contains('.') || path == "blog") {
                return@mapNotNull null
            }
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = href)
        }.distinctBy { it.url }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, ".poster-item.grid-item")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a.poster-item.grid-item").mapNotNull { item ->
            val href = item.absUrl("href").ifBlank { return@mapNotNull null }
            val titleRaw = item.selectFirst(".poster-item__title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val author = item.selectFirst(".poster-item__meta")?.text()?.trim().toNullIfBlank()
            if (author == "Блог") return@mapNotNull null
            val title = author?.let { titleRaw.removeSuffix(" - $it").trim() }?.takeIf { it.isNotEmpty() }
                ?: titleRaw
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = item.selectFirst("img[data-src]")?.absUrl("data-src").toNullIfBlank(),
                author = author,
                durationText = item.selectFirst(".poster-item__label")?.ownText()?.trim().toNullIfBlank(),
            )
        }.distinctBy { it.id }
    }

    private suspend fun loadTracks(html: String): List<AudioTrack> {
        val playlistUrl = PLAYLIST_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val raw = runCatching { getHtml(client, playlistUrl, referer = baseUrl) }.getOrNull()
        return parsePlaylist(raw)
    }

    private fun parsePlaylist(raw: String?): List<AudioTrack> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JsonParser.parseString(raw).asJsonArray
            array.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                val title = obj.get("title")?.asString?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val file = obj.get("file")?.asString ?: return@mapNotNull null
                AudioTrack(title = title, url = file)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        val PLAYLIST_REGEX = Regex(
            """new\s+Playerjs\(\{.*?file:"([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}
