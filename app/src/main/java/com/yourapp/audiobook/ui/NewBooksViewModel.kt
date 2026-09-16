package com.yourapp.audiobook.ui

import android.app.Application
import com.yourapp.audiobook.source.api.Book

class NewBooksViewModel(app: Application) : BookListViewModel(app) {
    override suspend fun loadPage(page: Int): List<Book> =
        appContext.newBooksAll(page).distinctBy { "${it.sourceId}:${it.id}" }
}