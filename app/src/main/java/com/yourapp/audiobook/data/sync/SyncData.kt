package com.yourapp.audiobook.data.sync

import com.yourapp.audiobook.data.Bookmark
import com.yourapp.audiobook.data.HistoryEntry
import com.yourapp.audiobook.source.api.Book

/**
 * Содержимое документа синхронизации в Cloud Firestore.
 * [updatedAtMs] — время последнего изменения данных (метка пишущей стороны),
 * используется для выбора актуальной копии при слиянии (last-write-wins).
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

    /**
     * Объединяет данные, сохраняя записи обеих сторон.
     * Используется, когда метки времени неизвестны или победившая сторона
     * не должна терять записи, добавленные на другом устройстве.
     * Прогресс объединяется по треку с меткой времени каждой записи (newest-wins),
     * чтобы позиция прослушивания не откатывалась.
     */
    fun unionWith(other: SyncData): SyncData {
        val remote = this
        val local = other
        val remoteFavoriteKeys = remote.favorites.map { it.bookKey }.toSet()
        val mergedFavorites = remote.favorites +
            local.favorites.filterNot { it.bookKey in remoteFavoriteKeys }
        val mergedHistory = (remote.history + local.history)
            .distinctBy { it.book.bookKey }
            .sortedByDescending { it.playedAtMs }
        val mergedProgress = mergeProgress(remote.progress, local.progress)
        val mergedBookmarks = (remote.bookmarks.keys + local.bookmarks.keys).associateWith { key ->
            val remoteList = remote.bookmarks[key].orEmpty()
            val remoteIds = remoteList.map { it.id }.toSet()
            remoteList + local.bookmarks[key].orEmpty().filterNot { it.id in remoteIds }
        }
        val mergedSettings = remote.settings + local.settings.filterKeys { it !in remote.settings }
        return SyncData(
            version = maxOf(remote.version, local.version),
            updatedAtMs = maxOf(remote.updatedAtMs, local.updatedAtMs),
            favorites = mergedFavorites,
            history = mergedHistory,
            progress = mergedProgress,
            bookmarks = mergedBookmarks,
            settings = mergedSettings,
        )
    }

    private fun mergeProgress(remote: Map<String, String>, local: Map<String, String>): Map<String, String> {
        val result = remote.toMutableMap()
        local.forEach { (key, localValue) ->
            val remoteValue = result[key]
            if (remoteValue == null || entryTimestamp(localValue) > entryTimestamp(remoteValue)) {
                result[key] = localValue
            }
        }
        return result
    }

    private fun entryTimestamp(value: String): Long =
        value.split(";").getOrNull(2)?.toLongOrNull() ?: 0L

    private val Book.bookKey: String
        get() = "$sourceId:$id"
}
