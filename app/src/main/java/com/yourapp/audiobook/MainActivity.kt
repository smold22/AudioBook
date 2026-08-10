package com.yourapp.audiobook

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.player.PlaybackService
import com.yourapp.audiobook.ui.AppNavHost
import com.yourapp.audiobook.ui.theme.AudioBookTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        resumeLastBookIfEnabled()
        setContent {
            val settings = (application as AudioBookApplication).settingsStore
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
            AudioBookTheme(darkTheme = darkTheme, fontScale = fontScale) {
                AppNavHost()
            }
        }
    }

    private fun resumeLastBookIfEnabled() {
        val app = application as AudioBookApplication
        lifecycleScope.launch {
            if (!app.settingsStore.resumeOnLaunch.first()) return@launch
            if (app.playerController.nowPlaying.value != null) return@launch
            val last = app.historyStore.snapshot().firstOrNull() ?: return@launch
            val book = last.book
            val source = app.sourceRegistry.get(book.sourceId) ?: return@launch
            val details = withContext(Dispatchers.IO) {
                runCatching { source.getBookDetails(book.url) }.getOrNull()
            } ?: return@launch
            if (details.tracks.isEmpty()) return@launch
            val bookKey = "${book.sourceId}:${book.id}"
            val progress = app.progressStore.load(book.id)
            val trackIndex = progress?.trackIndex ?: 0
            val positionMs = progress?.positionMs ?: 0L
            val localUris = if (app.downloadManager.isDownloaded(bookKey)) {
                app.downloadManager.offlineTrackUris(bookKey, details.tracks.size)
            } else {
                null
            }
            app.playerController.play(details, trackIndex, positionMs, localUris)
            ContextCompat.startForegroundService(
                this@MainActivity,
                Intent(this@MainActivity, PlaybackService::class.java),
            )
        }
    }
}
