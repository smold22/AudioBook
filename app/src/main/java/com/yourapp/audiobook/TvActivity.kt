package com.yourapp.audiobook

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
 * Активность для Android TV (управление с пульта ДУ, горизонтальная ориентация).
 * Запускается из [LauncherActivity] автоматически или при ручном выборе режима.
 */
class TvActivity : ComponentActivity() {

    private var closeOnBackLongPress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            (application as AudioBookApplication).settingsStore.closeOnBackLongPress
                .collect { closeOnBackLongPress = it }
        }
        setContent {
            AppRoot(uiMode = SettingsStore.UI_MODE_TV, onExitApp = ::closeApp)
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
        stopService(android.content.Intent(this, PlaybackService::class.java))
        finishAffinity()
    }
}