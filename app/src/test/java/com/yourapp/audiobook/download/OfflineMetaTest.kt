package com.yourapp.audiobook.download

import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.source.api.BookDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineMetaTest {

    private val details = BookDetails(
        book = Book(
            sourceId = "izibuk",
            id = "12345",
            title = "Тест: книга «№1»",
            url = "https://pda.izib.uk/art12345",
            coverUrl = "https://pda.izib.uk/img/covers/1.jpg",
            author = "Автор \"Проблема\"",
            reader = "Читает В.С.",
            durationText = "12 ч 30 мин",
            genre = "Фантастика",
        ),
        description = "Описание не сохраняется",
        tracks = listOf(
            AudioTrack("01 глава", "https://pda.izib.uk/12345/1.mp3", 3600),
            AudioTrack("02 глава", "https://pda.izib.uk/12345/2.mp3", null),
        ),
    )

    @Test
    fun roundTripPreservesBookAndTracks() {
        val decoded = OfflineMeta.decode(OfflineMeta.encode(details))
        assertNotNull(decoded)
        assertEquals(details.book, decoded!!.book)
        assertEquals(details.tracks, decoded.tracks)
    }

    @Test
    fun decodeHandlesAllFieldsPresentAndAbsent() {
        val minimal = BookDetails(
            book = Book(sourceId = "izibuk", id = "1", title = "Книга", url = "https://x/1"),
            tracks = emptyList(),
        )
        val decoded = OfflineMeta.decode(OfflineMeta.encode(minimal))
        assertNotNull(decoded)
        assertEquals(minimal.book, decoded!!.book)
        assertEquals(emptyList<AudioTrack>(), decoded.tracks)
    }

    @Test
    fun decodeReturnsNullOnCorruptInput() {
        assertNull(OfflineMeta.decode("not json"))
        assertNull(OfflineMeta.decode("{\"book\":{}}"))
    }
}
