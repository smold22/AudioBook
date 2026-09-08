package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class HomeFeed(val label: String) {
    HOME("Главная"),
    NEW("Новинки"),
}

class HomeViewModel(app: Application) : BookListViewModel(app) {

    private val _feed = MutableStateFlow(HomeFeed.HOME)
    val feed: StateFlow<HomeFeed> = _feed.asStateFlow()

    private val _availableFeeds = MutableStateFlow(listOf(HomeFeed.HOME))
    val availableFeeds: StateFlow<List<HomeFeed>> = _availableFeeds.asStateFlow()

    fun setFeed(newFeed: HomeFeed) {
        if (newFeed == _feed.value) return
        _feed.value = newFeed
        refresh()
    }

    override suspend fun currentSourceId(): String? = appContext.activeSource()?.id

    override fun refreshForSource(sourceId: String?) {
        _feed.value = HomeFeed.HOME
        super.refreshForSource(sourceId)
        viewModelScope.launch {
            val source = appContext.activeSource()
            val feeds = buildList {
                add(HomeFeed.HOME)
                if (source?.supportsNew() == true) add(HomeFeed.NEW)
            }
            _availableFeeds.value = feeds
        }
    }

    override suspend fun loadPage(page: Int): List<Book> {
        val source = appContext.activeSource() ?: return emptyList()
        return when (_feed.value) {
            HomeFeed.HOME -> {
                val genre = appContext.settingsStore.homeGenre.first()
                if (genre != null && genre.sourceId == source.id) {
                    source.books(genre.url, page)
                        .map { if (it.genre == null) it.copy(genre = genre.name) else it }
                } else {
                    source.home(page)
                }
            }
            HomeFeed.NEW -> source.newBooks(page)
        }
    }
}
