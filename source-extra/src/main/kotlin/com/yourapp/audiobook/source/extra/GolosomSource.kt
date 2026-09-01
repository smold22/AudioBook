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

class GolosomSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "golosom"
    override val name = "Голосом"
    override val baseUrl = "https://golosom.org"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) "$baseUrl/" else "$baseUrl/$page"))

    override suspend fun search(query: String, page: Int): List<Book> =
        parseBooks(getHtml(client, "$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}&page=$page"))

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: ""
        val cover = doc.selectFirst("#BookCoverImage")?.attr("src").toNullIfBlank()
        val author = doc.selectFirst(".BookMetaBlockLine[itemprop=author] a")?.text().toNullIfBlank()
        val reader = doc.select(".BookMetaBlockLine").firstOrNull { line ->
            line.selectFirst(".BookMetaLabel")?.text()?.contains("Чтец") == true
        }?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()
        val genre = doc.selectFirst(".BookMetaBlockLine a[href*=/genre/]")?.text().toNullIfBlank()
        val duration = doc.selectFirst(".PageTitle_Subtitle")?.text()?.trim().toNullIfBlank()
        val description = doc.selectFirst("#BookDescriptionContentInner")?.text().toNullIfBlank()
        val cleanTitle = author?.let { stripAuthor(title, it) } ?: title

        val seriesItems = doc.select(".BookDescriptionSeriesItem").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Book(
                sourceId = id,
                id = href,
                title = item.selectFirst(".BookDescriptionSeriesItemName")?.text()?.trim()
                    ?: link.text().trim(),
                url = href,
                seriesIndex = item.selectFirst(".BookDescriptionSeriesItemIndex")?.text()
                    ?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
        val seriesTitle = seriesItems.firstOrNull()?.title?.substringBeforeLast(" - ", "")
            ?.takeIf { it.isNotBlank() && seriesItems.size > 1 }

        val book = Book(
            sourceId = id,
            id = url,
            title = cleanTitle,
            url = url,
            coverUrl = cover,
            author = author,
            reader = reader,
            durationText = duration,
            genre = genre,
            seriesTitle = seriesTitle,
        )
        return BookDetails(
            book = book,
            description = description,
            tracks = parseTracks(html, cleanTitle),
            seriesBooks = seriesItems,
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (url.contains("?page=")) url.replace(Regex("""page=\d+""")) { "page=$page" } else "$url?page=$page"
        }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/genres"), baseUrl)
        return doc.select(".GenresListItem").mapNotNull { item ->
            val name = item.selectFirst(".GenresListItemName")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = item.absUrl("href").ifBlank { return@mapNotNull null }
            val count = item.selectFirst(".GenresListItemCount")?.text()
                ?.filter(Char::isDigit)?.toLongOrNull()
            Genre(name = name, url = href, bookCount = count)
        }
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".bookListItem").mapNotNull { item ->
            val link = item.selectFirst(".bookListItemName a")
                ?: item.selectFirst(".bookListItemCover")
                ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val name = item.selectFirst(".bookListItemName a")?.text()?.trim()
                ?: item.selectFirst(".bookListItemCoverNameText")?.text()?.trim()
                ?: return@mapNotNull null
            val author = metaValue(item, "Автор")
            val reader = metaValue(item, "Чтец")
            val genre = item.selectFirst(".bookListItemMetaBlock a.Tag[href*=/genre/]")?.text().toNullIfBlank()
            val duration = item.selectFirst(".bookListItemMetaBlock span.Tag")?.text().toNullIfBlank()
            val cover = item.selectFirst(".bookListItemCoverImg")?.attr("data-img")
                ?: item.selectFirst(".bookListItemCover img")?.attr("src")
            Book(
                sourceId = id,
                id = href,
                title = name,
                url = href,
                coverUrl = cover.toNullIfBlank(),
                author = author,
                reader = reader,
                durationText = duration,
                genre = genre,
            )
        }
    }

    private fun metaValue(item: org.jsoup.nodes.Element, label: String): String? =
        item.select(".bookListItemMetaBlock").firstOrNull { block ->
            block.selectFirst(".MetaLabel")?.text()?.startsWith(label) == true
        }?.select(".-allPeople a")?.eachText()?.joinToString(", ").toNullIfBlank()

    private fun parseTracks(html: String, bookName: String): List<AudioTrack> {
        for (script in Jsoup.parse(html).select("script")) {
            val raw = script.toString()
            if (!raw.contains("App.playerInit")) continue
            val start = raw.indexOf("App.playerInit(")
            if (start < 0) continue
            val jsonStart = raw.indexOf('{', start)
            if (jsonStart < 0) continue
            val jsonEnd = findJsonEnd(raw, jsonStart)
            if (jsonEnd < 0) continue
            return try {
                val obj = JsonParser.parseString(raw.substring(jsonStart, jsonEnd + 1)).asJsonObject
                if (obj.get("blocked")?.asBoolean == true) return emptyList()
                val playlist = obj.getAsJsonArray("playlist")
                playlist.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val o = el.asJsonObject
                    val title = o.get("title")?.asString ?: bookName
                    val src = o.get("src")?.asString ?: return@mapNotNull null
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

    private fun stripAuthor(name: String, author: String): String =
        name.removePrefix("$author - ").removePrefix("$author -").trim()
}
