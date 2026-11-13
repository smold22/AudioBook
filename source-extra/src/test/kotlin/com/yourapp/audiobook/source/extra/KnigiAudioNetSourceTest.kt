package com.yourapp.audiobook.source.extra

import org.junit.Assert.assertEquals
import org.junit.Test

class KnigiAudioNetSourceTest {

    private val source = KnigiAudioNetSource()

    @Test
    fun baseUrlUsesWwwBecauseApexDoesNotResolve() {
        assertEquals("https://www.knigiaudio.net", source.baseUrl)
    }

    @Test
    fun legacyApexUrlsAreRewrittenToWww() {
        assertEquals(
            "https://www.knigiaudio.net/audio-1",
            source.urlForId("https://knigiaudio.net/audio-1"),
        )
        assertEquals(
            "https://www.knigiaudio.net/genre-3-fantastika-fentezi",
            source.urlForId("http://knigiaudio.net/genre-3-fantastika-fentezi"),
        )
    }

    @Test
    fun wwwUrlsAndRelativeIdsStayIntact() {
        assertEquals(
            "https://www.knigiaudio.net/audio-1",
            source.urlForId("https://www.knigiaudio.net/audio-1"),
        )
        assertEquals("https://www.knigiaudio.net/audio-1", source.urlForId("/audio-1"))
    }

    @Test
    fun unrelatedHostsAreNotTouched() {
        assertEquals(
            "https://knigiaudio.net.example.com/audio-1",
            source.urlForId("https://knigiaudio.net.example.com/audio-1"),
        )
    }
}
