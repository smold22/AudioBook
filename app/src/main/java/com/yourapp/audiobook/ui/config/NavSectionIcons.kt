package com.yourapp.audiobook.ui.config

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.yourapp.audiobook.R

/** Ресурс подписи раздела нижней навигации. */
@get:StringRes
val NavSection.labelRes: Int
    get() = when (this) {
        NavSection.HOME -> R.string.nav_section_home
        NavSection.FAVORITES -> R.string.nav_section_favorites
        NavSection.WATCHLIST -> R.string.nav_section_watchlist
        NavSection.GENRES -> R.string.nav_section_genres
        NavSection.HISTORY -> R.string.nav_section_history
        NavSection.DOWNLOADS -> R.string.nav_section_downloads
        NavSection.ME -> R.string.nav_section_me
    }

/** Подпись раздела на языке интерфейса. */
@Composable
fun navSectionLabel(section: NavSection): String = stringResource(section.labelRes)

/** Иконка раздела. Единый источник для нижней панели, боковой панели TV и превью. */
fun navSectionIcon(section: NavSection): ImageVector = when (section) {
    NavSection.HOME -> Icons.Filled.Home
    NavSection.FAVORITES -> Icons.Filled.Favorite
    NavSection.WATCHLIST -> Icons.Filled.WatchLater
    NavSection.GENRES -> Icons.AutoMirrored.Filled.List
    NavSection.HISTORY -> Icons.Filled.History
    NavSection.DOWNLOADS -> Icons.Filled.Download
    NavSection.ME -> Icons.Filled.Person
}
