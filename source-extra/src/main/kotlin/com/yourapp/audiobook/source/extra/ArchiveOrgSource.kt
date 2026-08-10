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
import java.net.URLEncoder

class ArchiveOrgSource(
    private val client: OkHttpClient = buildClient(),
) : AudiobookSource {

    override val id = "archive_org"
    override val name = "Archive.org"
    override val baseUrl = "https://archive.org"

    private val langFilter = "mediatype:(audio) AND language:(rus OR russian)"

    override fun urlForId(bookId: String): String =
        if (bookId.startsWith("http")) bookId else "$baseUrl/details/$bookId"

    override suspend fun home(page: Int): List<Book> =
        searchItems("$langFilter AND format:(MP3)", page, sort = "date desc")

    override suspend fun search(query: String, page: Int): List<Book> =
        searchItems("(${query}) AND $langFilter", page, sort = null)

    override suspend fun books(url: String, page: Int): List<Book> {
        val prefix = "$baseUrl/ia-search/"
        if (!url.startsWith(prefix)) return emptyList()
        val raw = java.net.URLDecoder.decode(url.removePrefix(prefix), "UTF-8")
        return searchItems("($raw) AND $langFilter", page, sort = "downloads desc")
    }

    override suspend fun genres(): List<Genre> = listOf(
        Genre("Детективы и триллеры", "$baseUrl/ia-search/" + enc("subject:(детектив OR триллер)")),
        Genre("Фантастика", "$baseUrl/ia-search/" + enc("subject:(фантастика OR sf)")),
        Genre("Фэнтези", "$baseUrl/ia-search/" + enc("subject:(фэнтези OR fantasy)")),
        Genre("Классика", "$baseUrl/ia-search/" + enc("subject:(классика OR классическая)")),
        Genre("Историческая проза", "$baseUrl/ia-search/" + enc("subject:(исторический OR история)")),
        Genre("Приключения", "$baseUrl/ia-search/" + enc("subject:(приключения)")),
        Genre("Психология и философия", "$baseUrl/ia-search/" + enc("subject:(психология OR философия)")),
        Genre("Русская классика — аудиокниги", "$baseUrl/ia-search/" + enc("subject:(русская литература OR pushkin OR tolstoy OR dostoevsky)")),
    )

    override suspend fun getBookDetails(bookUrl: String): BookDetails {
        val identifier = Regex("""/details/([^/?]+)""").find(bookUrl)?.groupValues?.get(1)
            ?: throw java.io.IOException("Неверный URL книги: $bookUrl")
        val json = getHtml(client, "$baseUrl/metadata/$identifier")
        val root = JsonParser.parseString(json).asJsonObject
        val meta = root.getAsJsonObject("metadata")
        val title = meta.get("title")?.takeIf { it.isJsonPrimitive }?.asString.toNullIfBlank()
            ?: identifier
        val author = firstString(meta, "creator")
        val reader = null
        val genre = firstString(meta, "subject")
        val descriptionHtml = meta.get("description")?.takeIf { it.isJsonPrimitive }?.asString
        val description = descriptionHtml?.let {
            Jsoup.parseBodyFragment(it).text().trim().takeIf { s -> s.isNotBlank() }
        }

        val tracks = mutableListOf<AudioTrack>()
        val files = root.getAsJsonArray("files")
        files?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val obj = el.asJsonObject
            val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            if (name.endsWith("_files.xml") || name.endsWith("_meta.xml") || name.endsWith("_archive.torrent") ||
                name.endsWith(".sqlite") || name.endsWith(".txt") || name.endsWith(".jpg") ||
                name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".m3u")
            ) return@forEach
            val format = obj.get("format")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (!isAudioFormat(format)) return@forEach
            tracks += AudioTrack(
                title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString.toNullIfBlank() ?: name,
                url = "$baseUrl/download/$identifier/" + name,
            )
        }

        val book = Book(
            sourceId = id,
            id = "$baseUrl/details/$identifier",
            title = title,
            url = "$baseUrl/details/$identifier",
            coverUrl = "$baseUrl/services/img/$identifier",
            author = author,
            reader = reader,
            durationText = null,
            genre = genre,
        )
        return BookDetails(book = book, description = description, tracks = tracks)
    }

    override suspend fun genreBookCount(url: String): Long? {
        val prefix = "$baseUrl/ia-search/"
        if (!url.startsWith(prefix)) return null
        val raw = try {
            java.net.URLDecoder.decode(url.removePrefix(prefix), "UTF-8")
        } catch (_: Exception) {
            return null
        }
        val query = "($raw) AND $langFilter"
        val apiUrl = "$baseUrl/advancedsearch.php?q=${URLEncoder.encode(query, "UTF-8")}&rows=0&output=json"
        return try {
            val json = getHtml(client, apiUrl)
            JsonParser.parseString(json).asJsonObject
                .getAsJsonObject("response")
                .get("numFound")?.takeIf { it.isJsonPrimitive }?.asLong
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun searchItems(query: String, page: Int, sort: String?): List<Book> {
        val fl = "identifier,title,creator,subject"
        val sortParam = sort?.let { "&sort%5B%5D=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val url = "$baseUrl/advancedsearch.php?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&fl%5B%5D=$fl&rows=50&page=$page&output=json$sortParam"
        val json = getHtml(client, url)
        val docs = JsonParser.parseString(json).asJsonObject
            .getAsJsonObject("response")
            .getAsJsonArray("docs")
        return docs.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject
            val identifier = obj.get("identifier")?.takeIf { it.isJsonPrimitive }?.asString.toNullIfBlank()
                ?: return@mapNotNull null
            val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString.toNullIfBlank()
                ?: identifier
            Book(
                sourceId = id,
                id = "$baseUrl/details/$identifier",
                title = title,
                url = "$baseUrl/details/$identifier",
                coverUrl = "$baseUrl/services/img/$identifier",
                author = firstString(obj, "creator"),
                reader = null,
                durationText = null,
                genre = firstString(obj, "subject"),
            )
        }
    }

    private fun firstString(obj: JsonElement, field: String): String? {
        if (!obj.isJsonObject) return null
        val el = obj.asJsonObject.get(field) ?: return null
        return when {
            el.isJsonArray -> el.asJsonArray.firstOrNull { it.isJsonPrimitive }?.takeIf { it.isJsonPrimitive }?.asString
            el.isJsonPrimitive -> el.asString
            else -> null
        }.toNullIfBlank()
    }

    private fun isAudioFormat(format: String): Boolean {
        val f = format.lowercase()
        return f.contains("mp3") || f.contains("ogg") || f.contains("vorbis") ||
            f.contains("flac") || f.contains("wav") || f.contains("3gp")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}