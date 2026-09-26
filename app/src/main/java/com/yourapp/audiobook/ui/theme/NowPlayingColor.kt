package com.yourapp.audiobook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NowPlayingLight = Color(0xFFD32F2F)
private val NowPlayingDark = Color(0xFFFF5252)

/** Красный цвет текущей главы: одинаково контрастный в светлой и тёмной темах. */
@Composable
fun nowPlayingColor(): Color = if (isSystemInDarkTheme()) NowPlayingDark else NowPlayingLight
