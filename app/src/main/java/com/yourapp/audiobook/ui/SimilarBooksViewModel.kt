package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.source.api.Book

class SimilarBooksViewModel(
    app: Application,
    private val bookKey: String,
) : BookListViewModel(app) {

    override suspend fun loadPage(page: Int): List<Book> =
        if (page <= 1) appContext.similarBooks.similar(bookKey) else emptyList()
}

class SimilarBooksViewModelFactory(
    private val app: AudioBookApplication,
    private val bookKey: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SimilarBooksViewModel(app, bookKey) as T
}