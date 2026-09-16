package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.searchHistoryDataStore by preferencesDataStore(name = "search_history")

/** История поисковых запросов: упорядоченный список строк (свежие сверху). */
class SearchHistoryStore(context: Context) {

    private val appContext = context.applicationContext

    val queries: Flow<List<String>> =
        appContext.searchHistoryDataStore.data.map { decode(it[entriesKey]) }

    suspend fun add(query: String) {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return
        appContext.searchHistoryDataStore.edit { prefs ->
            val current = decode(prefs[entriesKey])
            val updated = listOf(normalized) +
                current.filterNot { it.equals(normalized, ignoreCase = true) }
            prefs[entriesKey] = encode(updated.take(MAX_ENTRIES))
        }
    }

    suspend fun remove(query: String) {
        appContext.searchHistoryDataStore.edit { prefs ->
            val current = decode(prefs[entriesKey])
            prefs[entriesKey] = encode(
                current.filterNot { it.equals(query, ignoreCase = true) },
            )
        }
    }

    suspend fun clear() {
        appContext.searchHistoryDataStore.edit { it.remove(entriesKey) }
    }

    suspend fun snapshot(): List<String> =
        appContext.searchHistoryDataStore.data
            .map { decode(it[entriesKey]) }
            .first()

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(entries: List<String>): String =
        JSONArray().apply {
            entries.forEach { put(it) }
        }.toString()

    private companion object {
        val entriesKey = stringPreferencesKey("entries")
        const val MAX_ENTRIES = 20
    }
}