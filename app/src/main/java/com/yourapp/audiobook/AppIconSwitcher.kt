package com.yourapp.audiobook

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

data class AppIconOption(
    val key: String,
    val label: String,
    val res: Int,
    val alias: String,
)

/**
 * Переключение иконки приложения на рабочем столе через activity-alias:
 * включается выбранный алиас, остальные отключаются. Сама [LauncherActivity]
 * (цель алиасов) всегда остаётся включённой.
 */
object AppIconSwitcher {

    val options = listOf(
        AppIconOption("default", "Стандартная", R.mipmap.ic_launcher, "LauncherIconDefault"),
        AppIconOption("alt1", "Иконка 1", R.mipmap.ic_launcher_1, "LauncherIconAlt1"),
        AppIconOption("alt2", "Иконка 2", R.mipmap.ic_launcher_2, "LauncherIconAlt2"),
        AppIconOption("alt3", "Иконка 3", R.mipmap.ic_launcher_3, "LauncherIconAlt3"),
        AppIconOption("alt4", "Иконка 4", R.mipmap.ic_launcher_4, "LauncherIconAlt4"),
        AppIconOption("alt5", "Иконка 5", R.mipmap.ic_launcher_5, "LauncherIconAlt5"),
    )

    fun apply(context: Context, icon: String) {
        val pm = context.packageManager
        // Совместимость со старым ключом "alt"; неизвестные значения — стандартная иконка.
        val effective = (legacyKeys[icon] ?: icon).let { raw ->
            options.firstOrNull { it.key == raw }?.key ?: "default"
        }
        pm.setComponentEnabledSetting(
            ComponentName(context, LauncherActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        options.forEach { option ->
            pm.setComponentEnabledSetting(
                ComponentName(context, "com.yourapp.audiobook.${option.alias}"),
                if (option.key == effective) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private val legacyKeys = mapOf("alt" to "alt1")
}