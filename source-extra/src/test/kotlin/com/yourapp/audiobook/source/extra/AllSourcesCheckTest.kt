package com.yourapp.audiobook.source.extra

import com.yourapp.audiobook.source.api.AudiobookSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
            AudioLibSource(),
            AknigXyzSource(),
            KnigaAudioSource(),
            KnigiAudioNetSource(),
            AudioknigiOnlainSource(),
        )
        val sem = Semaphore(4)
        val results = coroutineScope {
            sources.map { source ->
                async {
                    sem.withPermit { checkSource(source) }
                }
            }.awaitAll()
        }
        results.sortedBy { it.substringAfter("] ") }.forEach { println(it) }
    }

    private suspend fun checkSource(s: AudiobookSource): String {
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
        return when {
            tracks >= 0 -> "[OK     ] ${s.id} (${s.name}): home=${home.size} tracks=$tracks" +
                (if (torrent) "+torrent" else "") +
                " genres=${genres?.size ?: "ERR"} search=${search?.size ?: "ERR"}"
            else -> "[PARTIAL] ${s.id} (${s.name}): home=${home.size} tracks=ERR" +
                " genres=${genres?.size ?: "ERR"} search=${search?.size ?: "ERR"}"
        }
    }
}
