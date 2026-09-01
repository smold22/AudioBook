package com.yourapp.audiobook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.player.PlaybackService
import com.yourapp.audiobook.ui.AppNavHost
import com.yourapp.audiobook.ui.FocusBorderIndication
import com.yourapp.audiobook.ui.LocalUiMode
import com.yourapp.audiobook.ui.theme.AudioBookTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Маршрутизация между активностями: смартфон (вертикальная ориентация)
 * и Android TV (горизонтальная ориентация).
 */
object UiModeRouter {

    /** Определяет тип устройства: Android TV (пульт ДУ) или сенсорный экран. */
    fun isTvDevice(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return mode == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** Разрешает режим [SettingsStore.UI_MODE_AUTO] в конкретный по типу устройства. */
    fun resolveUiMode(context: Context, mode: String): String =
        if (mode == SettingsStore.UI_MODE_AUTO) {
            if (isTvDevice(context)) SettingsStore.UI_MODE_TV else SettingsStore.UI_MODE_TOUCH
        } else {
            mode
        }

    fun activityForMode(context: Context, mode: String): Class<out Activity> =
        if (resolveUiMode(context, mode) == SettingsStore.UI_MODE_TV) TvActivity::class.java else MainActivity::class.java

    fun launchForMode(context: Context, mode: String) {
        val activity = context as? Activity
        val intent = Intent(context, activityForMode(context, mode))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        activity?.finish()
    }
}

/**
 * Корневое содержимое приложения: тема, шрифт и интерфейс
 * для выбранного режима управления ([SettingsStore.UI_MODE_TOUCH] или [SettingsStore.UI_MODE_TV]).
 */
@Composable
fun AppRoot(uiMode: String, onExitApp: () -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as AudioBookApplication).settingsStore
    val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = SettingsStore.THEME_SYSTEM)
    val darkTheme = when (themeMode) {
        SettingsStore.THEME_DARK -> true
        SettingsStore.THEME_LIGHT -> false
        else -> null
    }
    val fontMode by settings.fontScale.collectAsStateWithLifecycle(initialValue = SettingsStore.FONT_MEDIUM)
    val fontScale = when (fontMode) {
        SettingsStore.FONT_SMALL -> 0.85f
        SettingsStore.FONT_LARGE -> 1.2f
        else -> 1f
    }
    // В горизонтальной ориентации используем ТВ-интерфейс (боковая навигация,
    // обводка фокуса), как на Android TV.
    val isTv = uiMode == SettingsStore.UI_MODE_TV ||
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val effectiveUiMode = if (isTv) SettingsStore.UI_MODE_TV else uiMode
    CompositionLocalProvider(
        LocalUiMode provides effectiveUiMode,
        LocalIndication provides if (isTv) FocusBorderIndication() else LocalIndication.current,
    ) {
        AudioBookTheme(darkTheme = darkTheme, fontScale = fontScale) {
            AppNavHost(onExitApp = onExitApp)
        }
    }
}

/** Продолжает прослушивание последней книги при запуске, если включено в настройках. */
fun AudioBookApplication.resumeLastBookIfEnabled(scope: CoroutineScope, context: Context) {
    scope.launch {
        if (!settingsStore.resumeOnLaunch.first()) return@launch
        if (playerController.nowPlaying.value != null) return@launch
        val last = historyStore.snapshot().firstOrNull() ?: return@launch
        val book = last.book
        val source = sourceRegistry.get(book.sourceId) ?: return@launch
        val details = withContext(Dispatchers.IO) {
            runCatching { source.getBookDetails(book.url) }.getOrNull()
        } ?: return@launch
        if (details.tracks.isEmpty()) return@launch
        val bookKey = "${book.sourceId}:${book.id}"
        val progress = progressStore.load(bookKey)
        val savedTrack = progress?.trackIndex ?: 0
        val trackIndex = savedTrack.takeIf { it in details.tracks.indices } ?: 0
        val positionMs = if (trackIndex == savedTrack) progress?.positionMs ?: 0L else 0L
        val localUris = if (downloadManager.isDownloaded(bookKey)) {
            downloadManager.offlineTrackUris(bookKey, details.tracks.size)
        } else {
            null
        }
        playerController.play(details, trackIndex, positionMs, localUris)
        ContextCompat.startForegroundService(
            context,
            Intent(context, PlaybackService::class.java),
        )
    }
}