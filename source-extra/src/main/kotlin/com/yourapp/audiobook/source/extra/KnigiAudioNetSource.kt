package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Источник «Книги аудио»: https://www.knigiaudio.net
 * Yii2 (клон Baza-Knig). Плейлист и MP3 на CDN redirectto.cc требуют заголовок
 * Referer с этого сайта — треки помечаются меткой ref. Поиск на сайте сломан (500).
 * У домена без www нет A-записи в DNS (не резолвится), поэтому работает только www;
 * старые URL вида https://knigiaudio.net/... переписываются на www.
 */
class KnigiAudioNetSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "knigiaudio_net"
    override val name = "Книги Аудио (net)"
    override val baseUrl = "https://www.knigiaudio.net"

    override fun urlForId(bookId: String): String =
        withWww(if (bookId.startsWith("http")) bookId else baseUrl + bookId)

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/?page=$page"))

    override suspend fun search(query: String, page: Int): List<Book> = emptyList()

    override suspend fun getBookDetails(url: String): BookDetails {
        val target = withWww(url)
        val html = getHtml(client, target)
        val doc = Jsoup.parse(html, target)
        val title = doc.selectFirst("h1 span.book_title_elem.book_title_name")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("div.book_cover img[src]")?.attr("src")?.absUrl(baseUrl)
            ?.let { it.withRefererRef("www.knigiaudio.net") }?.toNullIfBlank()
        val author = doc.select("h1 span.book_title_elem a[href*='/avtor-']")
            .firstOrNull()?.text()?.trim().toNullIfBlank()
        val reader = doc.select("h1 span.book_title_elem a[href*='/ispolnitel-']")
            .firstOrNull()?.text()?.trim().toNullIfBlank()
        val genre = doc.select("div.book_genre_pretitle a").eachText().joinToString(", ").toNullIfBlank()
        val duration = doc.selectFirst("div.book_blue_block")?.text()?.trim()
            ?.removePrefix("Время звучания:").orEmpty().trim().toNullIfBlank()
        val description = doc.selectFirst("div.book_description")?.text()?.trim().toNullIfBlank()

        val tracks = fetchTracks(html, target)

        val book = Book(
            sourceId = id,
            id = target,
            title = title,
            url = target,
            coverUrl = cover,
            author = author,
            reader = reader,
            durationText = duration,
            genre = genre,
        )
        return BookDetails(book = book, description = description, tracks = tracks)
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val base = withWww(url)
        val target = if (page <= 1) base else "$base?page=$page"
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres"), baseUrl)
        doc.select("a[href*='/genre-']").forEach { link ->
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
            val href = link.absUrl("href").ifBlank { return@forEach }
            if (seen.add(href)) result += Genre(name = name, url = href)
        }
        return result
    }

    override suspend fun genreBookCount(url: String): Long? {
        val doc = Jsoup.parse(getHtml(client, url), url)
        val countText = doc.selectFirst("div.pn_count")?.text() ?: return null
        return Regex("""\d[\d\s]*""").find(countText)
            ?.value?.replace(" ", "")?.toLongOrNull()
    }

    private suspend fun fetchTracks(html: String, bookUrl: String): List<com.yourapp.audiobook.source.api.AudioTrack> {
        val playlist = playerjsPlaylistUrl(html) ?: return emptyList()
        val json = runCatching { getHtml(client, playlist, referer = bookUrl) }.getOrNull() ?: return emptyList()
        return parsePlayerjsJson(json).map { track ->
            track.copy(url = track.url.withRefererRef("www.knigiaudio.net"))
        }
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("article.abook-item").mapNotNull { item ->
            val link = item.selectFirst("a.image-abook[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = item.selectFirst("h2.abook-title a.book-title")?.text()?.trim()
                ?: return@mapNotNull null
            val author = item.selectFirst("a.author-title")?.text()?.trim().toNullIfBlank()
            val cover = item.selectFirst("img.b-showshort__cover_image")?.attr("src")?.absUrl(baseUrl)
                ?.let { it.withRefererRef("www.knigiaudio.net") }?.toNullIfBlank()
            val genre = item.select("div.abook-genre a").eachText().joinToString(", ").toNullIfBlank()
            val reader = item.selectFirst("div.a-info-item a[rel='performer']")?.text()?.trim().toNullIfBlank()
            val duration = item.selectFirst("div.a-info-item .fa-clock-o")?.parent()?.text()?.trim().toNullIfBlank()
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                reader = reader,
                durationText = duration,
                genre = genre,
            )
        }
    }
}

/** Апекс-домен не резолвится (нет A-записи в DNS) — старые URL переписываем на www. */
private fun withWww(url: String): String =
    url.replace(
        Regex("""^https?://(www\.)?knigiaudio\.net(?=[:/?#]|$)"""),
        "https://www.knigiaudio.net",
    )