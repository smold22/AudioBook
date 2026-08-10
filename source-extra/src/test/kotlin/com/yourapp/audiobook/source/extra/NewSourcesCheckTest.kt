package com.yourapp.audiobook.source.extra

import kotlinx.coroutines.runBlocking
import org.junit.Test

class NewSourcesCheckTest {

    @Test
    fun checkAudioknigiPro() = runBlocking {
        val s = AudioknigiProSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size} books; first=${home.firstOrNull()?.title} | ${home.firstOrNull()?.url}")
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
    fun checkArchiveOrg() = runBlocking {
        val s = ArchiveOrgSource()
        try {
            val home = s.home(1)
            println("HOME ${s.id} -> ${home.size}; first=${home.firstOrNull()?.title}")
            if (home.isEmpty()) return@runBlocking
            val b = home.first()
            val details = s.getBookDetails(b.url)
            println("DETAILS tracks=${details.tracks.size} author=${details.book.author} title=${details.book.title}")
            details.tracks.take(2).forEach { println("  T: ${it.title} | ${it.url.take(80)}") }
            val search = s.search("Пикник", 1)
            println("SEARCH -> ${search.size}")
            val g = s.genres()
            println("GENRES -> ${g.size}")
            if (g.isNotEmpty()) {
                val gb = s.books(g.first().url, 1)
                println("GENRE BOOKS ${g.first().name} -> ${gb.size}")
            }
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }
}