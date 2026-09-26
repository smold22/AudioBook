package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Источник «Аудиополка» (audiopolka.club).
 *
 * Отдельной страницы со списком жанров на сайте нет, поэтому список жанров
 * собирается из базового набора KNOWN_GENRES и ссылок `/genre/{id}/`,
 * которые встречаются в карточках каталога.
 */
class AudiopolkaSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audiopolka"
    override val name = "Аудиополка"
    override val baseUrl = "https://audiopolka.club"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, catalogUrl(page)))

    override suspend fun search(query: String, page: Int): List<Book> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/search/?q=$encoded"
        } else {
            "$baseUrl/search/p$page/?q=$encoded"
        }
        return parseBooks(getHtml(client, url))
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst(".book-page-title a")?.text()?.trim()
            ?: doc.selectFirst("[itemprop=name]")?.text()?.trim()
            ?: ""
        val cover = doc.selectFirst("#book-page-cover img, .book-page-cover img")
            ?.absUrl("src").toNullIfBlank()
        val author = doc.selectFirst(".book-page-meta-line [itemprop=author] a")?.text().toNullIfBlank()
        val reader = doc.selectFirst(".book-page-meta-line a[href*=/voice/]")?.text().toNullIfBlank()
        val duration = metaValue(doc, "Длительность")
        val genre = doc.selectFirst(".book-page-nav a[href*=/genre/]")?.text().toNullIfBlank()
        val description = doc.selectFirst(".book-page-annotation-content")?.text()
            ?.replace(Regex("\\s+"), " ")?.trim().toNullIfBlank()

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
            tracks = parseTracks(html, title),
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        if (page <= 1) return parseBooks(getHtml(client, url))
        // У циклов, авторов и дикторов пагинации нет: сайт отдаёт первую страницу.
        if (!url.contains("/genre/")) return emptyList()
        val html = getHtml(client, withPage(url, page))
        // За последней страницей сайт отдаёт её содержимое, поэтому такие страницы отсекаем.
        val totalPages = PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
        if (totalPages != null && page > totalPages) return emptyList()
        return parseBooks(html)
    }

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> =
        books(seriesUrl, page)

    override suspend fun genres(): List<Genre> = coroutineScope {
        val discovered = GENRE_SAMPLE_PAGES.map { page ->
            async(Dispatchers.IO) {
                runCatching { genreLinks(getHtml(client, catalogUrl(page))) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
        val result = LinkedHashMap<String, Genre>()
        KNOWN_GENRES.forEach { (id, name) ->
            val url = genreUrl(id)
            result[url] = Genre(name = name, url = url)
        }
        discovered.forEach { (url, name) -> result.putIfAbsent(url, Genre(name = name, url = url)) }
        result.values.sortedBy { it.name }
    }

    override suspend fun genreBookCount(url: String): Long? {
        val html = getHtml(client, url)
        val perPage = Jsoup.parse(html, url).select(ITEM_SELECTOR).size
        val pages = PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
        if (perPage == 0 || pages == null) return null
        return perPage.toLong() * pages
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(ITEM_SELECTOR).mapNotNull { item ->
            val title = item.selectFirst(".book-list-item-name-link")?.text()?.trim()
                ?: return@mapNotNull null
            val href = item.selectFirst(".book-list-item-cover, .book-list-item-name-link")
                ?.absUrl("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = item.selectFirst(".book-list-item-cover-img img")?.absUrl("src").toNullIfBlank()
            val seriesLink = item.selectFirst(".book-list-item-serie-link")
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = item.selectFirst(".book-list-item-author-link")?.text().toNullIfBlank(),
                reader = item.selectFirst(".book-list-item-reader-link")?.text().toNullIfBlank(),
                durationText = item.selectFirst(".book-list-item-duration-link")?.text().toNullIfBlank(),
                genre = item.selectFirst(".book-list-item-genre-link")?.text().toNullIfBlank(),
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    private fun genreLinks(html: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a[href*=/genre/]").mapNotNull { link ->
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = link.absUrl("href").substringBefore('?').takeIf { it.contains("/genre/") }
                ?: return@mapNotNull null
            href to name
        }
    }

    private fun metaValue(doc: org.jsoup.nodes.Document, label: String): String? =
        doc.select(".book-page-meta-line")
            .firstOrNull { it.selectFirst(".book-page-light-text")?.text()?.trim() == label }
            ?.select("span")?.firstOrNull()?.ownText()?.trim().toNullIfBlank()

    private fun catalogUrl(page: Int): String = if (page <= 1) "$baseUrl/" else "$baseUrl/p$page/"

    private fun genreUrl(genreId: String): String = "$baseUrl/genre/$genreId/"

    /** Вставляет номер страницы перед завершающим слэшем, сохраняя query-параметры. */
    private fun withPage(url: String, page: Int): String {
        val path = url.substringBefore('?').trimEnd('/')
        val query = url.substringAfter('?', "")
        val paged = "$path/p$page/"
        return if (query.isEmpty()) paged else "$paged?$query"
    }

    private fun parseTracks(html: String, bookName: String): List<AudioTrack> {
        for (script in Jsoup.parse(html).select("script")) {
            val raw = script.toString()
            if (!raw.contains("KB.playerInit")) continue
            val start = raw.indexOf("KB.playerInit(")
            if (start < 0) continue
            val jsonStart = raw.indexOf('{', start)
            if (jsonStart < 0) continue
            val jsonEnd = findJsonEnd(raw, jsonStart)
            if (jsonEnd < 0) continue
            val obj = runCatching {
                JsonParser.parseString(raw.substring(jsonStart, jsonEnd + 1)).asJsonObject
            }.getOrNull() ?: continue
            if (obj.get("blocked")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return emptyList()
            val playlist = obj.getAsJsonArray("playlist") ?: return emptyList()
            return playlist.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val src = o.get("src")?.asString ?: return@mapNotNull null
                AudioTrack(
                    title = o.get("title")?.asString ?: bookName,
                    url = src,
                    durationSeconds = o.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt,
                )
            }
        }
        return emptyList()
    }

    private fun findJsonEnd(raw: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }

    private companion object {
        const val ITEM_SELECTOR = "div.book-list-item"
        val PAGES_REGEX = Regex("""KB\.PageNav\.Initialize\(\{"page":\d+,"pages":(\d+)""")

        /** Страницы каталога для поиска жанров, которых ещё нет в KNOWN_GENRES. */
        val GENRE_SAMPLE_PAGES = listOf(1, 2, 3)

        /** Базовый список жанров: индексной страницы на сайте нет. */
        val KNOWN_GENRES = listOf(
            "676717" to "Роман, проза",
            "842582" to "Классика",
            "1714668" to "Биографии, мемуары, ЖЗЛ",
            "1809283" to "Медицина, здоровье",
            "4491252" to "Ужасы, мистика, хоррор",
            "4586579" to "Детективы, триллеры, боевики",
            "5629311" to "Религия",
            "5823177" to "Поэзия",
            "6010783" to "Обучение",
            "6139639" to "Ранобэ",
            "7230868" to "Для детей, аудиосказки, стишки",
            "7380642" to "Разное",
            "7670678" to "Эзотерика, Нетрадиционные религиозно-философские учения",
            "8132765" to "Психология, философия",
            "8778627" to "Фантастика, фэнтези",
            "8852750" to "Аудиоспектакли, радиопостановки и литературные чтения",
            "9039329" to "Бизнес, личностный рост",
            "9490849" to "Юмор, сатира",
            "9626211" to "Приключения, военные приключения",
            "9905791" to "История, культурология",
        )
    }
}
