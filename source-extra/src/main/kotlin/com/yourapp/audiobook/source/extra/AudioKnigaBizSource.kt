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
 * Источник «Аудиокнига-биз» (audio-kniga.biz) — сайт на DataLife Engine с тем же
 * шаблоном списков, что и A-kniga, но обложки и аудио лежат на CDN, который
 * отвечает 403 без Referer с сайта: ссылки помечаются меткой `ref=audio-kniga.biz`.
 *
 * Плейлист Playerjs (`*.pl.txt`) перечисляет отрезки одного mp3-файла
 * (`start`/`end` в секундах), поэтому книга отдаётся одним треком на весь файл.
 * У части старых книг вместо Playerjs встроен плеер bibliovk с авторизацией —
 * для них треков нет.
 */
class AudioKnigaBizSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audioknigabiz"
    override val name = "Аудиокнига-биз"
    override val baseUrl = "https://audio-kniga.biz"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> {
        val html = getHtml(client, withPage("$baseUrl/", page))
        if (isClampedToLastPage(html, page)) return emptyList()
        return parseBooks(html)
    }

    override suspend fun search(query: String, page: Int): List<Book> {
        // Поиск отдаёт все найденные книги сразу, постраничной навигации нет.
        if (page > 1) return emptyList()
        val html = getHtml(client, "$baseUrl/search?text=${URLEncoder.encode(query, "UTF-8")}")
        val doc = Jsoup.parse(html, baseUrl)
        val section = doc.selectFirst("div.b-statictop-search ul.b-statictop__items")
            ?: return emptyList()
        return section.select("li.b-statictop__items_item a[href*=/audio-]").mapNotNull { link ->
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = link.ownText().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
            )
        }
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val rawTitle = doc.selectFirst("h1.b-maintitle")?.text()?.trim().orEmpty()
        val author = doc.selectFirst(".info-book a[rel=author]")?.text().toNullIfBlank()
        val title = author?.let { stripAuthor(rawTitle, it) }.toNullIfBlank() ?: rawTitle
        val cover = doc.selectFirst("img.abook_image")?.absUrl("src")
            .toNullIfBlank()?.withRefererRef(REF_HOST)
        val reader = doc.selectFirst(".info-book a[href*=/ispolnitel-]")?.text().toNullIfBlank()
        val genre = doc.selectFirst(".abook_genre a, .fullentry_info a[href*=/genre-]")
            ?.text()?.trim().toNullIfBlank()
        val duration = durationText(doc)
        val seriesLink = doc.select(".info-book .panel-item")
            .firstOrNull { it.selectFirst("i.fa-list") != null }
            ?.selectFirst("a[href*=/series-]")
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
            seriesTitle = seriesLink?.text()?.trim().toNullIfBlank(),
            seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
        )
        return BookDetails(
            book = book,
            description = description,
            tracks = loadTracks(html, url, title),
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val html = getHtml(client, withPage(url, page))
        if (isClampedToLastPage(html, page)) return emptyList()
        return parseBooks(html)
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

    override suspend fun genreBookCount(url: String): Long? {
        val html = getHtml(client, url)
        val perPage = Jsoup.parse(html, url).select(ITEM_SELECTOR).size
        if (perPage == 0) return null
        // Пагинатор показывает только окно из нескольких страниц, поэтому точное
        // количество известно лишь на последней странице — там «вперёд» отключено.
        val lastPage = activePage(html)?.takeIf { hasNoNextPage(html) } ?: return null
        return perPage.toLong() * lastPage
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(ITEM_SELECTOR).mapNotNull { item ->
            val link = item.selectFirst(".abook-title a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawTitle = link.text().trim()
            if (rawTitle.isBlank()) return@mapNotNull null
            val author = item.selectFirst(".content-abook-info a[href*=/avtor-]")?.text().toNullIfBlank()
            val title = author?.let { stripAuthor(rawTitle, it) }.toNullIfBlank() ?: rawTitle
            val cover = item.selectFirst("img.b-showshort__cover_image")?.absUrl("src")
                .toNullIfBlank()?.withRefererRef(REF_HOST)
            val reader = item.selectFirst(".content-abook-info a[href*=/ispolnitel-]")?.text().toNullIfBlank()
            val genre = item.selectFirst(".abook-genre a")?.text()?.trim().toNullIfBlank()
            val duration = item.select(".content-abook-info .a-info-item").firstOrNull { info ->
                info.selectFirst("i.fa-clock-o") != null
            }?.text()?.replace(Regex("\\s+"), " ")?.trim().toNullIfBlank()
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

    private fun durationText(doc: org.jsoup.nodes.Document): String? {
        val hours = doc.selectFirst(".book-player span.hours")?.text()?.trim().orEmpty()
        val minutes = doc.selectFirst(".book-player span.minutes")?.text()?.trim().orEmpty()
        return "$hours $minutes".replace(Regex("\\s+"), " ").trim().toNullIfBlank()
    }

    private fun jsonLdDescription(html: String): String? {
        val doc = Jsoup.parse(html)
        for (script in doc.select("script[type=application/ld+json]")) {
            val data = script.data()
            if (!data.contains("Book")) continue
            return try {
                val obj = JsonParser.parseString(data).asJsonObject
                val main = if (obj.has("mainEntity")) obj.getAsJsonObject("mainEntity") else obj
                plainText(main.get("description")?.asString)
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /** В аннотации из ld+json встречаются теги и entities — приводим к чистому тексту. */
    private fun plainText(raw: String?): String? =
        raw?.let { Jsoup.parse(it).text() }?.trim().toNullIfBlank()

    private suspend fun loadTracks(html: String, bookUrl: String, bookTitle: String): List<AudioTrack> {
        val playlistUrl = playerjsPlaylistUrl(html) ?: return emptyList()
        val json = runCatching { getHtml(client, playlistUrl, referer = bookUrl) }.getOrNull()
            ?: return emptyList()
        val entries = runCatching {
            JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                val file = obj.get("file")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return@mapNotNull null
                file to obj.get("end")?.takeIf { it.isJsonPrimitive }?.asInt
            }
        }.getOrDefault(emptyList())
        val files = entries.map { it.first }.distinct()
        if (files.isEmpty()) return emptyList()
        return if (files.size == 1) {
            // Плейлист нарезает один mp3 на отрезки — отдаём файл целиком одним треком.
            val duration = entries.mapNotNull { it.second }.maxOrNull()
            listOf(
                AudioTrack(
                    title = bookTitle,
                    url = files.first().withRefererRef(REF_HOST),
                    durationSeconds = duration,
                ),
            )
        } else {
            files.map { AudioTrack(title = bookTitle, url = it.withRefererRef(REF_HOST)) }
        }
    }

    /** Заменяет номер страницы в URL, сохраняя остальные query-параметры. */
    private fun withPage(url: String, page: Int): String {
        val path = url.substringBefore('?').trimEnd('/')
        val query = url.substringAfter('?', "")
        if (page <= 1) return if (query.isEmpty()) "$path/" else "$path/?$query"
        val params = query.split('&')
            .filter { it.isNotBlank() && !it.startsWith("page=") }
            .plus("page=$page")
        return "$path/?${params.joinToString("&")}"
    }

    /** Номер страницы, отмеченной в пагинаторе как активная. */
    private fun activePage(html: String): Int? =
        ACTIVE_PAGE_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()

    private fun hasNoNextPage(html: String): Boolean = NEXT_DISABLED_REGEX.containsMatchIn(html)

    /**
     * Сайт не показывает ошибку для слишком большого номера страницы, а отдаёт
     * последнюю существующую — такие страницы отсекаем, чтобы не было дублей.
     */
    private fun isClampedToLastPage(html: String, requested: Int): Boolean {
        val active = activePage(html) ?: return false
        return requested > active
    }

    private fun stripAuthor(name: String, author: String): String =
        name.removePrefix("$author - ").removePrefix("$author -").trim()

    private companion object {
        const val ITEM_SELECTOR = "article.abook-item"
        const val REF_HOST = "audio-kniga.biz"
        val ACTIVE_PAGE_REGEX = Regex("""<li class="active"><a href="[^"]*[?&]page=(\d+)""")
        val NEXT_DISABLED_REGEX = Regex("""<li class="next disabled">""")
    }
}
