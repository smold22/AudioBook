package com.yourapp.audiobook.ui.theme

import androidx.compose.ui.graphics.Color
import com.yourapp.audiobook.data.SettingsStore

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/** Палитра акцентного цвета: основные цвета для светлой и тёмной темы. */
data class AccentPalette(
    val lightPrimary: Color,
    val lightSecondary: Color,
    val lightTertiary: Color,
    val lightPrimaryContainer: Color,
    val lightSecondaryContainer: Color,
    val darkPrimary: Color,
    val darkSecondary: Color,
    val darkTertiary: Color,
    val darkPrimaryContainer: Color,
    val darkSecondaryContainer: Color,
)

/** Доступные акцентные цвета по идентификатору настройки [SettingsStore.ACCENT_*]. */
val AccentPalettes: Map<String, AccentPalette> = mapOf(
    SettingsStore.ACCENT_PURPLE to AccentPalette(
        lightPrimary = Purple40,
        lightSecondary = PurpleGrey40,
        lightTertiary = Pink40,
        lightPrimaryContainer = Color(0xFFEADDFF),
        lightSecondaryContainer = Color(0xFFE8DEF8),
        darkPrimary = Purple80,
        darkSecondary = PurpleGrey80,
        darkTertiary = Pink80,
        darkPrimaryContainer = Color(0xFF4F378B),
        darkSecondaryContainer = Color(0xFF4A445D),
    ),
    SettingsStore.ACCENT_BLUE to AccentPalette(
        lightPrimary = Color(0xFF1565C0),
        lightSecondary = Color(0xFF0277BD),
        lightTertiary = Color(0xFF00838F),
        lightPrimaryContainer = Color(0xFFD6E3FF),
        lightSecondaryContainer = Color(0xFFD0E8FF),
        darkPrimary = Color(0xFF90CAF9),
        darkSecondary = Color(0xFF4FC3F7),
        darkTertiary = Color(0xFF80DEEA),
        darkPrimaryContainer = Color(0xFF003B82),
        darkSecondaryContainer = Color(0xFF005A82),
    ),
    SettingsStore.ACCENT_GREEN to AccentPalette(
        lightPrimary = Color(0xFF2E7D32),
        lightSecondary = Color(0xFF00695C),
        lightTertiary = Color(0xFF558B2F),
        lightPrimaryContainer = Color(0xFFC8E6C9),
        lightSecondaryContainer = Color(0xFFB2DFDB),
        darkPrimary = Color(0xFFA5D6A7),
        darkSecondary = Color(0xFF80CBC4),
        darkTertiary = Color(0xFFC5E1A5),
        darkPrimaryContainer = Color(0xFF1B5E20),
        darkSecondaryContainer = Color(0xFF00695C),
    ),
    SettingsStore.ACCENT_ORANGE to AccentPalette(
        lightPrimary = Color(0xFFEF6C00),
        lightSecondary = Color(0xFFF57C00),
        lightTertiary = Color(0xFF6D4C41),
        lightPrimaryContainer = Color(0xFFFFE0B2),
        lightSecondaryContainer = Color(0xFFFFCCBC),
        darkPrimary = Color(0xFFFFCC80),
        darkSecondary = Color(0xFFFFAB91),
        darkTertiary = Color(0xFFBCAAA4),
        darkPrimaryContainer = Color(0xFFE65100),
        darkSecondaryContainer = Color(0xFFBF360C),
    ),
    SettingsStore.ACCENT_RED to AccentPalette(
        lightPrimary = Color(0xFFC62828),
        lightSecondary = Color(0xFFAD1457),
        lightTertiary = Color(0xFF6A1B9A),
        lightPrimaryContainer = Color(0xFFF9D7D7),
        lightSecondaryContainer = Color(0xFFF8BBD0),
        darkPrimary = Color(0xFFEF9A9A),
        darkSecondary = Color(0xFFF48FB1),
        darkTertiary = Color(0xFFCE93D8),
        darkPrimaryContainer = Color(0xFF8E0000),
        darkSecondaryContainer = Color(0xFF880E4F),
    ),
    SettingsStore.ACCENT_TEAL to AccentPalette(
        lightPrimary = Color(0xFF00796B),
        lightSecondary = Color(0xFF00897B),
        lightTertiary = Color(0xFF0097A7),
        lightPrimaryContainer = Color(0xFFB2DFDB),
        lightSecondaryContainer = Color(0xFFB2EBF2),
        darkPrimary = Color(0xFF80CBC4),
        darkSecondary = Color(0xFF4DB6AC),
        darkTertiary = Color(0xFF80DEEA),
        darkPrimaryContainer = Color(0xFF004D40),
        darkSecondaryContainer = Color(0xFF00695C),
    ),
    SettingsStore.ACCENT_PINK to AccentPalette(
        lightPrimary = Color(0xFFD81B60),
        lightSecondary = Color(0xFFC2185B),
        lightTertiary = Color(0xFF8E24AA),
        lightPrimaryContainer = Color(0xFFF8BBD0),
        lightSecondaryContainer = Color(0xFFFCE4EC),
        darkPrimary = Color(0xFFF48FB1),
        darkSecondary = Color(0xFFF06292),
        darkTertiary = Color(0xFFCE93D8),
        darkPrimaryContainer = Color(0xFF880E4F),
        darkSecondaryContainer = Color(0xFF6D2A4F),
    ),
    SettingsStore.ACCENT_INDIGO to AccentPalette(
        lightPrimary = Color(0xFF3949AB),
        lightSecondary = Color(0xFF5C6BC0),
        lightTertiary = Color(0xFF7E57C2),
        lightPrimaryContainer = Color(0xFFC5CAE9),
        lightSecondaryContainer = Color(0xFFD1C4E9),
        darkPrimary = Color(0xFF9FA8DA),
        darkSecondary = Color(0xFF7986CB),
        darkTertiary = Color(0xFFB39DDB),
        darkPrimaryContainer = Color(0xFF283593),
        darkSecondaryContainer = Color(0xFF3949AB),
    ),
    SettingsStore.ACCENT_CYAN to AccentPalette(
        lightPrimary = Color(0xFF0097A7),
        lightSecondary = Color(0xFF00838F),
        lightTertiary = Color(0xFF00ACC1),
        lightPrimaryContainer = Color(0xFFB2EBF2),
        lightSecondaryContainer = Color(0xFFE0F7FA),
        darkPrimary = Color(0xFF80DEEA),
        darkSecondary = Color(0xFF4DD0E1),
        darkTertiary = Color(0xFF4FC3F7),
        darkPrimaryContainer = Color(0xFF006064),
        darkSecondaryContainer = Color(0xFF00838F),
    ),
    SettingsStore.ACCENT_BROWN to AccentPalette(
        lightPrimary = Color(0xFF6D4C41),
        lightSecondary = Color(0xFF5D4037),
        lightTertiary = Color(0xFF4E342E),
        lightPrimaryContainer = Color(0xFFD7CCC8),
        lightSecondaryContainer = Color(0xFFEFEBE9),
        darkPrimary = Color(0xFFBCAAA4),
        darkSecondary = Color(0xFFA1887F),
        darkTertiary = Color(0xFF8D6E63),
        darkPrimaryContainer = Color(0xFF4E342E),
        darkSecondaryContainer = Color(0xFF5D4037),
    ),
)