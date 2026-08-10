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
        appContext.deadBooksDataStore.data.map { it[key] ?: emptySet() }

    suspend fun snapshot(): Set<String> =
        appContext.deadBooksDataStore.data.first()[key] ?: emptySet()

    suspend fun markDead(bookKey: String) {
        appContext.deadBooksDataStore.edit { prefs ->
            prefs[key] = (prefs[key] ?: emptySet()) + bookKey
        }
    }
}
