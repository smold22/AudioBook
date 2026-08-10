package com.yourapp.audiobook.ui

import android.app.Application
import com.yourapp.audiobook.source.api.Book

class SearchViewModel(app: Application) : BookListViewModel(app) {

    private var query: String = ""
    private var sourceId: String? = null

    fun search(newQuery: String) {
        query = newQuery.trim()
        refresh()
    }

    fun setSource(newSourceId: String?) {
        if (sourceId == newSourceId) return
        sourceId = newSourceId
        refresh()
    }

    fun selectedSourceId(): String? = sourceId

    override suspend fun currentSourceId(): String? = sourceId

    override suspend fun loadPage(page: Int): List<Book> {
        if (query.isBlank()) return emptyList()
        val target = sourceId
        return if (target == null) {
            appContext.searchAll(query, page)
        } else {
            appContext.sourceRegistry.get(target)?.search(query, page).orEmpty()
        }
    }
}
