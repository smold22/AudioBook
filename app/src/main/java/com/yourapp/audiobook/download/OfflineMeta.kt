package com.yourapp.audiobook.download

import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import org.json.JSONArray
import org.json.JSONObject

object OfflineMeta {

    fun encode(details: BookDetails): String {
        val book = details.book
        val title = JSONObject().apply {
            put("sourceId", book.sourceId)
            put("id", book.id)
            put("title", book.title)
            put("url", book.url)
            putOpt("coverUrl", book.coverUrl)
            putOpt("author", book.author)
            putOpt("reader", book.reader)
            putOpt("durationText", book.durationText)
            putOpt("genre", book.genre)
            putOpt("seriesTitle", book.seriesTitle)
            putOpt("seriesIndex", book.seriesIndex)
            putOpt("seriesUrl", book.seriesUrl)
        }
        val tracks = JSONArray()
        details.tracks.forEach { track ->
            tracks.put(
                JSONObject().apply {
                    put("title", track.title)
                    put("url", track.url)
                    putOpt("durationSeconds", track.durationSeconds)
                },
            )
        }
        return JSONObject()
            .put("book", title)
            .put("tracks", tracks)
            .toString()
    }

    fun decode(raw: String): BookDetails? = runCatching {
        val json = JSONObject(raw)
        val title = json.getJSONObject("book")
        val book = Book(
            sourceId = title.getString("sourceId"),
            id = title.getString("id"),
            title = title.getString("title"),
            url = title.getString("url"),
            coverUrl = title.optStringNullable("coverUrl"),
            author = title.optStringNullable("author"),
            reader = title.optStringNullable("reader"),
            durationText = title.optStringNullable("durationText"),
            genre = title.optStringNullable("genre"),
            seriesTitle = title.optStringNullable("seriesTitle"),
            seriesIndex = if (title.has("seriesIndex") && !title.isNull("seriesIndex")) {
                title.optInt("seriesIndex").takeIf { it > 0 }
            } else {
                null
            },
            seriesUrl = title.optStringNullable("seriesUrl"),
        )
        val tracksJson = json.getJSONArray("tracks")
        val tracks = (0 until tracksJson.length()).map { index ->
            val track = tracksJson.getJSONObject(index)
            AudioTrack(
                title = track.getString("title"),
                url = track.getString("url"),
                durationSeconds = if (track.has("durationSeconds") && !track.isNull("durationSeconds")) {
                    track.getInt("durationSeconds")
                } else {
                    null
                },
            )
        }
        BookDetails(book = book, tracks = tracks)
    }.getOrNull()

    private fun JSONObject.optStringNullable(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null
}