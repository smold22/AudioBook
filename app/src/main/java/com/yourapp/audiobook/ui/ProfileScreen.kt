package com.yourapp.audiobook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.sync.BackupInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfileScreen(navController: NavHostController) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Я",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        ProfileRow(
            title = "Избранное",
            subtitle = "Отмеченные книги",
            icon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
            onClick = { navController.navigate("favorites") },
        )
        HorizontalDivider()
        ProfileRow(
            title = "Буду слушать",
            subtitle = "Отложенные книги",
            icon = { Icon(Icons.Outlined.WatchLater, contentDescription = null) },
            onClick = { navController.navigate("watchlist") },
        )
        HorizontalDivider()
        ProfileRow(
            title = "Настройки",
            subtitle = "Тема, кеш, бэкап",
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { navController.navigate("settings") },
        )
        HorizontalDivider()
        GoogleSyncSection()
    }
}

@Composable
private fun GoogleSyncSection() {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    val syncState by app.syncManager.state.collectAsStateWithLifecycle()
    val googleSignIn = rememberGoogleSignInController()
    val lastSyncLabel = remember(syncState.lastSyncAtMs) {
        syncState.lastSyncAtMs?.let { ts ->
            val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            "Последняя синхронизация: ${format.format(Date(ts))}"
        }
    }
    var showBackupsDialog by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<BackupInfo?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Синхронизация с Google", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    !googleSignIn.available -> "Google Play Services не обнаружены на устройстве"
                    syncState.syncing -> "Синхронизирую..."
                    syncState.signedIn -> lastSyncLabel ?: "Синхронизация ещё не выполнялась"
                    else -> "Избранное, «Буду слушать», история, прогресс, закладки и настройки через Cloud Firestore"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Outlined.Sync, contentDescription = null)
    }
    syncState.lastError?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!syncState.signedIn) {
            OutlinedButton(
                enabled = googleSignIn.available && !syncState.syncing,
                onClick = googleSignIn.onSignInClick,
            ) {
                Text("Войти в Google")
            }
        } else {
            Button(
                enabled = !syncState.syncing && !syncState.restoring,
                onClick = { app.syncManager.syncNow() },
            ) {
                Text(if (syncState.syncing) "Синхронизирую..." else "Синхронизировать сейчас")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { app.syncManager.signOut() }) {
                Text("Выйти")
            }
            if (syncState.restoring) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (syncState.syncing) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    if (syncState.signedIn) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            OutlinedButton(
                enabled = !syncState.syncing && !syncState.restoring,
                onClick = {
                    app.syncManager.refreshBackups()
                    showBackupsDialog = true
                },
            ) {
                Text(
                    if (syncState.restoring) "Восстанавливаю..." else "Восстановить из бэкапа",
                )
            }
        }
    }
    if (showBackupsDialog) {
        BackupsDialog(
            onDismiss = { showBackupsDialog = false },
            onRestore = { backup ->
                showBackupsDialog = false
                confirmRestore = backup
            },
            onDeleteBackup = { app.syncManager.deleteBackup(it.id) },
            onClearAll = { app.syncManager.clearBackups() },
        )
    }
    confirmRestore?.let { backup ->
        ConfirmRestoreDialog(
            backup = backup,
            onDismiss = { confirmRestore = null },
            onConfirm = {
                confirmRestore = null
                app.syncManager.restoreBackup(backup.id)
            },
        )
    }
    HorizontalDivider()
}

@Composable
private fun BackupsDialog(
    onDismiss: () -> Unit,
    onRestore: (BackupInfo) -> Unit,
    onDeleteBackup: (BackupInfo) -> Unit,
    onClearAll: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    val backups by app.syncManager.backups.collectAsStateWithLifecycle()
    var confirmClearAll by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Восстановить из бэкапа") },
        text = {
            Column {
                Text(
                    "Выберите резервную копию по времени — данные на устройстве будут заменены.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.heightIn(min = 12.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    backups.forEach { backup ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRestore(backup) }
                                .tvFocus()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatBackupTime(backup.createdAtMs),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            )
                            IconButton(
                                onClick = { onDeleteBackup(backup) },
                                modifier = Modifier.tvFocus(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Удалить бэкап",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (backups.isEmpty()) {
                    Text(
                        "Пока нет резервных копий. Синхронизация создаёт их автоматически.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (backups.isNotEmpty()) {
                    Spacer(Modifier.heightIn(min = 8.dp))
                    TextButton(
                        onClick = { confirmClearAll = true },
                        modifier = Modifier.tvFocus(),
                    ) {
                        Text("Очистить все бэкапы", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Очистить все бэкапы?") },
            text = {
                Text(
                    "Будут удалены все резервные копии из облака. Восстановление станет невозможным.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearAll = false
                        onClearAll()
                    },
                ) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun ConfirmRestoreDialog(
    backup: BackupInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Восстановить бэкап?") },
        text = {
            Text(
                "Восстановить состояние от ${formatBackupTime(backup.createdAtMs)}?" +
                    " Текущие данные на устройстве будут заменены.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Восстановить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

private fun formatBackupTime(ms: Long): String {
    val format = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
    return format.format(Date(ms))
}

@Composable
private fun ProfileRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .tvFocus()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}