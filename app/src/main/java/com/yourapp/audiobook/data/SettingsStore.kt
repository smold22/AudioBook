package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    private val folderKey = stringPreferencesKey("download_folder")
    private val themeKey = stringPreferencesKey("theme")
    private val sourceKey = stringPreferencesKey("source_id")
    private val resumeOnLaunchKey = booleanPreferencesKey("resume_on_launch")
    private val viewModeKey = stringPreferencesKey("view_mode")
    private val hideTabLabelsKey = booleanPreferencesKey("hide_tab_labels")
    private val fontScaleKey = stringPreferencesKey("font_scale")

    val downloadFolder: Flow<String?> =
        appContext.settingsDataStore.data.map { it[folderKey] }

    val themeMode: Flow<String> =
        appContext.settingsDataStore.data.map { it[themeKey] ?: THEME_SYSTEM }

    val selectedSourceId: Flow<String?> =
        appContext.settingsDataStore.data.map { it[sourceKey] }

    val resumeOnLaunch: Flow<Boolean> =
        appContext.settingsDataStore.data.map { it[resumeOnLaunchKey] ?: false }

    val viewMode: Flow<String> =
        appContext.settingsDataStore.data.map { it[viewModeKey] ?: VIEW_LIST }

    val hideTabLabels: Flow<Boolean> =
        appContext.settingsDataStore.data.map { it[hideTabLabelsKey] ?: false }

    val fontScale: Flow<String> =
        appContext.settingsDataStore.data.map { it[fontScaleKey] ?: FONT_MEDIUM }

    suspend fun setResumeOnLaunch(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[resumeOnLaunchKey] = enabled
        }
    }

    suspend fun setViewMode(mode: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[viewModeKey] = mode
        }
    }

    suspend fun setHideTabLabels(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[hideTabLabelsKey] = enabled
        }
    }

    suspend fun setFontScale(mode: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[fontScaleKey] = mode
        }
    }

    suspend fun currentSourceId(): String? =
        appContext.settingsDataStore.data.first()[sourceKey]

    suspend fun setSourceId(id: String?) {
        appContext.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(sourceKey) else prefs[sourceKey] = id
        }
    }

    suspend fun currentDownloadFolder(): String? =
        appContext.settingsDataStore.data.first()[folderKey]

    suspend fun setDownloadFolder(uri: String?) {
        appContext.settingsDataStore.edit { prefs ->
            if (uri == null) prefs.remove(folderKey) else prefs[folderKey] = uri
        }
    }

    suspend fun saveDownloadedBook(bookKey: String, folder: String, metaJson: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey("$bookFolderPrefix$bookKey")] = folder
            prefs[stringPreferencesKey("$bookMetaPrefix$bookKey")] = metaJson
        }
    }

    suspend fun downloadedBooks(): Map<String, String> {
        val prefs = appContext.settingsDataStore.data.first()
        val bookKeys = prefs.asMap().keys
            .mapNotNull { it.name.takeIf { name -> name.startsWith(bookFolderPrefix) } }
            .map { it.removePrefix(bookFolderPrefix) }
        return buildMap {
            bookKeys.forEach { bookKey ->
                prefs[stringPreferencesKey("$bookFolderPrefix$bookKey")]?.let { folder ->
                    put(bookKey, folder)
                }
            }
        }
    }

    suspend fun bookMeta(bookKey: String): String? =
        appContext.settingsDataStore.data.first()[stringPreferencesKey("$bookMetaPrefix$bookKey")]

    suspend fun legacyDownloadedKeys(): Set<String> {
        val prefs = appContext.settingsDataStore.data.first()
        return buildSet {
            prefs.asMap().forEach { (key, _) ->
                val name = key.name
                if (name.startsWith("downloaded:") && prefs[booleanPreferencesKey(name)] == true) {
                    add(name.removePrefix("downloaded:"))
                }
            }
        }
    }

    suspend fun removeLegacyDownloadedKey(bookKey: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs.remove(booleanPreferencesKey("downloaded:$bookKey"))
        }
    }

    suspend fun removeDownloadedBook(bookKey: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("$bookFolderPrefix$bookKey"))
            prefs.remove(stringPreferencesKey("$bookMetaPrefix$bookKey"))
        }
    }

    suspend fun setThemeMode(mode: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[themeKey] = mode
        }
    }

    fun ignoredFlow(section: IgnoreSection): Flow<Set<String>> =
        appContext.settingsDataStore.data.map { it[stringSetPreferencesKey(section.key)].orEmpty() }

    suspend fun ignored(section: IgnoreSection): Set<String> =
        appContext.settingsDataStore.data.first()[stringSetPreferencesKey(section.key)].orEmpty()

    suspend fun addIgnored(section: IgnoreSection, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        val key = stringSetPreferencesKey(section.key)
        appContext.settingsDataStore.edit { prefs ->
            prefs[key] = prefs[key].orEmpty() + trimmed
        }
    }

    suspend fun removeIgnored(section: IgnoreSection, value: String) {
        val key = stringSetPreferencesKey(section.key)
        appContext.settingsDataStore.edit { prefs ->
            prefs[key] = prefs[key].orEmpty() - value
        }
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val VIEW_LIST = "list"
        const val VIEW_GRID = "grid"

        const val FONT_SMALL = "small"
        const val FONT_MEDIUM = "medium"
        const val FONT_LARGE = "large"

        private const val bookFolderPrefix = "bookFolder:"
        private const val bookMetaPrefix = "bookMeta:"
    }
}

enum class IgnoreSection(val key: String, val label: String) {
    GENRE("ignored_genres", "Жанры"),
    AUTHOR("ignored_authors", "Авторы"),
    READER("ignored_readers", "Чтецы"),
}
