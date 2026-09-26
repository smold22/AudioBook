package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudiobookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

/**
 * Живая проверка всех источников: home, детали книги, жанры, поиск.
 * Результаты печатаются в stdout теста.
 */
class AllSourcesCheckTest {

    @Test
    fun checkAllSources() = runBlocking {
        val sources: List<AudiobookSource> = listOf(
            KnigaVuheSource(),
            AknigaSource(),
            BazaKnigSource(),
            KnigobludSource(),
            PoleknigSource(),
            UkNigSource(),
            AudioknigiProSource(),
            GolosomSource(),
            BookishSource(),
            AudioknigiFunSource(),
            BookZvukSource(),
            Aknigi24Source(),
            Lis10bookSource(),
            AudioknigaOneSource(),
            AudiomirSource(),
            OtrubSource(),
            SlushatKnigiSource(),
            AudioknigaLifeSource(),
            AudioknigiTopSource(),
            AknigaComSource(),
            AuthorTodaySource(),
            RuKnigaMeSource(),
            TAudioknigiMp3Source(),
            AknigXyzSource(),
            KnigaAudioSource(),
            KnigiAudioNetSource(),
            AudioknigiOnlainSource(),
            AudiopolkaSource(),
            AudioKnigaBizSource(),
            MdsSource(),
        )
        val sem = Semaphore(4)
        val imageClient = buildClient()
        val results = coroutineScope {
            sources.map { source ->
                async {
                    sem.withPermit { checkSource(source, imageClient) }
                }
            }.awaitAll()
        }
        results.sortedBy { it.substringAfter("] ") }.forEach { println(it) }
    }

    private suspend fun checkSource(s: AudiobookSource, imageClient: OkHttpClient): String {
        val home = withTimeoutOrNull(30_000) { runCatching { s.home(1) }.getOrNull() }
        if (home == null) return "[TIMEOUT] ${s.id} (${s.name}): home не ответил за 30с"
        if (home.isEmpty()) {
            val genres = withTimeoutOrNull(20_000) { runCatching { s.genres() }.getOrNull() }
            return "[EMPTY  ] ${s.id} (${s.name}): home пуст, genres=${genres?.size ?: "ERR"}"
        }
        val first = home.first()
        val details = withTimeoutOrNull(30_000) {
            runCatching { s.getBookDetails(first.url) }.getOrNull()
        }
        val tracks = details?.tracks?.size ?: -1
        val torrent = details?.torrentUrl != null
        val genres = withTimeoutOrNull(20_000) { runCatching { s.genres() }.getOrNull() }
        val search = withTimeoutOrNull(20_000) { runCatching { s.search("приключения", 1) }.getOrNull() }
        val homeCoverUrl = home.asSequence()
            .mapNotNull { it.coverUrl?.toNullIfBlank() }
            .firstOrNull()
        val detailCoverUrl = details?.book?.coverUrl?.toNullIfBlank()
        val homeCover = probeCover(imageClient, homeCoverUrl)
        val detailCover = when {
            detailCoverUrl == null && details == null -> "DETAILS_ERR"
            detailCoverUrl == homeCoverUrl -> homeCover
            else -> probeCover(imageClient, detailCoverUrl)
        }
        return when {
            tracks >= 0 -> "[OK     ] ${s.id} (${s.name}): home=${home.size} tracks=$tracks" +
                (if (torrent) "+torrent" else "") +
                " genres=${genres?.size ?: "ERR"} search=${search?.size ?: "ERR"}" +
                " cover=list:$homeCover detail:$detailCover"
            else -> "[PARTIAL] ${s.id} (${s.name}): home=${home.size} tracks=ERR" +
                " genres=${genres?.size ?: "ERR"} search=${search?.size ?: "ERR"}" +
                " cover=list:$homeCover detail:DETAILS_ERR"
        }
    }

    private suspend fun probeCover(client: OkHttpClient, coverUrl: String?): String = when {
        coverUrl == null -> "NULL"
        coverUrl.startsWith("mdscover://") -> "LOCAL"
        else -> withTimeoutOrNull(25_000) {
            runCatching { fetchCoverProbe(client, coverUrl) }
                .getOrElse { "ERR:${it::class.simpleName}" }
        } ?: "TIMEOUT"
    }

    private suspend fun fetchCoverProbe(client: OkHttpClient, coverUrl: String): String =
        withContext(Dispatchers.IO) {
            val parsed = coverUrl.toHttpUrlOrNull() ?: return@withContext "INVALID_URL"
            val ref = parsed.queryParameter("ref")
            val requestUrl = parsed.newBuilder().removeAllQueryParameters("ref").build()
            val request = Request.Builder()
                .url(requestUrl)
                .apply { if (!ref.isNullOrBlank()) header("Referer", "https://$ref/") }
                .build()
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
