package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.deadBooksDataStore by preferencesDataStore(name = "dead_books")

class DeadBooksStore(context: Context) {

    private val appContext = context.applicationContext
    private val key = stringSetPreferencesKey("dead")

    val deadKeys: Flow<Set<String>> =
        appContext.deadBooksDataStore.data.map { prune(it[key]) }

    suspend fun snapshot(): Set<String> {
        val prefs = appContext.deadBooksDataStore.data.first()
        return prune(prefs[key])
    }

    suspend fun markDead(bookKey: String) {
        val now = System.currentTimeMillis()
        appContext.deadBooksDataStore.edit { prefs ->
            val current = prune(prefs[key])
            val updated = (current + "$bookKey;$now").take(MAX_DEAD_BOOKS).toSet()
            prefs[key] = updated
        }
    }

    /** Отбрасывает просроченные записи; старые записи без метки времени сохраняются как есть. */
    private fun prune(entries: Set<String>?): Set<String> {
        if (entries == null || entries.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        return entries.mapNotNull { entry ->
            val idx = entry.lastIndexOf(';')
            if (idx < 0) {
                entry
            } else {
                val bookKey = entry.substring(0, idx)
                val ts = entry.substring(idx + 1).toLongOrNull()
                if (ts != null && now - ts < EXPIRY_MS) bookKey else null
            }
        }.toSet()
    }

    companion object {
        private const val EXPIRY_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_DEAD_BOOKS = 500
    }
}
