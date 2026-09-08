package com.yourapp.audiobook.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourapp.audiobook.update.UpdateManager
import com.yourapp.audiobook.update.UpdateStatus

/**
 * Глобальные диалоги обновления: «доступна новая версия», прогресс
 * скачивания и предложение установить скачанный APK.
 */
@Composable
fun UpdateDialogs(manager: UpdateManager) {
    val context = LocalContext.current
    val status by manager.status.collectAsStateWithLifecycle()
    val promptVersion by manager.promptVersion.collectAsStateWithLifecycle()
    val installPrompt by manager.installPrompt.collectAsStateWithLifecycle()
    var readyDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        if (status !is UpdateStatus.Ready) {
            readyDismissed = false
            manager.dismissInstallPrompt()
        }
    }

    val available = status as? UpdateStatus.Available
    if (available != null && promptVersion != null) {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        AlertDialog(
            onDismissRequest = { manager.dismissPrompt() },
            title = { Text("Доступно обновление ${available.info.versionName}") },
            text = {
                Column {
                    installed?.let {
                        Text(
                            "Установленная версия: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        available.info.changelog.ifBlank { "Описание изменений отсутствует." },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { manager.download(available.info) }) {
                    Text("Скачать")
                }
            },
            dismissButton = {
                Row {
                    if (available.info.releaseUrl.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(available.info.releaseUrl))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) {
                            Text("GitHub")
                        }
                    }
                    TextButton(onClick = { manager.dismissPrompt() }) {
                        Text("Позже")
                    }
                }
            },
        )
    }

    when (val s = status) {
        is UpdateStatus.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Скачивание обновления") },
                text = {
                    Column {
                        if (s.percent >= 0) {
                            LinearProgressIndicator(
                                progress = { s.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${s.percent}%",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Загрузка…",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
                confirmButton = {},
            )
        }
        is UpdateStatus.Ready -> {
            if (installPrompt || !readyDismissed) {
                AlertDialog(
                    onDismissRequest = {
                        readyDismissed = true
                        manager.dismissInstallPrompt()
                    },
                    title = { Text("Обновление скачано") },
                    text = { Text("Установить скачанную версию? Системный установщик откроется поверх приложения.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                readyDismissed = true
                                manager.dismissInstallPrompt()
                                manager.install(s.file)
                            },
                        ) {
                            Text("Установить")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                readyDismissed = true
                                manager.dismissInstallPrompt()
                            },
                        ) {
                            Text("Позже")
                        }
                    },
                )
            }
        }
        else -> Unit
    }
}
