package com.yourapp.audiobook.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.source.api.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HomeFeed(val label: String) {
    HOME("Главная"),
    NEW("Новинки"),
}

data class HomeCollections(
    val new: List<Book> = emptyList(),
)

class HomeViewModel(app: Application) : BookListViewModel(app) {

    private val _feed = MutableStateFlow(HomeFeed.HOME)
    val feed: StateFlow<HomeFeed> = _feed.asStateFlow()

    private val _availableFeeds = MutableStateFlow(listOf(HomeFeed.HOME))
    val availableFeeds: StateFlow<List<HomeFeed>> = _availableFeeds.asStateFlow()

    private val _collections = MutableStateFlow(HomeCollections())
    val collections: StateFlow<HomeCollections> = _collections.asStateFlow()

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
        refreshCollections()
    }

    fun refreshCollections() {
        viewModelScope.launch {
            val source = appContext.activeSource() ?: return@launch
            val new = if (source.supportsNew()) {
                runCatching { source.newBooks(1) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            new.forEach { appContext.bookCache.put(it) }
            _collections.value = HomeCollections(new = new)
        }
    }

    override suspend fun loadPage(page: Int): List<Book> {
        val source = appContext.activeSource() ?: return emptyList()
        return when (_feed.value) {
            HomeFeed.HOME -> source.home(page)
            HomeFeed.NEW -> source.newBooks(page)
        }
    }
}
