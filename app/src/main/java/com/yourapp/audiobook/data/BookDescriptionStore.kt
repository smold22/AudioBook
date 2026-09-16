package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.bookDescriptionsDataStore by preferencesDataStore(name = "book_descriptions")

/**
 * Кэш описаний книг (bookKey -> описание), используемый для поиска «похожих книг»
 * по смыслу. Хранится в DataStore Preferences как JSON-объект с ограничением размера,
 * чтобы не тянуть описание одной и той же книги повторно.
 */
class BookDescriptionStore(context: Context) {

    private val appContext = context.applicationContext

    suspend fun get(bookKey: String): String? =
        appContext.bookDescriptionsDataStore.data
            .map { decode(it[entriesKey])[bookKey] }
            .first()

    suspend fun put(bookKey: String, description: String) {
        if (description.isBlank()) return
        appContext.bookDescriptionsDataStore.edit { prefs ->
            val map = decode(prefs[entriesKey])
            map[bookKey] = description
            while (map.size > MAX_ENTRIES) {
                val first = map.keys.iterator()
                if (first.hasNext()) {
                    first.next()
                    first.remove()
                }
            }
            prefs[entriesKey] = encode(map)
        }
    }

    private fun decode(raw: String?): LinkedHashMap<String, String> {
        if (raw.isNullOrBlank()) return LinkedHashMap()
        return runCatching {
            val json = JSONObject(raw)
            LinkedHashMap<String, String>().apply {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    json.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
                }
            }
        }.getOrDefault(LinkedHashMap())
    }

    private fun encode(map: LinkedHashMap<String, String>): String =
        JSONObject(map).toString()

    private companion object {
        val entriesKey = stringPreferencesKey("entries")
        const val MAX_ENTRIES = 150
    }
}