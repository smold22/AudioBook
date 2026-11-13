package com.yourapp.audiobook.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Конфигурация внешнего вида, раздаётся всем экранам из корня приложения.
 *
 * Значения приходят из DataStore, поэтому меняются мгновенно: и обычный экран,
 * и предпросмотр в настройках читают один и тот же [UiConfig].
 */
val LocalUiConfig: ProvidableCompositionLocal<UiConfig> =
    staticCompositionLocalOf { UiConfig.Default }

/** Текущая кастомизация внешнего вида. */
val uiConfig: UiConfig
    @Composable get() = LocalUiConfig.current

/** Разделы нижней навигации, которые нужно показать, в заданном порядке. */
val visibleSections: List<NavSection>
    @Composable get() = LocalUiConfig.current.visibleSections

/**
 * Тот же [UiConfigViewModel], что работает в корне приложения.
 *
 * Экран настроек берёт его отсюда, а не создаёт свой: иначе появился бы второй
 * ViewModel с собственной копией состояния и запись настроек расходилась бы
 * с тем, что читают остальные экраны.
 */
val LocalUiConfigViewModel: ProvidableCompositionLocal<UiConfigViewModel> =
    staticCompositionLocalOf {
        error("LocalUiConfigViewModel не задан: экран должен находиться под AppRoot")
    }
