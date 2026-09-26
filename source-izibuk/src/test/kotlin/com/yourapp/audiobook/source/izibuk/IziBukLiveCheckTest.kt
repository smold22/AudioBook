package com.yourapp.audiobook.source.izibuk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class IziBukLiveCheckTest {

    @Test
    fun checkLive() = runBlocking {
        val client = OkHttpClient()
        val s = IziBukSource(client)
        val home = withTimeoutOrNull(30_000) { runCatching { s.home(1) }.getOrNull() }
        println("HOME izibuk -> ${home?.size ?: "TIMEOUT"} books")
        val first = home?.firstOrNull()
        var detailsBookCover: String? = null
        if (first != null) {
            val details = withTimeoutOrNull(30_000) { runCatching { s.getBookDetails(first.url) }.getOrNull() }
            detailsBookCover = details?.book?.coverUrl
            println("DETAILS izibuk -> tracks=${details?.tracks?.size ?: "ERR"} title=${details?.book?.title ?: "ERR"}")
        }
        val coverUrl = home.orEmpty().asSequence()
            .mapNotNull { it.coverUrl?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            ?: detailsBookCover?.trim()?.takeIf(String::isNotEmpty)
        val cover = when {
            coverUrl == null -> "NULL"
            else -> withTimeoutOrNull(25_000) {
                runCatching { fetchCoverProbe(client, coverUrl) }
                    .getOrElse { "ERR:${it::class.simpleName}" }
            } ?: "TIMEOUT"
        }
        println("COVER izibuk -> $cover")
        val genres = withTimeoutOrNull(20_000) { runCatching { s.genres() }.getOrNull() }
        println("GENRES izibuk -> ${genres?.size ?: "ERR"}: ${genres?.take(5)?.joinToString { it.name }}")
        val search = withTimeoutOrNull(20_000) { runCatching { s.search("приключения", 1) }.getOrNull() }
        println("SEARCH izibuk -> ${search?.size ?: "ERR"} results")
    }

    private suspend fun fetchCoverProbe(client: OkHttpClient, coverUrl: String): String =
        withContext(Dispatchers.IO) {
            val parsed = coverUrl.toHttpUrlOrNull() ?: return@withContext "INVALID_URL"
            val request = Request.Builder().url(parsed).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "HTTP_${response.code}"
                val body = response.body ?: return@withContext "EMPTY_BODY"
                val contentType = body.contentType()?.toString() ?: "unknown"
                val prefix = body.byteStream().use { it.readNBytes(32) }
                if (prefix.isEmpty()) return@withContext "EMPTY_IMAGE"
                val format = imageFormat(prefix)
                    ?: return@withContext "NON_IMAGE:$contentType"
                "OK:$format:$contentType:${body.contentLength()}"
            }
        }

    private fun imageFormat(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "png"
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals("GIF8".toByteArray(Charsets.US_ASCII)) -> "gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) -> "webp"
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
        bytes.size >= 12 && bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII)) -> "heif"
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 1, 0)) -> "ico"
        else -> null
    }
}
