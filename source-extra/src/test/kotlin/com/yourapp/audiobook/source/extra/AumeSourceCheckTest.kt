package com.yourapp.audiobook.source.extra

import kotlinx.coroutines.runBlocking
import org.junit.Test

class AumeSourceCheckTest {

    private suspend fun ids(books: List<com.yourapp.audiobook.source.api.Book>): List<String> =
        books.map { it.id }

    @Test
    fun checkAllGenresPagination() = runBlocking {
        val s = AumeSource()
        var failed = 0
        for (genre in s.genres()) {
            val g1 = s.books(genre.url, 1)
            if (g1.isEmpty()) {
                println("GENRE ${genre.name} -> EMPTY page1")
                failed++
                continue
            }
            val g2 = s.books(genre.url, 2)
            val overlap = ids(g1).intersect(ids(g2).toSet()).size
            val far = s.books(genre.url, 9999)
            val ok = g2.isNotEmpty() && overlap == 0 && far.isEmpty()
            if (!ok) failed++
            println("GENRE ${genre.name}: p1=${g1.size} p2=${g2.size} overlap=$overlap far=${far.size} ${if (ok) "OK" else "FAIL"}")
        }
        println("RESULT: ${s.genres().size} genres, failed=$failed")
    }

    @Test
    fun checkFantasticGenre() = runBlocking {
        val s = AumeSource()
        val url = "https://aume.ru/fantastic"
        val seen = mutableSetOf<String>()
        var total = 0
        var page = 1
        while (true) {
            val books = s.books(url, page)
            if (books.isEmpty()) break
            val newCount = books.count { seen.add(it.id) }
            println("PAGE $page: ${books.size} books, new=$newCount")
            if (newCount != books.size) {
                println("GLOBAL DUPLICATES on page $page")
                break
            }
            total += books.size
            page++
            if (page > 400) { println("STOPPED at page $page (guard)"); break }
        }
        val lastPage = page - 1
        println("FANTASTIC pages=$lastPage total=$total unique=${seen.size}")
        println("count from site=${s.genreBookCount(url)}")
    }

    @Test
    fun checkPagination() = runBlocking {
        val s = AumeSource()
        val home1 = s.home(1)
        val home2 = s.home(2)
        println("HOME p1=${home1.size} p2=${home2.size} overlap=" +
            ids(home1).intersect(ids(home2).toSet()).size)

        val genre = s.genres().firstOrNull()
        println("GENRES count=${s.genres().size} first=${genre?.name} ${genre?.url}")
        if (genre != null) {
            val g1 = s.books(genre.url, 1)
            val g2 = s.books(genre.url, 2)
            val gEnd = s.books(genre.url, 9999)
            println("GENRE p1=${g1.size} p2=${g2.size} overlap=" +
                ids(g1).intersect(ids(g2).toSet()).size + " page9999=${gEnd.size}")
        }

        val q = "лекарь"
        val q1 = s.search(q, 1)
        val q2 = s.search(q, 2)
        println("SEARCH p1=${q1.size} p2=${q2.size} overlap=" +
            ids(q1).intersect(ids(q2).toSet()).size)

        val book = home1.firstOrNull()
        if (book != null) {
            val details = s.getBookDetails(book.url)
            println("DETAILS title=${details.book.title} tracks=${details.tracks.size} " +
                "author=${details.book.author} reader=${details.book.reader} " +
                "genre=${details.book.genre} related=${details.related.size}")
            details.tracks.take(3).forEach { println("  track: ${it.title} ${it.url.take(60)}") }
        }

        val withTracks = s.getBookDetails("https://aume.ru/22842-vtoroy-shans-dlya-lekarya.html")
        println("TRACKBOOK ${withTracks.book.title} tracks=${withTracks.tracks.size} " +
            "author=${withTracks.book.author} reader=${withTracks.book.reader} " +
            "duration=${withTracks.book.durationText}")
        withTracks.tracks.take(3).forEach { println("  track: ${it.title} ${it.url.take(60)}") }
    }
}