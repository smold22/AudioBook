package com.yourapp.audiobook.source.extra

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Живая проверка источника «Аудиокнига-биз»: каталог, жанры, поиск, детали, треки. */
class AudioKnigaBizCheckTest {

    @Test
    fun check() = runBlocking {
        val s = AudioKnigaBizSource()
        val home = s.home(1)
        println("домой: ${home.size}")
        home.take(3).forEach {
            println("  ${it.title} | ${it.author} | ${it.reader} | ${it.genre} | ${it.durationText}")
            println("    ${it.url} обложка=${it.coverUrl}")
        }
        println("домой стр.2: ${s.home(2).size} -> ${s.home(2).firstOrNull()?.title}")
        println("домой стр.9999: ${s.home(9999).size}")

        val genres = s.genres()
        println("жанров: ${genres.size}")
        genres.take(5).forEach { println("  ${it.name} — ${it.bookCount} книг ${it.url}") }

        val genre = genres.first { it.name.contains("Фантаст") }
        println("книг в жанре «${genre.name}»: ${s.genreBookCount(genre.url)} (с сайта: ${genre.bookCount})")
        val genreBooks = s.books(genre.url, 2)
        println("жанр стр.2: ${genreBooks.size} -> ${genreBooks.firstOrNull()?.title}")
        println("жанр стр.9999: ${s.books(genre.url, 9999).size}")

        val search = s.search("хари", 1)
        println("поиск: ${search.size} -> ${search.firstOrNull()?.title}")
        println("поиск стр.2: ${s.search("хари", 2).size}")

        val first = home.first()
        val details = s.getBookDetails(first.url)
        println("детали: ${details.book.title}")
        println("  автор=${details.book.author} диктор=${details.book.reader} " +
            "жанр=${details.book.genre} длительность=${details.book.durationText}")
        println("  обложка=${details.book.coverUrl}")
        println("  цикл=${details.book.seriesTitle} (${details.book.seriesUrl})")
        println("  аннотация=${details.description?.take(120)}")
        println("  треков=${details.tracks.size}")
        details.tracks.forEach {
            println("    ${it.title} | ${it.durationSeconds ?: "?"}c | ${it.url}")
        }
    }
}
