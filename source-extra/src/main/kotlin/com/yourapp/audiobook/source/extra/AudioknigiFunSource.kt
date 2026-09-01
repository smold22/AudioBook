package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.security.MessageDigest

class AudioknigiFunSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audioknigi_fun"
    override val name = "Audioknigi.Fun"
    override val baseUrl = "https://audioknigi.fun"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> =
        parseBooks(getHtml(client, if (page <= 1) baseUrl else "$baseUrl/page/$page/"))

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
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content").toNullIfBlank()
        val author = liValue(doc, "Автор")
        val reader = liValue(doc, "Исполнитель")
        val duration = liValue(doc, "Продолжительность")
        val genre = liValue(doc, "Жанр")
        val description = doc.selectFirst(".full-text")?.text().toNullIfBlank()
        val seriesChip = doc.select(".book-chip").firstOrNull { (it.selectFirst("b")?.text() ?: "").startsWith("Цикл") }
        val seriesLink = seriesChip?.selectFirst("a")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()

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
        return BookDetails(book = book, description = description, tracks = parseTracks(doc, title))
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = if (page <= 1) url else url.replace(Regex("""/page/\d+""")) { "/page/$page" }
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else "$seriesUrl?page=$page"
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        return doc.select("ul.nav-menu a[href*=/zhanr/]").mapNotNull { link ->
            val href = link.absUrl("href")
            if (!href.startsWith("$baseUrl/zhanr/")) return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = href)
        }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "article.card")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("article.card").mapNotNull { item ->
            val link = item.selectFirst(".card__title a") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val img = item.selectFirst(".card__img img")
            val cover = img?.attr("src")?.ifBlank { img.attr("data-src") }
                ?.let { if (it.startsWith("http")) it else baseUrl + it }.toNullIfBlank()
            val author = cardLi(item, "Автор")
            val reader = cardLi(item, "Исполнитель")
            val duration = cardLiText(item, "Продолжительность")
            val genre = cardLi(item, "Жанр")
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

    private fun cardLi(item: org.jsoup.nodes.Element, label: String): String? =
        item.select(".card__list li").firstOrNull { (it.selectFirst("span")?.text() ?: "").startsWith(label) }
            ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()

    private fun cardLiText(item: org.jsoup.nodes.Element, label: String): String? =
        item.select(".card__list li").firstOrNull { (it.selectFirst("span")?.text() ?: "").startsWith(label) }
            ?.ownText()?.trim().toNullIfBlank()

    private fun liValue(doc: org.jsoup.nodes.Document, label: String): String? =
        doc.select(".book-info-list li").firstOrNull { (it.selectFirst("span")?.text() ?: "").startsWith(label) }
            ?.select("a")?.eachText()?.joinToString(", ").toNullIfBlank()

    private fun parseTracks(doc: org.jsoup.nodes.Document, bookName: String): List<AudioTrack> {
        val player = doc.selectFirst("[data-ap-encoded]") ?: return emptyList()
        val encoded = player.attr("data-ap-encoded")
        val newsId = player.attr("data-ap-id")
        if (encoded.isBlank() || newsId.isBlank()) return emptyList()
        val decoded = ApDecoder.decode(encoded, newsId) ?: return emptyList()
        return parsePlaylist(decoded, bookName)
    }

    private fun parsePlaylist(decoded: String, bookName: String): List<AudioTrack> {
        try {
            val array = JsonParser.parseString(decoded).asJsonArray
            return array.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                val title = obj.get("title")?.asString ?: bookName
                val file = obj.get("file")?.asString ?: return@mapNotNull null
                AudioTrack(title = title, url = file)
            }
        } catch (_: Exception) {
        }
        val result = mutableListOf<AudioTrack>()
        try {
            val parts = decoded.split(Regex(""",\s*"title"\s*:"""))
            parts.forEachIndexed { index, part ->
                val chunk = if (index == 0) part else "\"title\":$part"
                val obj = JsonParser.parseString("{$chunk}").asJsonObject
                val title = obj.get("title")?.asString ?: bookName
                val file = obj.get("file")?.asString ?: return@forEachIndexed
                result += AudioTrack(title = title, url = file)
            }
        } catch (_: Exception) {
        }
        return result
    }
}

private object ApDecoder {
    private const val SALT = "KxOdv42AVmaq"
    private const val ALPHA = "f3RqmzK8TvNpLn0Xd7WhjYeA9QobsSl1HCgu5cyEDi4GBwrk2aFxJtMOPZUIV6+/="

    fun decode(encoded: String, newsId: String): String? {
        return try {
            val bytes = b64Decode(encoded)
            val key = makeKey(newsId, bytes.size)
            val out = ByteArray(bytes.size) { i -> (bytes[i] xor key[i]).toByte() }
            String(out, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun b64Decode(str: String): IntArray {
        val bytes = mutableListOf<Int>()
        var i = 0
        while (i < str.length) {
            val e1 = ALPHA.indexOf(str[i++])
            val e2 = if (i < str.length) ALPHA.indexOf(str[i++]) else -1
            val e3 = if (i < str.length) ALPHA.indexOf(str[i++]) else -1
            val e4 = if (i < str.length) ALPHA.indexOf(str[i++]) else -1
            if (e1 < 0 || e2 < 0) break
            bytes.add((e1 shl 2) or (e2 shr 4))
            if (e3 >= 0 && e3 != 64) bytes.add(((e2 and 15) shl 4) or (e3 shr 2))
            if (e4 >= 0 && e4 != 64) bytes.add(((e3 and 3) shl 6) or e4)
        }
        return bytes.toIntArray()
    }

    private fun makeKey(newsId: String, keyLen: Int): IntArray {
        val key = mutableListOf<Int>()
        var block = 0
        while (key.size < keyLen) {
            val digest = md5Hex("$newsId:$SALT:$block")
            var i = 0
            while (i < digest.length && key.size < keyLen) {
                key.add(digest.substring(i, i + 2).toInt(16))
                i += 2
            }
            block++
        }
        return key.toIntArray()
    }

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
