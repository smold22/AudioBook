package com.yourapp.audiobook.source.api

data class Book(
    val sourceId: String,
    val id: String,
    val title: String,
    val url: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val reader: String? = null,
    val durationText: String? = null,
    val genre: String? = null,
    val seriesTitle: String? = null,
    val seriesIndex: Int? = null,
    val seriesUrl: String? = null,
)

data class AudioTrack(
    val title: String,
    val url: String,
    val durationSeconds: Int? = null,
)

data class BookDetails(
    val book: Book,
    val description: String? = null,
    val tracks: List<AudioTrack> = emptyList(),
    val related: List<Book> = emptyList(),
    val seriesBooks: List<Book> = emptyList(),
)

data class Genre(
    val name: String,
    val url: String,
    val bookCount: Long? = null,
)

interface AudiobookSource {
    val id: String
    val name: String
    val baseUrl: String

    suspend fun home(page: Int): List<Book>

    suspend fun search(query: String, page: Int): List<Book>

    suspend fun getBookDetails(url: String): BookDetails

    suspend fun genres(): List<Genre>

    suspend fun genreBookCount(url: String): Long? = null

    suspend fun books(url: String, page: Int): List<Book>

    suspend fun newBooks(page: Int): List<Book> = emptyList()

    fun supportsNew(): Boolean = false

    suspend fun seriesBooks(seriesUrl: String, page: Int): List<Book> = emptyList()

    fun supportsSeries(): Boolean = false

    fun urlForId(id: String): String
}

class SourceRegistry {
    private val _sources = mutableListOf<AudiobookSource>()

    val sources: List<AudiobookSource>
        get() = _sources.toList()

    fun register(source: AudiobookSource) {
        if (_sources.none { it.id == source.id }) {
            _sources.add(source)
        }
    }

    fun get(id: String): AudiobookSource? = _sources.firstOrNull { it.id == id }

    fun default(): AudiobookSource? = _sources.firstOrNull()
}
