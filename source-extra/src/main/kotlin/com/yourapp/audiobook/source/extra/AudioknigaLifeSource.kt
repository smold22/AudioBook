package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class AudioknigaLifeSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audiokniga_life"
    override val name = "Audiokniga.Life"
    override val baseUrl = "https://audiokniga.life"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"))

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
        val title = doc.selectFirst("h1.title_item")?.text()?.trim() ?: ""
        val cover = doc.selectFirst(".poster_item img")
            ?.attr("data-src")?.ifBlank { doc.selectFirst(".poster_item img")?.attr("src") }
            .toNullIfBlank()
        val author = statValue(doc, "Автор")
        val reader = statValue(doc, "Читает")
        val genre = statValue(doc, "Жанр")
        val description = doc.selectFirst(".full_descr")?.text()
            ?.replace(Regex("""^Аннотация:"""), "")?.trim().toNullIfBlank()

        val seriesLink = doc.selectFirst(".bookitem_meta_block.icon_serie a[href*=/xfsearch/serie/]")
        val seriesText = seriesLink?.text()?.trim().toNullIfBlank()
        val seriesTitle = seriesText?.substringBeforeLast(" (", seriesText)
        val seriesIndex = seriesText?.let {
            Regex("""\((\d+)\)\s*$""").find(it)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { n -> n > 0 }
        }

        val book = Book(
            sourceId = id,
            id = url,
            title = title,
            url = url,
            coverUrl = cover,
            author = author,
            reader = reader,
            genre = genre,
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
        )
        return BookDetails(
            book = book,
            description = description,
            tracks = parseTracks(html, title),
            seriesBooks = listOf(book).takeIf { seriesIndex != null } ?: emptyList(),
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else url.trimEnd('/') + "/page/$page/"
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> =
        books(seriesUrl, page)

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/"), baseUrl)
        val seen = mutableSetOf<String>()
        return doc.select(".spisok-ganrov a.link_genre").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith(baseUrl) || href.contains("index.php") ||
                href.contains("favorites") || href.contains("pravoobladateljam") ||
                href.endsWith(".html")
            ) return@mapNotNull null
            val name = link.selectFirst(".genre_name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null
            val count = link.selectFirst(".genre_kol")?.text()?.filter(Char::isDigit)?.toLongOrNull()
            Genre(name = name, url = href, bookCount = count)
        }
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".short-item.bookitem").mapNotNull { item ->
            val link = item.selectFirst(".bookitem_name a")
                ?: item.selectFirst("a.short-img[href]")
                ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val name = item.selectFirst(".bookitem_name a")?.text()?.trim()
                ?: link.text().trim()
            if (name.isBlank()) return@mapNotNull null
            val cover = item.selectFirst("a.short-img img")
                ?.attr("data-src")?.ifBlank { item.selectFirst("a.short-img img")?.attr("src") }
            val author = metaBlock(item, "icon_author")?.select("a")?.eachText()?.joinToString(", ")
            val reader = metaBlock(item, "icon_reader")?.select("a")?.eachText()?.joinToString(", ")
            val genre = item.selectFirst(".bookitem_genre a")?.text().toNullIfBlank()
            val duration = metaBlock(item, "icon_time")?.text()?.trim().toNullIfBlank()
            val seriesText = metaBlock(item, "icon_serie")?.selectFirst("a[href*=/xfsearch/serie/]")?.text()?.trim()
            val seriesIndex = seriesText?.let {
                Regex("""\((\d+)\)\s*$""").find(it)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { n -> n > 0 }
            }
            Book(
                sourceId = id,
                id = href,
                title = name,
                url = href,
                coverUrl = cover.toNullIfBlank(),
                author = author.toNullIfBlank(),
                reader = reader.toNullIfBlank(),
                durationText = duration,
                genre = genre,
                seriesTitle = seriesText?.substringBeforeLast(" (", seriesText).toNullIfBlank(),
                seriesIndex = seriesIndex,
            )
        }
    }

    private fun metaBlock(item: org.jsoup.nodes.Element, iconClass: String): org.jsoup.nodes.Element? =
        item.selectFirst(".bookitem_meta_block.icon_$iconClass")

    private fun statValue(doc: org.jsoup.nodes.Document, label: String): String? =
        doc.select(".full-news-stats .fstat-item").firstOrNull { item ->
            (item.selectFirst(".fstat-item-title")?.text() ?: "").startsWith(label)
        }?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()

    private fun parseTracks(html: String, bookName: String): List<AudioTrack> {
        for (script in Jsoup.parse(html).select("script")) {
            val raw = script.data()
            val start = raw.indexOf("playerInit(")
            if (start < 0) continue
            val arrStart = raw.indexOf('[', start)
            if (arrStart < 0) continue
            val arrEnd = findJsonEnd(raw, arrStart)
            if (arrEnd < 0) continue
            return try {
                val array = JsonParser.parseString(raw.substring(arrStart, arrEnd + 1)).asJsonArray
                array.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val o = el.asJsonObject
                    val title = o.get("title")?.asString ?: bookName
                    val src = o.get("url")?.asString ?: return@mapNotNull null
                    AudioTrack(
                        title = title,
                        url = src,
                        durationSeconds = o.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt,
                    )
                }
            } catch (_: Exception) {
                continue
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
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }
}
