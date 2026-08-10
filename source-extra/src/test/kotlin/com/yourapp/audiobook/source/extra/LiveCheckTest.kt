package com.yourapp.audiobook.source.extra

import kotlinx.coroutines.runBlocking
import org.junit.Test

class LiveCheckTest {

    @Test
    fun checkAll() = runBlocking {
        val sources = listOf(
            KnigaVuheSource(),
            AknigaSource(),
            BazaKnigSource(),
            KnigobludSource(),
            PoleknigSource(),
        )
        for (s in sources) {
            try {
                val books = s.home(1)
                if (books.isEmpty()) {
                    println("HOME ${s.id} -> EMPTY")
                    continue
                }
                var tracks = -1
                try {
                    tracks = s.getBookDetails(books.first().url).tracks.size
                } catch (e: Exception) {
                    tracks = -999
                }
                println("HOME ${s.id} [${s.name}] -> ${books.size} books, tracks=${if (tracks >= 0) tracks else "ERR"}")
            } catch (e: Exception) {
                println("HOME ${s.id} [${s.name}] -> ERROR: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }
    }
}