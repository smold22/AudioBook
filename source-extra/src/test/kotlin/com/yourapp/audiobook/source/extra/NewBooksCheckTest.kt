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
 * Живая проверка страниц «Новинки» у всех источников, заявивших поддержку.
 * Печатает количество книг на 1-й и 2-й страницах.
 */
class NewBooksCheckTest {

    @Test
    fun checkNewBooks() = runBlocking {
        val sources: List<AudiobookSource> = listOf(
            SlushatKnigiSource(),
            PoleknigSource(),
            UkNigSource(),
            AudioknigaLifeSource(),
            BazaKnigSource(),
            BookishSource(),
            AudiomirSource(),
            AudioknigiTopSource(),
            AknigXyzSource(),
            TAudioknigiMp3Source(),
            AudioknigiFunSource(),
            AudioknigiOnlainSource(),
            AuthorTodaySource(),
        )
        val sem = Semaphore(4)
        val results = coroutineScope {
            sources.map { source ->
                async {
                    sem.withPermit { checkNew(source) }
                }
            }.awaitAll()
        }
        results.sortedBy { it.substringAfter(") ") }.forEach { println(it) }
    }

    private suspend fun checkNew(s: AudiobookSource): String {
        if (!s.supportsNew()) return "[SKIP  ] (${s.id}) не поддерживает новинки"
        val p1 = withTimeoutOrNull(30_000) { runCatching { s.newBooks(1) }.getOrNull() }
        if (p1 == null) return "[TIMEOUT] ${s.id} (${s.name}): newBooks(1) не ответил за 30с"
        if (p1.isEmpty()) {
            val p2 = withTimeoutOrNull(30_000) { runCatching { s.newBooks(2) }.getOrNull() }
            return "[EMPTY  ] ${s.id} (${s.name}): page1=0 page2=${p2?.size ?: "ERR"}"
        }
        val p2 = withTimeoutOrNull(30_000) { runCatching { s.newBooks(2) }.getOrNull() }
        val first = p1.first()
        return "[OK     ] ${s.id} (${s.name}): page1=${p1.size} page2=${p2?.size ?: "ERR"} " +
            "first=[${first.title}]"
    }
}