package com.yourapp.audiobook.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.yourapp.audiobook.data.SettingsStore

private fun mix(a: Color, b: Color, t: Float): Color = lerp(a, b, t)

/** Читаемый цвет текста (тёмный или белый) на фоне [color]. */
private fun readableOn(color: Color): Color {
    val lum = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (lum > 0.5f) Color(0xFF1C1B1F) else Color.White
}

/** Схема из произвольного цвета палитры (с учётом прозрачности). */
private fun customAccentScheme(base: Color, dark: Boolean): ColorScheme {
    val solid = base.copy(alpha = 1f)
    val primaryContainer = if (dark) mix(solid, Color.Black, 0.68f) else mix(solid, Color.White, 0.72f)
    val secondaryContainer = if (dark) mix(solid, Color.Black, 0.5f) else mix(solid, Color.White, 0.84f)
    return if (dark) {
        darkColorScheme(
            primary = base,
            onPrimary = readableOn(solid),
            primaryContainer = primaryContainer,
            onPrimaryContainer = readableOn(primaryContainer),
            secondary = mix(solid, Color.White, 0.35f),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = readableOn(secondaryContainer),
            tertiary = mix(solid, Color.White, 0.6f),
        )
    } else {
        lightColorScheme(
            primary = base,
            onPrimary = readableOn(solid),
            primaryContainer = primaryContainer,
            onPrimaryContainer = readableOn(primaryContainer),
            secondary = mix(solid, Color.Black, 0.18f),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = readableOn(secondaryContainer),
            tertiary = mix(solid, Color.Black, 0.08f),
        )
    }
}

private fun accentColorScheme(accent: String, dark: Boolean, customAccentHex: String?): ColorScheme {
    if (accent == SettingsStore.ACCENT_CUSTOM) {
        val argb = customAccentHex?.toLongOrNull(16)?.toInt()
        if (argb != null) return customAccentScheme(Color(argb), dark)
    }
    val palette = AccentPalettes[accent] ?: AccentPalettes.getValue(SettingsStore.ACCENT_PURPLE)
    return if (dark) {
        darkColorScheme(
            primary = palette.darkPrimary,
            secondary = palette.darkSecondary,
            tertiary = palette.darkTertiary,
            primaryContainer = palette.darkPrimaryContainer,
            secondaryContainer = palette.darkSecondaryContainer,
        )
    } else {
        lightColorScheme(
            primary = palette.lightPrimary,
            secondary = palette.lightSecondary,
            tertiary = palette.lightTertiary,
            primaryContainer = palette.lightPrimaryContainer,
            secondaryContainer = palette.lightSecondaryContainer,
        )
    }
}

@Composable
fun AudioBookTheme(
    darkTheme: Boolean? = null,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    accentColor: String = SettingsStore.ACCENT_DYNAMIC,
    customAccentHex: String? = null,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val useDark = darkTheme ?: isSystemInDarkTheme()
    val useDynamic = dynamicColor && accentColor == SettingsStore.ACCENT_DYNAMIC
    val colorScheme = when {
        useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        useDark -> accentColorScheme(accentColor, dark = true, customAccentHex = customAccentHex)
        else -> accentColorScheme(accentColor, dark = false, customAccentHex = customAccentHex)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !useDark
                isAppearanceLightNavigationBars = !useDark
            }
        }
    }

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = density.fontScale * fontScale,
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}