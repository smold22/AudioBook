package com.yourapp.audiobook.source.extra

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class AknigaSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "akniga"
    override val name = "Акнига"
    override val baseUrl = "https://akniga.org"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, "$baseUrl/index/page$page/"))

    override suspend fun search(query: String, page: Int): List<Book> =
        parseBooks(getHtml(client, "$baseUrl/search/books/page$page/?q=${URLEncoder.encode(query, "UTF-8")}"))

    override suspend fun getBookDetails(url: String): BookDetails {
        val html = getHtml(client, url, referer = "$baseUrl/performers/")
        val doc = Jsoup.parse(html, url)

        val rawTitle = doc.selectFirst(".caption__article-main")?.text()?.trim() ?: ""
        val author = doc.selectFirst(".about-author a")?.text().toNullIfBlank()
        val title = author?.let { stripAuthor(rawTitle, it) } ?: rawTitle
        val coverImg = doc.selectFirst(".cover__wrapper--image img")
        val cover = (coverImg?.attr("data-src")?.ifBlank { coverImg.attr("src") }).toNullIfBlank()
        val reader = (doc.selectFirst(".link__reader")?.text() ?: doc.selectFirst(".about-artist a")?.text())
            .toNullIfBlank()
        val description = doc.selectFirst("[itemprop=description]")?.ownText().toNullIfBlank()
        val duration = buildString {
            doc.selectFirst(".hours")?.text()?.let { append(it).append(" ") }
            doc.selectFirst(".minutes")?.text()?.let { append(it) }
        }.trim().toNullIfBlank()

        val seriesLink = doc.selectFirst("a[href*=/serie/]")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = doc.selectFirst("[data-serie-index]")?.attr("data-serie-index")
            ?.toIntOrNull()?.takeIf { it > 0 }
        val seriesBooks = doc.select(".book_series .book_series_item, .b-book__series .book_series_item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Book(
                sourceId = id,
                id = href,
                title = link.text().trim(),
                url = href,
                seriesTitle = seriesTitle,
                seriesIndex = item.attr("data-serie-index").toIntOrNull()?.takeIf { it > 0 },
                seriesUrl = seriesUrl,
            )
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
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        return BookDetails(
            book = book,
            description = description,
            tracks = loadTracks(url, doc, rawTitle),
            seriesBooks = seriesBooks,
        )
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else {
            if (Regex("""/page\d+/""").containsMatchIn(url)) {
                url.replace(Regex("""/page\d+/""")) { "/page$page/" }
            } else {
                url.trimEnd('/') + "/page$page/"
            }
        }
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, "$baseUrl/sections/"), baseUrl)
        val result = mutableListOf<Genre>()
        val seen = mutableSetOf<String>()
        doc.select(".name-obj").forEach { obj ->
            val link = obj.selectFirst("h4 a.name") ?: return@forEach
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
            val href = link.absUrl("href").ifBlank { return@forEach }
            val count = obj.selectFirst(".description")?.text()
                ?.filter(Char::isDigit)?.toLongOrNull()
            if (seen.add(href)) result += Genre(name = name, url = href, bookCount = count)
            obj.select("a[href*=/label/genre/]").forEach { label ->
                val labelName = label.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
                val labelHref = label.absUrl("href").ifBlank { return@forEach }
                if (seen.add(labelHref)) result += Genre(name = labelName, url = labelHref)
            }
        }
        return result
    }

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".content__main__articles--item").mapNotNull { item ->
            if (item.selectFirst(".caption__article-preview")?.text()?.trim() == "Фрагмент") return@mapNotNull null
            if (item.selectFirst(".link-article--paid") != null || item.selectFirst("a[href*=paid]") != null) {
                return@mapNotNull null
            }
            val link = item.selectFirst(".content__article-main-link")
                ?: item.selectFirst(".book-cover a")
                ?: item.selectFirst("a[href]")
                ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val name = item.selectFirst(".caption__article-main")?.text()?.trim() ?: link.text().trim()
            if (name.isBlank()) return@mapNotNull null
            val author = item.selectFirst("a[href*=/author/]")?.text().toNullIfBlank()
            val reader = (item.selectFirst("a[href*=/performer/]")?.text()
                ?: item.selectFirst(".link__action--performer")?.text()
                ?: item.selectFirst(".link__action--reader")?.text()).toNullIfBlank()
            val genre = item.selectFirst(".section__title")?.text().toNullIfBlank()
            val title = author?.let { stripAuthor(name, it) } ?: name
            val coverImg = item.selectFirst(".cover img") ?: item.selectFirst("img")
            val cover = (coverImg?.attr("data-src")?.ifBlank { coverImg.attr("src") }).toNullIfBlank()
            val seriesLink = item.selectFirst("a[href*=/serie/]")
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = author,
                reader = reader,
                durationText = null,
                genre = genre,
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesIndex = item.attr("data-serie-index").toIntOrNull()?.takeIf { it > 0 },
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else seriesUrl.trimEnd('/') + "/page$page/"
        return parseBooks(getHtml(client, target))
    }

    private suspend fun loadTracks(bookUrl: String, doc: org.jsoup.nodes.Document, bookTitle: String): List<AudioTrack> {
        val securityKey = extractSecurityKey(doc) ?: return emptyList()
        val bid = doc.selectFirst("[data-bid]")?.attr("data-bid") ?: return emptyList()

        val hash = JsRuntime.getHash(securityKey)
        val body = postForm(
            client = client,
            url = "$baseUrl/ajax/b/$bid",
            data = mapOf(
                "bid" to bid,
                "hash" to hash,
                "hls" to "true",
                "security_ls_key" to securityKey,
            ),
            referer = bookUrl,
            extraHeaders = mapOf(
                "Origin" to baseUrl,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        return parseAjaxResponse(bid, bookTitle, body)
    }

    private fun extractSecurityKey(doc: org.jsoup.nodes.Document): String? {
        for (script in doc.select("script")) {
            val data = script.data()
            val marker = "LIVESTREET_SECURITY_KEY = '"
            val start = data.indexOf(marker)
            if (start >= 0) {
                return data.substring(start + marker.length).substringBefore("'").ifBlank { null }
            }
        }
        return null
    }

    private fun parseAjaxResponse(bid: String, bookTitle: String, body: String): List<AudioTrack> {
        return try {
            val json = JsonParser.parseString(body.trim())
            if (!json.isJsonObject) return emptyList()
            val obj = json.asJsonObject
            val srv = obj.get("srv")?.asString ?: return emptyList()
            val audioUrl: String = obj.get("key")?.let { key ->
                val slug = obj.get("slug")?.asString ?: ""
                "$srv/b/$bid/$key/$slug.mp3"
            } ?: obj.get("hres")?.asString?.let { hres ->
                JsRuntime.myDecrypt(hres).toNullIfBlank()
            } ?: return emptyList()

            val titleOnly = obj.get("titleonly")?.asString?.takeIf { it.isNotBlank() } ?: bookTitle
            val itemsEl = obj.get("items")
            val items = when {
                itemsEl == null || itemsEl.isJsonNull -> null
                itemsEl.isJsonArray -> itemsEl.asJsonArray
                itemsEl.isJsonPrimitive -> JsonParser.parseString(itemsEl.asString).asJsonArray
                else -> null
            }
            if (items != null && items.size() > 0) {
                items.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val it = el.asJsonObject
                    val itemUrl = it.get("key")?.asString?.takeIf { k -> k.isNotBlank() }?.let { k ->
                        val slug = obj.get("slug")?.asString ?: ""
                        "$srv/b/$bid/$k/$slug.mp3"
                    } ?: audioUrl
                    AudioTrack(
                        title = it.get("title")?.asString ?: return@mapNotNull null,
                        url = itemUrl,
                        durationSeconds = durationSeconds(it.get("duration")),
                    )
                }
            } else {
                listOf(AudioTrack(title = titleOnly, url = audioUrl))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun durationSeconds(el: JsonElement?): Int? =
        el?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun stripAuthor(name: String, author: String): String =
        name.removePrefix("$author - ").removePrefix("$author -").trim()
}