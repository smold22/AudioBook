package com.yourapp.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    /** Быстрое зеркало режима управления для мгновенного роутинга при запуске. */
    private val uiModeMirror =
        appContext.getSharedPreferences("ui_mode_mirror", Context.MODE_PRIVATE)

    private val changeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /** Поток сигналов об изменении настроек (для автосинхронизации). */
    val changes: Flow<Unit> = changeSignal.asSharedFlow()

    private val folderKey = stringPreferencesKey("download_folder")
    private val themeKey = stringPreferencesKey("theme")
    private val sourceKey = stringPreferencesKey("source_id")
    private val resumeOnLaunchKey = booleanPreferencesKey("resume_on_launch")
    private val openPlayerOnLaunchKey = booleanPreferencesKey("open_player_on_launch")
    private val viewModeKey = stringPreferencesKey("view_mode")
    private val hideTabLabelsKey = booleanPreferencesKey("hide_tab_labels")
    private val tabUnderlayHeightKey = stringPreferencesKey("tab_underlay_height")
    private val fontScaleKey = stringPreferencesKey("font_scale")
    private val hideFemaleAuthorsKey = booleanPreferencesKey("hide_female_authors")
    private val closeOnBackLongPressKey = booleanPreferencesKey("close_on_back_long_press")
    private val uiModeKey = stringPreferencesKey("ui_mode")
    private val accentColorKey = stringPreferencesKey("accent_color")
    private val customAccentColorKey = stringPreferencesKey("custom_accent_color")
    private val hiddenSourcesKey = stringSetPreferencesKey("hidden_sources")
    private val homeGenreSourceKey = stringPreferencesKey("home_genre_source")
    private val homeGenreUrlKey = stringPreferencesKey("home_genre_url")
    private val homeGenreNameKey = stringPreferencesKey("home_genre_name")
    private val appIconKey = stringPreferencesKey("app_icon")
    private val playbackSpeedKey = floatPreferencesKey("playback_speed")

    val downloadFolder: Flow<String?> =
        appContext.settingsDataStore.data.map { it[folderKey] }

    val themeMode: Flow<String> =
        appContext.settingsDataStore.data.map { it[themeKey] ?: THEME_SYSTEM }

    val selectedSourceId: Flow<String?> =
        appContext.settingsDataStore.data.map { it[sourceKey] }

    val resumeOnLaunch: Flow<Boolean> =
        appContext.settingsDataStore.data.map { it[resumeOnLaunchKey] ?: false }

    val openPlayerOnLaunch: Flow<Boolean> =
        appContext.settingsDataStore.data.map { it[openPlayerOnLaunchKey] ?: false }

    val viewMode: Flow<String> =
        appContext.settingsDataStore.data.map { it[viewModeKey] ?: VIEW_LIST }

    val hideTabLabels: Flow<Boolean> =
        appContext.settingsDataStore.data.map { it[hideTabLabelsKey] ?: false }

    val tabUnderlayHeight: Flow<String> =
        appContext.settingsDataStore.data.map { it[tabUnderlayHeightKey] ?: TAB_UNDERLAY_DEFAULT }

    val fontScale: Flow<String> =
        appContext.settingsDataStore.data.map { it[fontScaleKey] ?: FONT_MEDIUM }

    val hideFemaleAuthors: Flow<Boolean> =
        appContext.settingsDataStore.data.map { it[hideFemaleAuthorsKey] ?: false }

    val closeOnBackLongPress: Flow<Boolean> =
        appContext.settingsDataStore.data.map { it[closeOnBackLongPressKey] ?: false }

    val uiMode: Flow<String?> =
        appContext.settingsDataStore.data.map { it[uiModeKey] }

    /** Акцентный цвет приложения (null-значение ключа — динамический системный). */
    val accentColor: Flow<String> =
        appContext.settingsDataStore.data.map { it[accentColorKey] ?: ACCENT_DYNAMIC }

    /** Свой цвет акцента в формате AARRGGBB (для [ACCENT_CUSTOM]). */
    val customAccentColor: Flow<String?> =
        appContext.settingsDataStore.data.map { it[customAccentColorKey] }

    /** Идентификаторы скрытых источников. */
    val hiddenSources: Flow<Set<String>> =
        appContext.settingsDataStore.data.map { it[hiddenSourcesKey].orEmpty() }

    /** Жанр, книги которого показываются на главном экране (null — все книги). */
    val homeGenre: Flow<HomeGenre?> =
        appContext.settingsDataStore.data.map { prefs ->
            val sourceId = prefs[homeGenreSourceKey] ?: return@map null
            val url = prefs[homeGenreUrlKey] ?: return@map null
            val name = prefs[homeGenreNameKey] ?: return@map null
            HomeGenre(sourceId = sourceId, url = url, name = name)
        }

    /** Выбранная иконка приложения ("default" или "alt1"…"alt5"). */
    val appIcon: Flow<String> =
        appContext.settingsDataStore.data.map { prefs ->
            when (val value = prefs[appIconKey]) {
                null -> APP_ICON_DEFAULT
                // Совместимость со старым ключом первой альтернативной иконки.
                "alt" -> APP_ICON_ALT
                else -> value
            }
        }

    /** Запомненная скорость воспроизведения (по умолчанию 1x). */
    val playbackSpeed: Flow<Float> =
        appContext.settingsDataStore.data.map { it[playbackSpeedKey] ?: 1f }

    suspend fun setPlaybackSpeed(speed: Float) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[playbackSpeedKey] = speed
        }
        changeSignal.emit(Unit)
    }

    suspend fun setResumeOnLaunch(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[resumeOnLaunchKey] = enabled
        }
        changeSignal.emit(Unit)
    }

    suspend fun setOpenPlayerOnLaunch(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[openPlayerOnLaunchKey] = enabled
        }
        changeSignal.emit(Unit)
    }

    suspend fun setViewMode(mode: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[viewModeKey] = mode
        }
        changeSignal.emit(Unit)
    }

    suspend fun setHideTabLabels(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[hideTabLabelsKey] = enabled
        }
        changeSignal.emit(Unit)
    }

    suspend fun setTabUnderlayHeight(mode: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[tabUnderlayHeightKey] = mode
        }
        changeSignal.emit(Unit)
    }

    suspend fun setFontScale(mode: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[fontScaleKey] = mode
        }
        changeSignal.emit(Unit)
    }

    suspend fun setHideFemaleAuthors(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[hideFemaleAuthorsKey] = enabled
        }
        changeSignal.emit(Unit)
    }

    suspend fun setCloseOnBackLongPress(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[closeOnBackLongPressKey] = enabled
        }
        changeSignal.emit(Unit)
    }

    suspend fun setUiMode(mode: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[uiModeKey] = mode
        }
        uiModeMirror.edit().putString("ui_mode", mode).apply()
        changeSignal.emit(Unit)
    }

    suspend fun setAccentColor(accent: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[accentColorKey] = accent
        }
        changeSignal.emit(Unit)
    }

    suspend fun setCustomAccentColor(argb: Int) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[customAccentColorKey] = String.format("%08X", argb)
        }
        changeSignal.emit(Unit)
    }

    suspend fun currentUiMode(): String? {
        val mode = appContext.settingsDataStore.data.first()[uiModeKey]
        if (mode != null) {
            uiModeMirror.edit().putString("ui_mode", mode).apply()
        }
        return mode
    }

    suspend fun hiddenSourceIds(): Set<String> =
        appContext.settingsDataStore.data.first()[hiddenSourcesKey].orEmpty()

    suspend fun setSourceHidden(id: String, hidden: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[hiddenSourcesKey] = if (hidden) {
                prefs[hiddenSourcesKey].orEmpty() + id
            } else {
                prefs[hiddenSourcesKey].orEmpty() - id
            }
        }
        changeSignal.emit(Unit)
    }

    suspend fun setHomeGenre(genre: HomeGenre?) {
        appContext.settingsDataStore.edit { prefs ->
            if (genre == null) {
                prefs.remove(homeGenreSourceKey)
                prefs.remove(homeGenreUrlKey)
                prefs.remove(homeGenreNameKey)
            } else {
                prefs[homeGenreSourceKey] = genre.sourceId
                prefs[homeGenreUrlKey] = genre.url
                prefs[homeGenreNameKey] = genre.name
            }
        }
        changeSignal.emit(Unit)
    }

    suspend fun setAppIcon(icon: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[appIconKey] = icon
        }
        changeSignal.emit(Unit)
    }

    /** Синхронное чтение режима управления (без ожидания DataStore). */
    fun cachedUiMode(): String? = uiModeMirror.getString("ui_mode", null)

    suspend fun currentSourceId(): String? =
        appContext.settingsDataStore.data.first()[sourceKey]

    suspend fun setSourceId(id: String?) {
        appContext.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(sourceKey) else prefs[sourceKey] = id
        }
        changeSignal.emit(Unit)
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
            prefs.remove(stringPreferencesKey("$bookTracksPrefix$bookKey"))
        }
    }

    suspend fun saveBookMeta(bookKey: String, metaJson: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey("$bookMetaPrefix$bookKey")] = metaJson
        }
    }

    /** Имена файлов треков, скачанных отдельно (по одному). */
    suspend fun downloadedTrackNames(): Map<String, Set<String>> {
        val prefs = appContext.settingsDataStore.data.first()
        return buildMap {
            prefs.asMap().keys.forEach { key ->
                val name = key.name
                if (name.startsWith(bookTracksPrefix)) {
                    val bookKey = name.removePrefix(bookTracksPrefix)
                    put(
                        bookKey,
                        prefs[stringPreferencesKey(name)]
                            .orEmpty()
                            .split(SET_ITEM_SEPARATOR)
                            .filter { it.isNotEmpty() }
                            .toSet(),
                    )
                }
            }
        }
    }

    suspend fun saveDownloadedTracks(bookKey: String, names: Set<String>) {
        appContext.settingsDataStore.edit { prefs ->
            if (names.isEmpty()) {
                prefs.remove(stringPreferencesKey("$bookTracksPrefix$bookKey"))
            } else {
                prefs[stringPreferencesKey("$bookTracksPrefix$bookKey")] =
                    names.joinToString(SET_ITEM_SEPARATOR)
            }
        }
    }

    suspend fun setThemeMode(mode: String) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[themeKey] = mode
        }
        changeSignal.emit(Unit)
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
        changeSignal.emit(Unit)
    }

    suspend fun removeIgnored(section: IgnoreSection, value: String) {
        val key = stringSetPreferencesKey(section.key)
        appContext.settingsDataStore.edit { prefs ->
            prefs[key] = prefs[key].orEmpty() - value
        }
        changeSignal.emit(Unit)
    }

    /** Снимок всех синхронизируемых настроек (без путей к скачанным файлам). */
    suspend fun snapshotAll(): Map<String, String> {
        val prefs = appContext.settingsDataStore.data.first()
        val result = mutableMapOf<String, String>()
        prefs.asMap().forEach { (key, value) ->
            val name = key.name
            if (name == downloadFolderName || name.startsWith(bookFolderPrefix) ||
                name.startsWith(bookMetaPrefix) || name.startsWith(bookTracksPrefix)
            ) {
                return@forEach
            }
            when (value) {
                is String -> result[name] = value
                is Boolean -> result[name] = value.toString()
                is Set<*> -> result[name] = value.joinToString(SET_ITEM_SEPARATOR)
                else -> Unit
            }
        }
        return result
    }

    /** Применяет снимок настроек, пропуская ключи, специфичные для устройства. */
    suspend fun restoreAll(snapshot: Map<String, String>) {
        if (snapshot.isEmpty()) return
        appContext.settingsDataStore.edit { prefs ->
            snapshot.forEach { (name, value) ->
                if (name == downloadFolderName || name.startsWith(bookFolderPrefix) ||
                    name.startsWith(bookMetaPrefix) || name.startsWith(bookTracksPrefix)
                ) {
                    return@forEach
                }
                when (name) {
                    resumeOnLaunchKey.name, openPlayerOnLaunchKey.name, hideTabLabelsKey.name, hideFemaleAuthorsKey.name, closeOnBackLongPressKey.name ->
                        prefs[booleanPreferencesKey(name)] = value.toBooleanStrictOrNull() ?: false
                    IgnoreSection.GENRE.key, IgnoreSection.AUTHOR.key, IgnoreSection.READER.key, hiddenSourcesKey.name ->
                        prefs[stringSetPreferencesKey(name)] =
                            value.split(SET_ITEM_SEPARATOR).filter { it.isNotEmpty() }.toSet()
                    else -> prefs[stringPreferencesKey(name)] = value
                }
            }
        }
        changeSignal.emit(Unit)
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val VIEW_LIST = "list"
        const val VIEW_GRID = "grid"
        const val VIEW_GRID3 = "grid3"

        const val FONT_SMALL = "small"
        const val FONT_MEDIUM = "medium"
        const val FONT_LARGE = "large"

        const val TAB_UNDERLAY_LOW = "low"
        const val TAB_UNDERLAY_DEFAULT = "default"
        const val TAB_UNDERLAY_HIGH = "high"

        const val UI_MODE_AUTO = "auto"
        const val UI_MODE_TOUCH = "touch"
        const val UI_MODE_TV = "tv"

        const val ACCENT_DYNAMIC = "dynamic"
        const val ACCENT_CUSTOM = "custom"
        const val ACCENT_PURPLE = "purple"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_GREEN = "green"
        const val ACCENT_ORANGE = "orange"
        const val ACCENT_RED = "red"
        const val ACCENT_TEAL = "teal"
        const val ACCENT_PINK = "pink"
        const val ACCENT_INDIGO = "indigo"
        const val ACCENT_CYAN = "cyan"
        const val ACCENT_BROWN = "brown"

        const val APP_ICON_DEFAULT = "default"
        const val APP_ICON_ALT = "alt1"

        private const val SET_ITEM_SEPARATOR = "\u0001"

        const val downloadFolderName = "download_folder"
        const val bookFolderPrefix = "bookFolder:"
        const val bookMetaPrefix = "bookMeta:"
        const val bookTracksPrefix = "bookTracks:"
    }
}

enum class IgnoreSection(val key: String, val label: String) {
    GENRE("ignored_genres", "Жанры"),
    AUTHOR("ignored_authors", "Авторы"),
    READER("ignored_readers", "Чтецы"),
}

/** Жанр, отображаемый на главном экране: источник, ссылка и название. */
data class HomeGenre(
    val sourceId: String,
    val url: String,
    val name: String,
)
