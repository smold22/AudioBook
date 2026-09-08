package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.source.api.Book

class GenreBooksViewModel(
    app: Application,
    private val genreUrl: String,
    private val genreName: String,
) : BookListViewModel(app) {

    override suspend fun loadPage(page: Int): List<Book> =
        appContext.activeSource()?.books(genreUrl, page).orEmpty()
            .map { if (it.genre == null) it.copy(genre = genreName) else it }

    override suspend fun currentSourceId(): String? = appContext.activeSource()?.id
}

class GenreBooksViewModelFactory(
    private val app: AudioBookApplication,
    private val genreUrl: String,
    private val genreName: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        GenreBooksViewModel(app, genreUrl, genreName) as T
}