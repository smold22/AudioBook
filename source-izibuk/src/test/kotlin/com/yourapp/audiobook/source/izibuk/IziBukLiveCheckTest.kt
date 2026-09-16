package com.yourapp.audiobook.source.izibuk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

class IziBukLiveCheckTest {

    @Test
    fun checkLive() = runBlocking {
        val s = IziBukSource()
        val home = withTimeoutOrNull(30_000) { runCatching { s.home(1) }.getOrNull() }
        println("HOME izibuk -> ${home?.size ?: "TIMEOUT"} books")
        val first = home?.firstOrNull()
        if (first != null) {
            val details = withTimeoutOrNull(30_000) { runCatching { s.getBookDetails(first.url) }.getOrNull() }
            println("DETAILS izibuk -> tracks=${details?.tracks?.size ?: "ERR"} title=${details?.book?.title ?: "ERR"}")
        }
        val genres = withTimeoutOrNull(20_000) { runCatching { s.genres() }.getOrNull() }
        println("GENRES izibuk -> ${genres?.size ?: "ERR"}: ${genres?.take(5)?.joinToString { it.name }}")
        val search = withTimeoutOrNull(20_000) { runCatching { s.search("приключения", 1) }.getOrNull() }
        println("SEARCH izibuk -> ${search?.size ?: "ERR"} results")
    }
}
