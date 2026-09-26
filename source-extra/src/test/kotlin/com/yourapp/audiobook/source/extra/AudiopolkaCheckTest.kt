package com.yourapp.audiobook.source.extra

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Живая проверка источника «Аудиополка»: каталог, жанры, поиск, детали, треки. */
class AudiopolkaCheckTest {

    @Test
    fun check() = runBlocking {
        val s = AudiopolkaSource()
        val home = s.home(1)
        println("домой: ${home.size}")
        home.take(3).forEach {
            println("  ${it.title} | ${it.author} | ${it.reader} | ${it.genre} | ${it.durationText}")
            println("    обложка=${it.coverUrl}")
            println("    цикл=${it.seriesTitle} (${it.seriesUrl})")
        }

        val genres = s.genres()
        println("жанров: ${genres.size}")
        genres.forEach { println("  ${it.name} ${it.url}") }

        val genre = genres.first()
        println("книг в жанре «${genre.name}»: ${s.genreBookCount(genre.url)}")
        val genreBooks = s.books(genre.url, 2)
        println("жанр стр.2: ${genreBooks.size} -> ${genreBooks.firstOrNull()?.title}")
        println("жанр стр.9999: ${s.books(genre.url, 9999).size}")

        val search = s.search("приключения", 1)
        println("поиск: ${search.size} -> ${search.firstOrNull()?.title}")
        println("поиск стр.2: ${s.search("приключения", 2).size}")

        val first = home.first()
        val details = s.getBookDetails(first.url)
        println("детали: ${details.book.title}")
        println("  автор=${details.book.author} диктор=${details.book.reader} " +
            "жанр=${details.book.genre} длительность=${details.book.durationText}")
        println("  обложка=${details.book.coverUrl}")
        println("  аннотация=${details.description?.take(120)}")
        println("  треков=${details.tracks.size}")
        details.tracks.take(3).forEach {
            println("    ${it.title} | ${it.durationSeconds ?: "?"}c | ${it.url.take(90)}")
        }

        val seriesUrl = home.firstNotNullOfOrNull { it.seriesUrl }
        if (seriesUrl != null) {
            println("цикл ${home.first { it.seriesUrl == seriesUrl }.seriesTitle}: " +
                "${s.seriesBooks(seriesUrl, 1).size}")
            println("цикл стр.2: ${s.seriesBooks(seriesUrl, 2).size}")
        }
    }
}
