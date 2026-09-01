package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

/**
 * Торрент-источник «Аудиокниги MP3»: https://t-audioknigimp3.org
 * Движок DataLife Engine. Книги распространяются только через торрент-файлы.
 * Скачивание торрента двухшаговое: страница /file/...torrent → скрытый input#dlink
 * → реальный файл /download/<id> (требует заголовок Referer).
 */
class TAudioknigiMp3Source(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "taudioknigimp3"
    override val name = "Аудиокниги MP3 (торрент)"
    override val baseUrl = "https://t-audioknigimp3.org"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override fun supportsTorrent(): Boolean = true

    override suspend fun fetchTorrentBytes(url: String): ByteArray? {
        val pageHtml = getHtml(client, url)
        val dlink = Jsoup.parse(pageHtml, url)
            .selectFirst("input#dlink")
            ?.attr("value")
            ?.toNullIfBlank()
            ?: throw IOException("Не удалось получить прямую ссылку на торрент-файл")
        return getBytes(client, dlink, referer = url)
    }

    override suspend fun home(page: Int): List<Book> =
        fetchPage(if (page <= 1) baseUrl else "$baseUrl/page/$page/")

    override suspend fun search(query: String, page: Int): List<Book> {
        val url = "$baseUrl/index.php?do=search&subaction=search&story=${URLEncoder.encode(query, "UTF-8")}"
        if (page <= 1) return fetchPage(url)
        return parseBooks(
            postForm(
                client,
                url,
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query,
                    "search_start" to ((page - 1) * 10).toString(),
                ),
                referer = baseUrl,
            ),
        )
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("h1.inner-entry__title")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("div.inner-entry__image img[src]")?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
        val text = doc.selectFirst("#msg")?.text().orEmpty()
        val author = metaValue(text, "Автор:")
        val reader = metaValue(text, "Озвучивает:")
        val genre = metaValue(text, "Жанр:")
        val duration = metaValue(text, "Продолжительность:")
        val description = metaValue(text, "Описание:")
        val torrentPage = doc.selectFirst("div#download a.download-torrent[href]")
            ?.attr("href")
            ?.absUrl(baseUrl)
            ?.toNullIfBlank()

        val parsed = parseTitleAuthor(title)
        val book = Book(
            sourceId = id,
            id = url,
            title = parsed.first,
            url = url,
            coverUrl = cover,
            author = author ?: parsed.second,
            reader = reader,
            durationText = duration,
            genre = genre,
        )
        return BookDetails(
            book = book,
            description = description,
            torrentUrl = torrentPage,
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val base = url.trimEnd('/')
        val target = if (page <= 1) url else "$base/page/$page/"
        return fetchPage(target)
    }

    /** HTTP 404 означает конец пагинации — возвращаем пустой список. */
    private suspend fun fetchPage(url: String): List<Book> =
        runCatching { parseBooks(getHtml(client, url)) }
            .getOrElse { e ->
                if (e.message?.contains("HTTP 404") == true) emptyList() else throw e
            }

    override suspend fun genres(): List<Genre> {
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        doc.select("ul.block__menu a[href], ul.menu a[href]").forEach { link ->
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
            val href = link.absUrl("href").ifBlank { return@forEach }
            if (href != baseUrl && href.trimEnd('/') != baseUrl && seen.add(href)) {
                result += Genre(name = name, url = href)
            }
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, cardSelector = "div.entry")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.entry").mapNotNull { item ->
            val link = item.selectFirst("div.entry__title a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val fullTitle = link.text().trim()
            if (fullTitle.isBlank()) return@mapNotNull null
            val parsed = parseTitleAuthor(fullTitle)
            val cover = item.selectFirst("div.entry_content img[src]")?.attr("src")?.absUrl(baseUrl)?.toNullIfBlank()
            val size = item.selectFirst("span.entry__info-size")?.text()?.trim().toNullIfBlank()
            val genre = item.select("div.entry__info-categories a[href]").eachText()
                .joinToString(", ").toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = parsed.first,
                url = href,
                coverUrl = cover,
                author = parsed.second,
                durationText = size,
                genre = genre,
            )
        }
    }

    private companion object {
        val META_KEYS = listOf(
            "Название:", "Жанр:", "Автор:", "Озвучивает:", "Год издания книги:",
            "Издательство:", "Продолжительность:", "Формат/Кодек:", "Битрейт аудио:", "Описание:",
        )

        /** «Автор - Название (2025) МР3» → пара (Название, Автор). */
        fun parseTitleAuthor(fullTitle: String): Pair<String, String?> {
            val cleaned = fullTitle
                .replace(Regex("""\s*\([^)]*\)\s*$"""), "")
                .trim()
            val author = cleaned.substringBefore(" - ").trim()
                .takeIf { it.isNotBlank() && it != cleaned }
            val title = if (author != null) {
                cleaned.substringAfter(" - ").trim().ifBlank { cleaned }
            } else {
                cleaned
            }
            return title to author
        }

        fun metaValue(text: String, key: String): String? {
            val start = text.indexOf(key)
            if (start < 0) return null
            val after = text.substring(start + key.length)
            val end = META_KEYS.filter { it != key }
                .mapNotNull { after.indexOf(it).takeIf { index -> index >= 0 } }
                .minOrNull() ?: after.length
            return after.substring(0, end).trim().toNullIfBlank()
        }
    }
}