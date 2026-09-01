package com.yourapp.audiobook

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.player.PlaybackService
import kotlinx.coroutines.launch

/**
 * Активность для смартфона/планшета (сенсорный экран, вертикальная ориентация).
 * Запускается из [LauncherActivity] автоматически или при ручном выборе режима.
 */
class MainActivity : ComponentActivity() {

    private var closeOnBackLongPress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            (application as AudioBookApplication).settingsStore.closeOnBackLongPress
                .collect { closeOnBackLongPress = it }
        }
        setContent {
            AppRoot(uiMode = SettingsStore.UI_MODE_TOUCH, onExitApp = ::closeApp)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (closeOnBackLongPress &&
            keyCode == KeyEvent.KEYCODE_BACK &&
            (event.isLongPress || event.repeatCount > 0)
        ) {
            closeApp()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun closeApp() {
        val app = application as AudioBookApplication
        app.playerController.stopAndClear()
        stopService(Intent(this, PlaybackService::class.java))
        finishAffinity()
    }
}