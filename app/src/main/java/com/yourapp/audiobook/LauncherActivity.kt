package com.yourapp.audiobook

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.yourapp.audiobook.data.SettingsStore
import kotlinx.coroutines.launch

/**
 * Точка входа приложения. Режим управления выбирается автоматически
 * по типу устройства: смартфон/планшет — сенсорный экран ([MainActivity]),
 * Android TV — пульт ДУ ([TvActivity]). Ручное переключение доступно в настройках.
 */
class LauncherActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as AudioBookApplication
        app.resumeLastBookIfEnabled(lifecycleScope, this)
        // Мгновенный роутинг без ожидания DataStore — чтобы не мелькал промежуточный экран.
        val cachedMode = app.settingsStore.cachedUiMode()
        if (cachedMode != null) {
            UiModeRouter.launchForMode(this, cachedMode)
            return
        }
        lifecycleScope.launch {
            // При первом запуске (режим не выбран) сохраняется автовыбор по устройству.
            var mode = app.settingsStore.currentUiMode()
            if (mode == null) {
                mode = SettingsStore.UI_MODE_AUTO
                app.settingsStore.setUiMode(mode)
            }
            UiModeRouter.launchForMode(this@LauncherActivity, mode)
        }
    }
}