package com.yourapp.audiobook.source.extra

import com.google.gson.JsonParser
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.AudiobookSource
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import com.yourapp.audiobook.source.api.Genre
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest

class AudioknigiProSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "audioknigi_pro"
    override val name = "Аудиокниги PRO"
    override val baseUrl = "https://audioknigi.pro"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else baseUrl + bookId

    override suspend fun home(page: Int): List<Book> {
        val url = if (page <= 1) baseUrl else "$baseUrl/page/$page/"
        return parseBooks(getHtml(client, url))
    }

    override suspend fun search(query: String, page: Int): List<Book> {
        val html = postForm(
            client = client,
            url = "$baseUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "search_start" to (page - 1).toString(),
                "full_search" to "0",
                "story" to query,
            ),
            referer = baseUrl,
        )
        return parseBooks(html)
    }

    override suspend fun getBookDetails(bookUrl: String): BookDetails {
        val html = getHtml(client, bookUrl)
        if (html.contains("Just a moment")) {
            throw java.io.IOException("Защита от ботов (Cloudflare)")
        }
        val doc = Jsoup.parse(html, bookUrl)

        val newsId = Regex("""data-ap-id="(\d+)"""").find(html)?.groupValues?.get(1)
        val encoded = Regex("""data-ap-encoded="([^"]+)"""").find(html)?.groupValues?.get(1)
        val tracks = buildList {
            val raw = if (encoded != null && newsId != null) {
                runCatching { AkPlayerDecoder.decode(encoded, newsId) }.getOrNull()
            } else null
            if (raw != null) addAll(parseFlatPlaylist(raw))
        }

        val title = doc.selectFirst("h1")?.ownText()?.trim().toNullIfBlank()
            ?: doc.selectFirst("[data-title]")?.attr("data-title").toNullIfBlank()
            ?: ""
        val cover = doc.selectFirst("#vk-player-inline")?.attr("data-cover").toNullIfBlank()
        val description = doc.selectFirst(".story-short, .short-text")?.text().toNullIfBlank()
        val author = doc.selectFirst("[data-schema-author] a")?.text().toNullIfBlank()
        val reader = doc.selectFirst("[data-schema-readby] a")?.text().toNullIfBlank()
        val genre = doc.selectFirst("[data-schema-genre]")?.text().toNullIfBlank()
        val durationText = doc.selectFirst(".time.js-duration, .js-duration")?.text().toNullIfBlank()
        val seriesSpan = doc.selectFirst("[data-schema-series]")
        val seriesLink = seriesSpan?.selectFirst("a")
        val seriesTitle = seriesLink?.text().toNullIfBlank()
        val seriesUrl = seriesLink?.absUrl("href").toNullIfBlank()
        val seriesIndex = seriesSpan?.ownText()?.substringAfter("(#", "")?.substringBefore(")")?.toIntOrNull()
            ?.takeIf { it > 0 }

        val book = Book(
            sourceId = id,
            id = bookUrl,
            title = title,
            url = bookUrl,
            coverUrl = cover,
            author = author,
            reader = reader,
            durationText = durationText,
            genre = genre,
            seriesTitle = seriesTitle,
            seriesIndex = seriesIndex,
            seriesUrl = seriesUrl,
        )
        return BookDetails(book = book, description = description, tracks = tracks)
    }

    override suspend fun books(url: String, page: Int): List<Book> {
        val target = when {
            page <= 1 -> url
            Regex("""/page/\d+/""").containsMatchIn(url) ->
                url.replace(Regex("""/page/\d+/""")) { "/page/$page/" }
            else -> url.trimEnd('/') + "/page/$page/"
        }
        return parseBooks(getHtml(client, target))
    }

    override fun supportsSeries(): Boolean = true

    override suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> {
        val target = if (page <= 1) seriesUrl else "$seriesUrl?cstart=${(page - 1) * 30}"
        return parseBooks(getHtml(client, target))
    }

    override suspend fun genres(): List<Genre> {
        val doc = Jsoup.parse(getHtml(client, baseUrl), baseUrl)
        return doc.select("a[href*='/zhanr/']").mapNotNull { link ->
            val href = link.absUrl("href").substringBefore("?")
            if (!href.contains("/zhanr/")) return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Genre(name = name, url = href)
        }.distinctBy { it.url }
    }

    override suspend fun genreBookCount(url: String): Long? =
        estimateCount(client, url, "div.short")

    private fun parseBooks(html: String): List<Book> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.short").mapNotNull { item ->
            val link = item.selectFirst("a.name-kniga") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = item.selectFirst("img")?.attr("data-src")?.let {
                if (it.startsWith("http")) it else baseUrl + it
            }.toNullIfBlank()
            val seriesSpan = item.selectFirst(".cikl.meta-cycle, .meta-cycle")
            val seriesLink = seriesSpan?.selectFirst("a")
            Book(
                sourceId = id,
                id = href,
                title = title,
                url = href,
                coverUrl = cover,
                author = item.selectFirst(".author")?.text().toNullIfBlank(),
                reader = item.selectFirst(".reader")?.text().toNullIfBlank(),
                durationText = item.selectFirst(".time")?.text().toNullIfBlank(),
                genre = item.selectFirst(".zhanr")?.text().toNullIfBlank(),
                seriesTitle = seriesLink?.text().toNullIfBlank(),
                seriesIndex = seriesSpan?.ownText()?.substringAfter("(#", "")?.substringBefore(")")?.toIntOrNull()
                    ?.takeIf { it > 0 },
                seriesUrl = seriesLink?.absUrl("href").toNullIfBlank(),
            )
        }
    }

    private fun parseFlatPlaylist(raw: String): List<AudioTrack> {
        val cleaned = raw
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .trim()
        if (cleaned.startsWith("[")) {
            return try {
                val arr = JsonParser.parseString(cleaned).asJsonArray
                arr.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val obj = el.asJsonObject
                    val file = obj.get("file")?.asString ?: return@mapNotNull null
                    AudioTrack(title = obj.get("title")?.asString ?: "", url = file)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        val pair = Regex("""(?:"title"\s*:\s*"([^"]*)",\s*"file"\s*:\s*"([^"]*)")""")
        return pair.findAll(cleaned).mapNotNull { m ->
            val t = m.groupValues[1]
            val f = m.groupValues[2]
            if (f.isBlank()) null else AudioTrack(title = t, url = f)
        }.toList()
    }
}

/**
 * Декодер плей-листа audioknigi.pro: XOR шифр, ключ MD5(newsId:SALT:block),
 * кастомный Base64-алфавит. Порт decoder.js из шаблона сайта.
 */
private object AkPlayerDecoder {
    private const val SALT = "KxOdv42AVmaq"
    private const val ALPHA = "f3RqmzK8TvNpLn0Xd7WhjYeA9QobsSl1HCgu5cyEDi4GBwrk2aFxJtMOPZUIV6+/="
    private const val PAD_INDEX = ALPHA.length - 1

    fun decode(encoded: String, newsId: String): String {
        val bytes = b64decode(encoded)
        val key = makeKey(newsId, bytes.size)
        val xored = ByteArray(bytes.size)
        for (i in bytes.indices) xored[i] = (bytes[i].toInt() xor key[i].toInt()).toByte()
        return String(xored, Charsets.UTF_8)
    }

    private fun b64decode(str: String): ByteArray {
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < str.length) {
            val e1 = ALPHA.indexOf(str[i]).takeIf { it >= 0 } ?: break
            val e2 = ALPHA.indexOf(alphaChar(str, i + 1)).takeIf { it >= 0 } ?: break
            val e3 = ALPHA.indexOf(alphaChar(str, i + 2))
            val e4 = ALPHA.indexOf(alphaChar(str, i + 3))
            out.add(((e1 shl 2) or (e2 shr 4)).toByte())
            if (e3 >= 0 && e3 != PAD_INDEX) out.add((((e2 and 15) shl 4) or (e3 shr 2)).toByte())
            if (e4 >= 0 && e4 != PAD_INDEX) out.add((((e3 and 3) shl 6) or e4).toByte())
            i += 4
        }
        return out.toByteArray()
    }

    private fun alphaChar(str: String, index: Int): Char =
        if (index < str.length) str[index] else '='

    private fun makeKey(newsId: String, keyLen: Int): ByteArray {
        val key = mutableListOf<Byte>()
        var block = 0
        while (key.size < keyLen) {
            val seed = "$newsId:$SALT:$block".toByteArray(Charsets.UTF_8)
            val hex = MessageDigest.getInstance("MD5").digest(seed).toHex()
            var i = 0
            while (i < hex.length && key.size < keyLen) {
                key.add(Integer.valueOf(hex.substring(i, i + 2), 16).toByte())
                i += 2
            }
            block++
        }
        return key.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}