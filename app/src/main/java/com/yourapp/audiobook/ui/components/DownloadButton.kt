package com.yourapp.audiobook.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.download.DownloadStatus

@Composable
fun rememberDownloadStarter(app: AudioBookApplication, bookKey: String): () -> Unit {
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) app.downloadManager.startDownload(context, bookKey)
    }
    return {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            app.downloadManager.startDownload(context, bookKey)
        }
    }
}

/** Стартер скачивания одного трека книги. */
@Composable
fun rememberTrackDownloadStarter(app: AudioBookApplication, bookKey: String): (Int) -> Unit {
    val context = LocalContext.current
    var pendingIndex by remember { mutableStateOf(-1) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingIndex >= 0) {
            app.downloadManager.startDownloadTrack(context, bookKey, pendingIndex)
        }
    }
    return { index ->
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            pendingIndex = index
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            app.downloadManager.startDownloadTrack(context, bookKey, index)
        }
    }
}

@Composable
fun DownloadButton(app: AudioBookApplication, bookKey: String) {
    val states by app.downloadManager.states.collectAsStateWithLifecycle()
    val downloadedKeys by app.downloadManager.downloadedKeys.collectAsStateWithLifecycle()
    val state = states[bookKey]
    val downloaded = bookKey in downloadedKeys

    val startDownload = rememberDownloadStarter(app, bookKey)

    when {
        downloaded -> IconButton(onClick = {}, enabled = false) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Книга скачана",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        state?.status == DownloadStatus.DOWNLOADING -> IconButton(onClick = { app.downloadManager.cancel(bookKey) }) {
            CircularProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
        }
        state?.status == DownloadStatus.ERROR -> IconButton(onClick = startDownload) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Ошибка скачивания, нажмите для повтора",
                tint = MaterialTheme.colorScheme.error,
            )
        }
        else -> {
            val anyDownloading = states.values.any { it.status == DownloadStatus.DOWNLOADING }
            IconButton(onClick = startDownload, enabled = !anyDownloading) {
                Icon(Icons.Filled.Download, contentDescription = "Скачать книгу")
            }
        }
    }
}