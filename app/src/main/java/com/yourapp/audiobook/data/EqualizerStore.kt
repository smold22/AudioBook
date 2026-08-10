package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.equalizerDataStore by preferencesDataStore(name = "equalizer")

data class EqualizerSettings(
    val enabled: Boolean = false,
    val bandLevels: List<Int> = emptyList(),
)

class EqualizerStore(context: Context) {

    private val appContext = context.applicationContext

    private val enabledKey = booleanPreferencesKey("enabled")
    private val levelsKey = stringPreferencesKey("levels")

    val settings: Flow<EqualizerSettings> = appContext.equalizerDataStore.data.map { prefs ->
        EqualizerSettings(
            enabled = prefs[enabledKey] ?: false,
            bandLevels = prefs[levelsKey]
                ?.split(";")
                ?.mapNotNull { it.toIntOrNull() }
                .orEmpty(),
        )
    }

    suspend fun save(enabled: Boolean, bandLevels: List<Int>) {
        appContext.equalizerDataStore.edit { prefs ->
            prefs[enabledKey] = enabled
            prefs[levelsKey] = bandLevels.joinToString(";")
        }
    }
}
