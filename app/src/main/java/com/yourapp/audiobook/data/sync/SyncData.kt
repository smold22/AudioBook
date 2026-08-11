package com.yourapp.audiobook.data.sync

import com.yourapp.audiobook.data.Bookmark
import com.yourapp.audiobook.data.HistoryEntry
import com.yourapp.audiobook.source.api.Book

/**
 * Содержимое документа синхронизации в Cloud Firestore.
 */
data class SyncData(
    val version: Int = 2,
    val updatedAtMs: Long = 0L,
    val favorites: List<Book> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val progress: Map<String, String> = emptyMap(),
    val bookmarks: Map<String, List<Bookmark>> = emptyMap(),
    val settings: Map<String, String> = emptyMap(),
) {

    /** Объединяет данные сервера с локальными: сервер приоритетнее, локальное добавляется, если отсутствует. */
    fun mergedWith(local: SyncData): SyncData {
        val remoteFavoriteKeys = favorites.map { it.bookKey }.toSet()
        val mergedFavorites = favorites +
            local.favorites.filterNot { it.bookKey in remoteFavoriteKeys }
        val mergedHistory = (history + local.history)
            .distinctBy { it.book.bookKey }
            .sortedByDescending { it.playedAtMs }
        val mergedProgress = progress + local.progress.filterKeys { it !in progress }
        val mergedBookmarks = (bookmarks.keys + local.bookmarks.keys).associateWith { key ->
            val remote = bookmarks[key].orEmpty()
            val remoteIds = remote.map { it.id }.toSet()
            remote + local.bookmarks[key].orEmpty().filterNot { it.id in remoteIds }
        }
        val mergedSettings = settings + local.settings.filterKeys { it !in settings }
        return SyncData(
            version = maxOf(version, local.version),
            updatedAtMs = System.currentTimeMillis(),
            favorites = mergedFavorites,
            history = mergedHistory,
            progress = mergedProgress,
            bookmarks = mergedBookmarks,
            settings = mergedSettings,
        )
    }

    private val Book.bookKey: String
        get() = "$sourceId:$id"
}
