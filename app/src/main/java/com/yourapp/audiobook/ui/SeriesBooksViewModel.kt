package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.source.api.Book

class SeriesBooksViewModel(
    app: Application,
    private val sourceId: String,
    private val seriesUrl: String,
) : BookListViewModel(app) {

    override suspend fun loadPage(page: Int): List<Book> {
        val source = appContext.sourceRegistry.get(sourceId) ?: return emptyList()
        return source.seriesBooks(seriesUrl, page)
    }

    override suspend fun currentSourceId(): String? = sourceId
}

class SeriesBooksViewModelFactory(
    private val app: AudioBookApplication,
    private val sourceId: String,
    private val seriesUrl: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SeriesBooksViewModel(app, sourceId, seriesUrl) as T
}
