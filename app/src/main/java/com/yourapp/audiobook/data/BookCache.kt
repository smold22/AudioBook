package com.yourapp.audiobook.data

import com.yourapp.audiobook.source.api.Book

class BookCache {

    private val map = LinkedHashMap<String, Book>()

    fun keyOf(book: Book): String = "${book.sourceId}:${book.id}"

    fun put(book: Book) {
        map[keyOf(book)] = book
    }

    fun get(key: String): Book? = map[key]
}