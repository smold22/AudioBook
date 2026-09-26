package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * Источник «Слушать Книги» (slushat-knigi.com).
 *
 * Аудиофайлы лежат на CDN, который без Referer с сайта отдаёт 403 — ссылки
 * помечаются меткой `ref=slushat-knigi.com`. На каждой странице сайта есть
 * закреплённый блок «Лучшие аудиокниги», поэтому из ленты берётся только сам
 * список книг, иначе страницы повторяли бы друг друга.
 */
class SlushatKnigiSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "slushat-knigi"
    override val name = "Слушать Книги"
    override val baseUrl = "https://slushat-knigi.com"

    /** Книги последней загруженной страницы ленты: сайт повторяет последнюю страницу вместо 404. */
    private val lastFeedIds = ConcurrentHashMap<String, List<String>>()

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        feed(baseUrl, page) { if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/" }

    override suspend fun newBooks(page: Int): List<Book> =
        feed("$baseUrl/lastnews", page) {
            if (page <= 1) "$baseUrl/lastnews" else "$baseUrl/lastnews/page/$page/"
        }

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
        val description = doc.select("h2.page__subtitle, h3.page__subtitle")
            .firstOrNull { it.text().contains("Аннотация") }
            ?.nextElementSibling()
            ?.takeIf { it.hasClass("page__text") }
            ?.let { elementText(it) }
            ?: doc.selectFirst(".page__text.full-text")?.let { elementText(it) }

        val seriesLi = doc.select(".page__details-list li")
            .firstOrNull { it.selectFirst("span")?.text()?.trim() == "Серия (цикл)" }
        val seriesLink = seriesLi?.selectFirst("a[href]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesBooks = if (seriesUrl != null) {
            runCatching { parseFeed(getHtml(client, seriesUrl, referer = baseUrl)) }
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

    override suspend fun books(url: String, page: Int): List<Book> =
        feed(url, page) { if (page <= 1) url else url.trimEnd('/') + "/page/$page/" }

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> =
        books(seriesUrl, page)

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
        catalogSection(Jsoup.parse(getHtml(client, url), baseUrl))
            ?.selectFirst(".sect__link")?.text()
            ?.filter(Char::isDigit)?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * Загружает очередную страницу ленты и отсекает страницы за концом списка:
     * сайт отдаёт либо 404, либо повторяет последнюю страницу.
     */
    private suspend fun feed(key: String, page: Int, urlFor: (Int) -> String): List<Book> {
        val books = try {
            parseFeed(getHtml(client, urlFor(page)))
        } catch (e: java.io.IOException) {
            // Страница за концом списка (404) — просто конец ленты.
            return emptyList()
        }
        val ids = books.map { it.id }
        if (page > 1 && ids.isNotEmpty() && ids == lastFeedIds[key]) return emptyList()
        lastFeedIds[key] = ids
        return books
    }

    /** Постраничный список книг без закреплённого блока «Лучшие аудиокниги». */
    private fun parseFeed(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        val container = doc.selectFirst("#dle-content")
            ?: catalogSection(doc)?.selectFirst(".sect__content")
            ?: doc.select(".sect__content").lastOrNull()
            ?: doc
        return parseBooks(container)
    }

    /** Секция со списком книг (заголовок «Слушать книги жанра …»). */
    private fun catalogSection(doc: Document): Element? =
        doc.select(".sect").firstOrNull { sect ->
            sect.selectFirst(".sect__title")?.text()?.trim()?.startsWith(CATALOG_TITLE) == true
        }

    /** Текст блока без вставок рекламы и CDATA-секций. */
    private fun elementText(element: Element): String? {
        element.select("script, style").remove()
        element.getAllElements().flatMap { it.childNodes() }
            .filterIsInstance<CDataNode>()
            .forEach { it.remove() }
        return element.text().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim().toNullIfBlank()
    }

    private fun parseBooks(html: String): List<Book> = parseBooks(Jsoup.parse(html, baseUrl))

    private fun parseBooks(container: Element): List<Book> =
        container.select("a.poster-item.grid-item").mapNotNull { item ->
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

    private suspend fun loadTracks(html: String): List<AudioTrack> {
        val playlistUrl = PLAYLIST_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val raw = runCatching { getHtml(client, playlistUrl, referer = baseUrl) }.getOrNull()
        return parsePlaylist(raw).map { it.copy(url = it.url.withRefererRef(REF_HOST)) }
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
        const val REF_HOST = "slushat-knigi.com"
        const val CATALOG_TITLE = "Слушать книги"
        val PLAYLIST_REGEX = Regex(
            """new\s+Playerjs\(\{.*?file:"([^"]+)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}
