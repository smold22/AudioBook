package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.progressDataStore by preferencesDataStore(name = "progress")

data class TrackPosition(
    val trackIndex: Int,
    val positionMs: Long,
)

class ProgressStore(context: Context) {

    private val appContext = context.applicationContext

    suspend fun save(bookId: String, trackIndex: Int, positionMs: Long) {
        appContext.progressDataStore.edit { prefs ->
            prefs[stringPreferencesKey("book:$bookId")] = "$trackIndex;$positionMs"
        }
    }

    suspend fun load(bookId: String): TrackPosition? {
        val raw = appContext.progressDataStore.data.first()[stringPreferencesKey("book:$bookId")]
            ?: return null
        val parts = raw.split(";")
        if (parts.size != 2) return null
        val track = parts[0].toIntOrNull() ?: return null
        val position = parts[1].toLongOrNull() ?: return null
        return TrackPosition(track, position)
    }

    suspend fun clear() {
        appContext.progressDataStore.edit { it.clear() }
    }

    suspend fun snapshot(): Map<String, String> {
        val prefs = appContext.progressDataStore.data.first()
        return prefs.asMap().entries
            .filter { it.key.name.startsWith(PROGRESS_PREFIX) }
            .associate { key ->
                key.key.name to prefs[stringPreferencesKey(key.key.name)].orEmpty()
            }
    }

    suspend fun import(snapshot: Map<String, String>) {
        if (snapshot.isEmpty()) return
        appContext.progressDataStore.edit { prefs ->
            snapshot.forEach { (name, value) ->
                prefs[stringPreferencesKey(name)] = value
            }
        }
    }

    private companion object {
        const val PROGRESS_PREFIX = "book:"
    }
}