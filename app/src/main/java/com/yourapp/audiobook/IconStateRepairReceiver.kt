package com.yourapp.audiobook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Аварийный ремонт иконки лаунчера: восстанавливает состояния компонентов,
 * если приложение перестало запускаться (например, была отключена
 * [LauncherActivity]). Вызывается системным broadcast от adb.
 */
class IconStateRepairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPAIR) return
        val app = context.applicationContext as AudioBookApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val icon = app.settingsStore.appIcon.first()
                AppIconSwitcher.apply(context, icon)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPAIR = "com.yourapp.audiobook.action.REPAIR_ICON"
    }
}