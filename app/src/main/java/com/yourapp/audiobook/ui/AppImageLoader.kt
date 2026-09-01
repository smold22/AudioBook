package com.yourapp.audiobook.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import com.yourapp.audiobook.AudioBookApplication

/** Общий загрузчик изображений приложения (учитывает метку ref=host в URL обложек). */
@Composable
fun rememberAppImageLoader(): ImageLoader =
    (LocalContext.current.applicationContext as AudioBookApplication).imageLoader