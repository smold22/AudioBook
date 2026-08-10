package com.yourapp.audiobook.source.izibuk

import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class IziBukSource(
    private val client: OkHttpClient = defaultClient(),
) : AudiobookSource {

    override val id = "izibuk"
    override val name = "ИзиБук"
    override val baseUrl = "https://pda.izib.uk/"

    override fun urlForId(id: String): String = "https://pda.izib.uk/art$id"

    override suspend fun home(page: Int): List<Book> {
        val url = if (page <= 1) baseUrl else "${baseUrl}?p=$page"
        return parseBookList(get(url), id, baseUrl)
    }

    override suspend fun search(query: String, page: Int): List<Book> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "${baseUrl}search?q=$encoded" else "${baseUrl}search?q=$encoded&p=$page"
        return parseBookList(get(url), id, baseUrl)
    }

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = get(url)
        if (html.contains("удалена по требованию правообладателя")) {
            throw IOException("Аудиокнига удалена по требованию правообладателя")
        }
        if (html.contains("Ознакомительный фрагмент") || html.contains("Купить аудиокнигу")) {
            throw IOException("Доступен только ознакомительный фрагмент, полная версия платная")
        }
        return parseDetails(html, id, url)
            ?: throw IOException("Не удалось разобрать страницу книги")
    }

    override suspend fun genres(): List<Genre> =
        parseGenres(get("${baseUrl}genres"), baseUrl)

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (url.contains("?")) "$url&p=$page" else "$url?p=$page"
        }
        return parseBookList(get(target), id, baseUrl)
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else {
            if (seriesUrl.contains("?")) "$seriesUrl&p=$page" else "$seriesUrl?p=$page"
        }
        return parseSeriesBooks(get(target), id, baseUrl)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        var lastException: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} для $url")
                    }
                    return@withContext response.body?.string()
                        ?: throw IOException("Пустой ответ для $url")
                }
            } catch (e: Exception) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw e
                }
                lastException = e
                delay(500L * (attempt + 1))
            }
        }
        throw lastException ?: IOException("Ошибка запроса для $url")
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        private val DURATION_REGEX = Regex("""\d+\s*ч\.?\s*\d*\s*мин\.?""")
        private val PLAYER_REGEX = Regex("""new\s+XSPlayer\((\{.*?\})\)\s*;""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val BLOCKED_REGEX = Regex(""""blocked":(true|false)""")
        private val ID_REGEX = Regex(""""id":(\d+)""")
        private val MP3_PREFIX_REGEX = Regex(""""mp3_url_prefix":"([^"]*)"""")
        private val SIGN_REGEX = Regex(""""sign":"([^"]*)"""")
        private val TRACK_REGEX = Regex("""\[(\d+),"([^"]*)",(\d+),\d+,"([^"]*)"\]""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                    )
                    .header("Referer", "https://pda.izib.uk/")
                    .build()
                chain.proceed(request)
            }
            .build()

        fun parseGenres(html: String, baseUrl: String): List<Genre> {
            val doc = Jsoup.parse(html, baseUrl)
            val result = mutableListOf<Genre>()
            val seen = mutableSetOf<String>()
            doc.select("._c2b650 a").forEach { item ->
                val name = item.select("._28f7ab").firstOrNull()?.text()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@forEach
                val href = item.absUrl("href").toNullIfBlank() ?: return@forEach
                if (seen.add(href)) {
                    result += Genre(
                        name = name,
                        url = href,
                        bookCount = item.select("._94566d").firstOrNull()?.text()
                            ?.let { text -> text.filter { it.isDigit() }.toLongOrNull() },
                    )
                }
            }
            doc.select("a[href*=\"subcategory=\"]").forEach { item ->
                val name = item.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
                val href = item.absUrl("href").toNullIfBlank() ?: return@forEach
                if (seen.add(href)) {
                    result += Genre(name = name, url = href, bookCount = null)
                }
            }
            return result
        }

        fun parseBookList(html: String, sourceId: String, baseUrl: String): List<Book> {
            val doc = Jsoup.parse(html, baseUrl)
            return doc.select("._ccb9b7").mapNotNull { item ->
                if (item.selectFirst("._09ddb7") != null) return@mapNotNull null
                val link = item.select("a[href^=/art]").firstOrNull() ?: return@mapNotNull null
                val href = link.absUrl("href")
                val id = Regex("""/art(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val title = item.select("._3dc935 a").firstOrNull()?.text() ?: return@mapNotNull null
                val duration = item.select("._08dd3a").eachText()
                    .firstOrNull { DURATION_REGEX.containsMatchIn(it) }
                val seriesLink = item.select("a[href^=/serie]").firstOrNull()
                Book(
                    sourceId = sourceId,
                    id = id,
                    title = title,
                    url = href,
                    coverUrl = item.select("img").attr("src").toNullIfBlank(),
                    author = item.select("a[href^=/author]").firstOrNull()?.text().toNullIfBlank(),
                    reader = item.select("a[href^=/reader]").firstOrNull()?.text().toNullIfBlank(),
                    durationText = duration,
                    genre = item.select("._680f12 a").firstOrNull()?.text().toNullIfBlank(),
                    seriesTitle = seriesLink?.text().toNullIfBlank(),
                    seriesIndex = item.attr("data-serie-index").toIntOrNull()?.takeIf { it > 0 },
                    seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
                )
            }
        }

        fun parseSeriesBooks(html: String, sourceId: String, baseUrl: String): List<Book> {
            val doc = Jsoup.parse(html, baseUrl)
            return doc.select("._ccb9b7").mapNotNull { item ->
                if (item.selectFirst("._09ddb7") != null) return@mapNotNull null
                val link = item.select("a[href^=/art]").firstOrNull() ?: return@mapNotNull null
                val href = link.absUrl("href")
                val id = Regex("""/art(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val title = item.select("._3dc935 a").firstOrNull()?.text() ?: return@mapNotNull null
                val duration = item.select("._08dd3a").eachText()
                    .firstOrNull { DURATION_REGEX.containsMatchIn(it) }
                val seriesLink = item.select("a[href^=/serie]").firstOrNull()
                Book(
                    sourceId = sourceId,
                    id = id,
                    title = title,
                    url = href,
                    coverUrl = item.select("img").attr("src").toNullIfBlank(),
                    author = item.select("a[href^=/author]").firstOrNull()?.text().toNullIfBlank(),
                    reader = item.select("a[href^=/reader]").firstOrNull()?.text().toNullIfBlank(),
                    durationText = duration,
                    genre = item.select("._680f12 a").firstOrNull()?.text().toNullIfBlank(),
                    seriesTitle = seriesLink?.text().toNullIfBlank(),
                    seriesIndex = item.attr("data-serie-index").toIntOrNull()?.takeIf { it > 0 },
                    seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
                )
            }
        }

        fun extractPlayerConfig(html: String): String? =
            PLAYER_REGEX.find(html)?.groupValues?.get(1)

        fun parseTracks(html: String): List<AudioTrack> {
            val config = extractPlayerConfig(html) ?: return emptyList()
            val blocked = BLOCKED_REGEX.find(config)?.groupValues?.get(1) == "true"
            if (blocked) return emptyList()
            val prefix = MP3_PREFIX_REGEX.find(config)?.groupValues?.get(1)?.unescapeJs()
            val sign = SIGN_REGEX.find(config)?.groupValues?.get(1)?.unescapeJs()
            if (prefix == null || sign == null) return emptyList()
            return TRACK_REGEX.findAll(config).map { match ->
                val title = match.groupValues[2].unescapeJs()
                val file = match.groupValues[4].unescapeJs()
                AudioTrack(
                    title = title,
                    url = "https://$prefix/$file$sign",
                    durationSeconds = match.groupValues[3].toIntOrNull(),
                )
            }.toList()
        }

        fun parseDetails(html: String, sourceId: String, requestedUrl: String): BookDetails? {
            val doc = Jsoup.parse(html, requestedUrl)
            val title = doc.select("span[itemprop=name]").firstOrNull()?.text()
                ?: return null
            val cover = doc.select("._306524 img").attr("src").toNullIfBlank()
            val author = doc.select("._8f5765 a[href^=/author]").firstOrNull()?.text().toNullIfBlank()
            val reader = doc.select("._8f5765 a[href^=/reader]").firstOrNull()?.text().toNullIfBlank()
            val metaText = doc.select("._8f5765").eachText().joinToString(" ")
            val duration = DURATION_REGEX.find(metaText)?.value
            val description = doc.select("span[itemprop=description]").text().toNullIfBlank()
            val config = extractPlayerConfig(html)
            val bookId = config?.let { ID_REGEX.find(it)?.groupValues?.get(1) }
                ?: requestedUrl.substringAfterLast("/art", "")
            val seriesLink = doc.select("._40d1c3 a[href^=/serie]").firstOrNull()
            val seriesTitle = seriesLink?.text().toNullIfBlank()
            val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
            val seriesBooks = doc.select("._0a3ec5").mapNotNull { item ->
                val link = item.selectFirst("a[href^=/art]") ?: return@mapNotNull null
                val href = link.absUrl("href").toNullIfBlank() ?: return@mapNotNull null
                val id = Regex("""/art(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val index = item.selectFirst("._77155e")?.text()
                    ?.trim()?.trimEnd('.')?.toIntOrNull()?.takeIf { it > 0 }
                Book(
                    sourceId = sourceId,
                    id = id,
                    title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    url = href,
                    reader = item.selectFirst("a[href^=/reader]")?.text().toNullIfBlank(),
                    seriesTitle = seriesTitle,
                    seriesIndex = index,
                    seriesUrl = seriesUrl,
                )
            }
            val book = Book(
                sourceId = sourceId,
                id = bookId,
                title = title,
                url = requestedUrl,
                coverUrl = cover,
                author = author,
                reader = reader,
                durationText = duration,
                genre = null,
                seriesTitle = seriesTitle,
                seriesUrl = seriesUrl,
            )
            return BookDetails(
                book = book,
                description = description,
                tracks = if (config == null) emptyList() else parseTracks(html),
                seriesBooks = seriesBooks,
            )
        }

        private fun String.unescapeJs(): String =
            replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")

        private fun String?.toNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
    }
}
