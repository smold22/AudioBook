package com.yourapp.audiobook.source.extra

import kotlinx.coroutines.runBlocking
import org.junit.Test

class Lis10BookCheckTest {

    @Test
    fun check() = runBlocking {
        val s = Lis10bookSource()
        val books = s.books("https://lis10book.com/genres/eve-online/", 1)
        println("жанр EVE: ${books.size}")
        val first = books.first()
        println("первая: ${first.title}")
        val d = s.getBookDetails(first.url)
        println("треков: ${d.tracks.size}")
        d.tracks.take(3).forEach { println("  ${it.title} | ${it.url.take(70)}") }
        val page2 = s.books("https://lis10book.com/genres/eve-online/", 2)
        println("страница 2: ${page2.size}")
        val home = s.home(2)
        println("домой стр.2: ${home.size}")
        val search = s.search("космос", 1)
        println("поиск: ${search.size} -> ${search.firstOrNull()?.title}")
        println("счётчик: ${s.genreBookCount("https://lis10book.com/genres/eve-online/")}")
        val seriesUrl = d.book.seriesUrl
        if (seriesUrl != null) {
            val series = s.seriesBooks(seriesUrl, 1)
            println("серия ${d.book.seriesTitle} (#${d.book.seriesIndex}): ${series.size}")
        }
    }
}