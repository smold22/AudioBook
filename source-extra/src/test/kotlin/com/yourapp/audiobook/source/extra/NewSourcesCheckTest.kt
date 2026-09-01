package com.yourapp.audiobook.source.extra

import kotlinx.coroutines.runBlocking
import org.junit.Test

class NewSourcesCheckTest {

    @Test
    fun checkAudioknigiPro() = runBlocking {
        val s = AudioknigiProSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS tracks=${details.tracks.size} author=${details.book.author} reader=${details.book.reader} genre=${details.book.genre}")
            details.tracks.firstOrNull()?.let { println("TRACK0: ${it.title} | ${it.url.take(80)}") }
            val search = s.search("Роберт", 1)
            println("SEARCH -> ${search.size} results")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkAudioknigaLife() = runBlocking {
        val s = AudioknigaLifeSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS tracks=${details.tracks.size} author=${details.book.author} reader=${details.book.reader} genre=${details.book.genre}")
            details.tracks.firstOrNull()?.let { println("TRACK0: ${it.title} | ${it.url.take(80)}") }
            val search = s.search("Война", 1)
            println("SEARCH -> ${search.size} results")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkAudioknigiTop() = runBlocking {
        val s = AudioknigiTopSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS tracks=${details.tracks.size} author=${details.book.author} reader=${details.book.reader} genre=${details.book.genre}")
            details.tracks.firstOrNull()?.let { println("TRACK0: ${it.title} | ${it.url.take(80)}") }
            val search = s.search("Война", 1)
            println("SEARCH -> ${search.size} results")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkAknigaCom() = runBlocking {
        val s = AknigaComSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS tracks=${details.tracks.size} author=${details.book.author} reader=${details.book.reader} genre=${details.book.genre}")
            details.tracks.firstOrNull()?.let { println("TRACK0: ${it.title} | ${it.url.take(80)}") }
            val search = s.search("Булгаков", 1)
            println("SEARCH -> ${search.size} results")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkAuthorToday() = runBlocking {
        val s = AuthorTodaySource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS tracks=${details.tracks.size} author=${details.book.author} reader=${details.book.reader} genre=${details.book.genre} series=${details.book.seriesTitle}")
            details.tracks.take(2).forEach { println("  T: ${it.title} | ${it.url.take(80)}") }
            val search = s.search("гидраргирум", 1)
            println("SEARCH -> ${search.size} results")
            val withSeries = home.firstOrNull { !it.seriesUrl.isNullOrBlank() }
            println("HOME book with series: ${withSeries?.title} | series=${withSeries?.seriesTitle} | ${withSeries?.seriesUrl}")
            val seriesUrl = withSeries?.seriesUrl
            if (seriesUrl != null) {
                val sb = s.seriesBooks(seriesUrl, 1)
                println("SERIES BOOKS -> ${sb.size}: ${sb.joinToString { "${it.title}#${it.seriesIndex}" }}")
                withSeries?.let { wb ->
                    val sd = s.getBookDetails(wb.url)
                    println("SERIES DETAILS book.seriesUrl=${sd.book.seriesUrl} seriesTitle=${sd.book.seriesTitle} seriesIndex=${sd.book.seriesIndex} inline=${sd.seriesBooks.size}")
                }
            } else {
                println("SERIES BOOKS -> no series book on home p1")
            }
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkAudioLib() = runBlocking {
        val s = AudioLibSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS title=${details.book.title} author=${details.book.author} tracks=${details.tracks.size}")
            details.tracks.take(2).forEach { println("  T: ${it.title} | ${it.url.take(90)}") }
            val search = s.search("Шантарам", 1)
            println("SEARCH -> ${search.size}")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkAknigXyz() = runBlocking {
        val s = AknigXyzSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS title=${details.book.title} author=${details.book.author} reader=${details.book.reader} tracks=${details.tracks.size}")
            details.tracks.take(2).forEach { println("  T: ${it.title} | ${it.url.take(90)}") }
            val search = s.search("магия", 1)
            println("SEARCH -> ${search.size}")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkKnigaAudio() = runBlocking {
        val s = KnigaAudioSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS title=${details.book.title} author=${details.book.author} reader=${details.book.reader} tracks=${details.tracks.size}")
            details.tracks.take(2).forEach { println("  T: ${it.title} | ${it.url.take(90)}") }
            val search = s.search("Ангелы", 1)
            println("SEARCH -> ${search.size}")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkKnigiAudioNet() = runBlocking {
        val s = KnigiAudioNetSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS title=${details.book.title} author=${details.book.author} reader=${details.book.reader} dur=${details.book.durationText} tracks=${details.tracks.size}")
            details.tracks.take(2).forEach { println("  T: ${it.title} | ${it.url.take(90)}") }
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkAudioknigiOnlain() = runBlocking {
        val s = AudioknigiOnlainSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | cover=${home.firstOrNull()?.coverUrl} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS title=${details.book.title} author=${details.book.author} reader=${details.book.reader} tracks=${details.tracks.size}")
            details.tracks.take(2).forEach { println("  T: ${it.title} | ${it.url.take(90)}") }
            val search = s.search("Толкиен", 1)
            println("SEARCH -> ${search.size}")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                println("COUNT ${g.first().name} -> ${s.genreBookCount(g.first().url)}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkRuKnigaMe() = runBlocking {
        val s = RuKnigaMeSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | author=${home.firstOrNull()?.author} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS title=${details.book.title} author=${details.book.author} reader=${details.book.reader} genre=${details.book.genre}")
            println("DETAILS torrent=${details.torrentUrl}")
            details.torrentUrl?.let { torrent ->
                val bytes = s.fetchTorrentBytes(torrent)
                println("TORRENT BYTES -> ${bytes?.size} bytes")
            }
            val search = s.search("Живучий", 1)
            println("SEARCH -> ${search.size} results")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                val gb2 = s.books(g.first().url, 2)
                println("GENRE BOOKS p2 -> ${gb2.size}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    @Test
    fun checkTAudioknigiMp3() = runBlocking {
        val s = TAudioknigiMp3Source()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | author=${home.firstOrNull()?.author} | ${home.firstOrNull()?.url}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS title=${details.book.title} author=${details.book.author} reader=${details.book.reader} genre=${details.book.genre} dur=${details.book.durationText}")
            println("DETAILS torrent=${details.torrentUrl}")
            details.torrentUrl?.let { torrent ->
                val bytes = s.fetchTorrentBytes(torrent)
                println("TORRENT BYTES -> ${bytes?.size} bytes")
            }
            val search = s.search("Корнев", 1)
            println("SEARCH -> ${search.size} results")
            val g = s.genres()
            println("GENRES -> ${g.size}: ${g.take(5).joinToString { it.name }}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
                val gb2 = s.books(g.first().url, 2)
                println("GENRE BOOKS p2 -> ${gb2.size}")
            }
            val page2 = s.home(2)
            println("HOME p2 -> ${page2.size} books")
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }
}