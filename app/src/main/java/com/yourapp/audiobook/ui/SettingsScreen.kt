package com.yourapp.audiobook.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.codekidlabs.storagechooser.Content
import com.codekidlabs.storagechooser.StorageChooser
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.IgnoreSection
import com.yourapp.audiobook.data.LogCollector
import com.yourapp.audiobook.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val themeOptions = listOf(
    SettingsStore.THEME_SYSTEM to "Системная",
    SettingsStore.THEME_LIGHT to "Светлая",
    SettingsStore.THEME_DARK to "Тёмная",
)

private val viewOptions = listOf(
    SettingsStore.VIEW_LIST to "Список",
    SettingsStore.VIEW_GRID to "Сетка в 2 столбца",
)

private val fontOptions = listOf(
    SettingsStore.FONT_SMALL to "Маленький",
    SettingsStore.FONT_MEDIUM to "Средний",
    SettingsStore.FONT_LARGE to "Большой",
)

private enum class BackupAction { BACKUP, RESTORE }

@Composable
private fun IgnoreListRow(
    label: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        icon()
    }
}

@Composable
private fun IgnoreListDialog(
    section: IgnoreSection,
    items: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скрывать ${section.label.lowercase()}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (items.isEmpty()) {
                    Text(
                        "Список пуст",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemove(item) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Убрать из списка")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Название") },
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        if (input.isNotBlank()) {
                            onAdd(input)
                            input = ""
                        }
                    }) {
                        Text("Добавить")
                    }
                }
                Text(
                    "Совпадение по части названия, без учёта регистра",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово")
            }
        },
    )
}

@Composable
fun SettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearDone by remember { mutableStateOf(false) }
    var showCacheConfirm by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showViewDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestorePath by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var logsBusy by remember { mutableStateOf(false) }
    var logsMessage by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<BackupAction?>(null) }
    var ignoreSection by remember { mutableStateOf<IgnoreSection?>(null) }
    val ignoredGenres by app.settingsStore.ignoredFlow(IgnoreSection.GENRE)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val ignoredAuthors by app.settingsStore.ignoredFlow(IgnoreSection.AUTHOR)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val ignoredReaders by app.settingsStore.ignoredFlow(IgnoreSection.READER)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val themeMode by app.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = SettingsStore.THEME_SYSTEM)
    val resumeOnLaunch by app.settingsStore.resumeOnLaunch.collectAsStateWithLifecycle(initialValue = false)
    val viewMode by app.settingsStore.viewMode.collectAsStateWithLifecycle(initialValue = SettingsStore.VIEW_LIST)
    val hideTabLabels by app.settingsStore.hideTabLabels.collectAsStateWithLifecycle(initialValue = false)
    val fontMode by app.settingsStore.fontScale.collectAsStateWithLifecycle(initialValue = SettingsStore.FONT_MEDIUM)
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    val folderUri by app.settingsStore.downloadFolder.collectAsStateWithLifecycle(initialValue = null)
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            scope.launch { app.settingsStore.setDownloadFolder(uri.toString()) }
        }
    }

    fun showBackupChooser() {
        val activity = context as? Activity ?: return
        val content = Content().apply {
            cancelLabel = "Отмена"
            selectLabel = "Выбрать"
            overviewHeading = "Выбор папки"
            createLabel = "Создать"
            folderCreatedToastText = "Папка создана"
            folderErrorToastText = "Ошибка создания папки"
            newFolderLabel = "Новая папка"
            textfieldHintText = "Новая папка"
            textfieldErrorText = "Не может быть пустым"
        }
        @Suppress("DEPRECATION")
        val chooser = StorageChooser.Builder()
            .withContent(content)
            .allowAddFolder(true)
            .withActivity(activity)
            .withMemoryBar(true)
            .withFragmentManager(activity.fragmentManager)
            .withPredefinedPath(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path,
            )
            .allowCustomPath(true)
            .setType(StorageChooser.DIRECTORY_CHOOSER)
            .build()
        chooser.setOnSelectListener { path ->
            scope.launch {
                backupBusy = true
                backupMessage = runCatching {
                    val favorites = app.favoritesStore.snapshot()
                    val history = app.historyStore.snapshot()
                    val progress = app.progressStore.snapshot()
                    val theme = app.settingsStore.themeMode.first()
                    val filePath = app.backupManager.backup(
                        path, favorites, history, progress, theme,
                    ) ?: throw java.io.IOException("Копия не создана")
                    "Резервная копия создана: избранное ${favorites.size}, " +
                        "история ${history.size}, позиций ${progress.size}. $filePath"
                }.getOrElse { "Ошибка: ${it.message}" }
                backupBusy = false
            }
        }
        chooser.show()
    }

    fun showRestoreChooser() {
        val activity = context as? Activity ?: return
        val content = Content().apply {
            cancelLabel = "Отмена"
            selectLabel = "Выбрать"
            overviewHeading = "Выбор файла"
        }
        @Suppress("DEPRECATION")
        val chooser = StorageChooser.Builder()
            .withContent(content)
            .withActivity(activity)
            .withMemoryBar(false)
            .withFragmentManager(activity.fragmentManager)
            .allowCustomPath(true)
            .setType(StorageChooser.FILE_PICKER)
            .build()
        chooser.setOnSelectListener { path ->
            if (path.contains(".json")) {
                pendingRestorePath = path
                showRestoreConfirm = true
            } else {
                backupMessage = "Выберите JSON-файл резервной копии"
            }
        }
        chooser.show()
    }

    fun runPendingAction() {
        when (pendingAction) {
            BackupAction.BACKUP -> showBackupChooser()
            BackupAction.RESTORE -> showRestoreChooser()
            null -> Unit
        }
        pendingAction = null
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            runPendingAction()
        } else {
            backupMessage = "Доступ ко всем файлам не выдан"
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            runPendingAction()
        } else {
            backupMessage = "Разрешение на доступ к хранилищу не выдано"
        }
    }

    fun launchManageStorage() {
        pendingAction?.let {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse("package:${context.packageName}")
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                manageStorageLauncher.launch(intent)
            }
        }
    }

    fun requestStoragePermission() {
        pendingAction?.let {
            val permission = if (it == BackupAction.BACKUP) {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestPermissionLauncher.launch(permission)
        }
    }

    fun onBackupClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingAction = BackupAction.BACKUP
            if (Environment.isExternalStorageManager()) {
                runPendingAction()
            } else {
                launchManageStorage()
            }
        } else {
            pendingAction = BackupAction.BACKUP
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                runPendingAction()
            } else {
                requestStoragePermission()
            }
        }
    }

    fun onRestoreClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingAction = BackupAction.RESTORE
            if (Environment.isExternalStorageManager()) {
                runPendingAction()
            } else {
                launchManageStorage()
            }
        } else {
            pendingAction = BackupAction.RESTORE
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                runPendingAction()
            } else {
                requestStoragePermission()
            }
        }
    }

    val folderName = remember(folderUri) {
        folderUri?.let {
            runCatching {
                DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(it))?.name
            }.getOrNull()
        }
    }
    val themeLabel = themeOptions.firstOrNull { it.first == themeMode }?.second ?: "Системная"
    val viewLabel = viewOptions.firstOrNull { it.first == viewMode }?.second ?: "Список"
    val fontLabel = fontOptions.firstOrNull { it.first == fontMode }?.second ?: "Средний"

    fun sendLogs() {
        if (logsBusy) return
        logsBusy = true
        logsMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LogCollector.collect(context) }
            }
            logsBusy = false
            result.onSuccess { file ->
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val version = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "?"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("vlasov5020@gmail.com"))
                    putExtra(Intent.EXTRA_SUBJECT, "Логи AudioBook v$version")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(Intent.createChooser(intent, "Отправить логи").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    logsMessage = "Логи собраны — выберите приложение для отправки"
                }.onFailure {
                    logsMessage = "Не удалось открыть отправку: ${it.message}"
                }
            }.onFailure {
                logsMessage = "Ошибка сбора логов: ${it.message}"
            }
        }
    }

    fun restoreNow(path: String) {
        scope.launch {
            backupBusy = true
            backupMessage = runCatching {
                val data = app.backupManager.restore(path)
                app.favoritesStore.restore(data.favorites.orEmpty())
                app.historyStore.restore(data.history.orEmpty())
                app.progressStore.import(data.progress.orEmpty())
                data.theme?.let { app.settingsStore.setThemeMode(it) }
                "Восстановлено: избранное ${data.favorites?.size ?: 0}, " +
                    "история ${data.history?.size ?: 0}, позиций ${data.progress?.size ?: 0}"
            }.getOrElse { "Ошибка: ${it.message}" }
            backupBusy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Настройки", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { pickFolder.launch(null) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Папка для скачанных книг", style = MaterialTheme.typography.bodyLarge)
                Text(
                    folderName ?: "Не выбрана — книги сохраняются в личную папку приложения",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.Folder, contentDescription = null)
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showThemeDialog = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Тема оформления", style = MaterialTheme.typography.bodyLarge)
                Text(
                    themeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.Palette, contentDescription = null)
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showViewDialog = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Вид", style = MaterialTheme.typography.bodyLarge)
                Text(
                    viewLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.ViewModule, contentDescription = null)
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Скрыть подписи вкладок", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "На нижней панели останутся только значки",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = hideTabLabels,
                onCheckedChange = { scope.launch { app.settingsStore.setHideTabLabels(it) } },
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showFontDialog = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Шрифт", style = MaterialTheme.typography.bodyLarge)
                Text(
                    fontLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.TextFields, contentDescription = null)
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Воспроизводить последнюю книгу при запуске", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "При открытии приложения автоматически продолжится прослушивание последней книги",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = resumeOnLaunch,
                onCheckedChange = { scope.launch { app.settingsStore.setResumeOnLaunch(it) } },
            )
        }
        HorizontalDivider()
        IgnoreListRow(
            label = "Скрывать жанры",
            subtitle = if (ignoredGenres.isEmpty()) "Книги таких жанров не показывать" else "В списке: ${ignoredGenres.size}",
            icon = { Icon(Icons.Outlined.FilterAltOff, contentDescription = null) },
            onClick = { ignoreSection = IgnoreSection.GENRE },
        )
        HorizontalDivider()
        IgnoreListRow(
            label = "Скрывать авторов",
            subtitle = if (ignoredAuthors.isEmpty()) "Книги таких авторов не показывать" else "В списке: ${ignoredAuthors.size}",
            icon = { Icon(Icons.Outlined.PersonOff, contentDescription = null) },
            onClick = { ignoreSection = IgnoreSection.AUTHOR },
        )
        HorizontalDivider()
        IgnoreListRow(
            label = "Скрывать чтецов",
            subtitle = if (ignoredReaders.isEmpty()) "Книги с такими чтецами не показывать" else "В списке: ${ignoredReaders.size}",
            icon = { Icon(Icons.Outlined.Hearing, contentDescription = null) },
            onClick = { ignoreSection = IgnoreSection.READER },
        )
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !backupBusy) { onBackupClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Создать резервную копию", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Сохранить избранное, историю и прогресс в JSON-файл",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.Backup, contentDescription = null)
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !backupBusy) { onRestoreClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Восстановить из файла", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Загрузить избранное, историю и позиции из копии",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.Restore, contentDescription = null)
        }
        backupMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !logsBusy) { sendLogs() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Отправить логи", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (logsBusy) "Собираю логи..." else
                        "Файл журнала ошибок отправить на vlasov5020@gmail.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.Send, contentDescription = null)
        }
        logsMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCacheConfirm = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Очистить кеш", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Удалить временные файлы обложек и страниц",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.CleaningServices, contentDescription = null)
        }
        if (cacheCleared) {
            Text(
                "Кеш очищен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showClearConfirm = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Очистить прогресс прослушивания", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Удалить сохранённые позиции всех книг",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        if (clearDone) {
            Text(
                "Прогресс очищен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        versionName?.let {
            Text(
                "Версия $it",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Тема оформления") },
            text = {
                Column {
                    themeOptions.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settingsStore.setThemeMode(mode) }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = { scope.launch { app.settingsStore.setThemeMode(mode) } },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Готово")
                }
            },
        )
    }

    if (showViewDialog) {
        AlertDialog(
            onDismissRequest = { showViewDialog = false },
            title = { Text("Вид") },
            text = {
                Column {
                    viewOptions.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settingsStore.setViewMode(mode) }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            RadioButton(
                                selected = viewMode == mode,
                                onClick = { scope.launch { app.settingsStore.setViewMode(mode) } },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showViewDialog = false }) {
                    Text("Готово")
                }
            },
        )
    }

    if (showFontDialog) {
        AlertDialog(
            onDismissRequest = { showFontDialog = false },
            title = { Text("Шрифт") },
            text = {
                Column {
                    fontOptions.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settingsStore.setFontScale(mode) }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            RadioButton(
                                selected = fontMode == mode,
                                onClick = { scope.launch { app.settingsStore.setFontScale(mode) } },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontDialog = false }) {
                    Text("Готово")
                }
            },
        )
    }

    if (showCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showCacheConfirm = false },
            title = { Text("Очистить кеш?") },
            text = { Text("Временные файлы будут удалены. Скачанные книги и настройки не пострадают.") },
            confirmButton = {
                TextButton(onClick = {
                    showCacheConfirm = false
                    cacheCleared = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        }
                        cacheCleared = true
                    }
                }) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCacheConfirm = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    pendingRestorePath?.let { path ->
        AlertDialog(
            onDismissRequest = {
                pendingRestorePath = null
                showRestoreConfirm = false
            },
            title = { Text("Восстановить из копии?") },
            text = { Text("Текущие избранное, история и прогресс прослушивания будут заменены данными из выбранного файла.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestorePath = null
                    showRestoreConfirm = false
                    restoreNow(path)
                }) {
                    Text("Восстановить")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRestorePath = null
                    showRestoreConfirm = false
                }) {
                    Text("Отмена")
                }
            },
        )
    }

    if (ignoreSection != null) {
        val section = ignoreSection ?: IgnoreSection.GENRE
        val items = when (section) {
            IgnoreSection.GENRE -> ignoredGenres
            IgnoreSection.AUTHOR -> ignoredAuthors
            IgnoreSection.READER -> ignoredReaders
        }
        IgnoreListDialog(
            section = section,
            items = items,
            onAdd = { value ->
                scope.launch { app.settingsStore.addIgnored(section, value) }
            },
            onRemove = { value ->
                scope.launch { app.settingsStore.removeIgnored(section, value) }
            },
            onDismiss = { ignoreSection = null },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить прогресс?") },
            text = { Text("Сохранённые позиции прослушивания будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch { app.progressStore.clear() }
                    clearDone = true
                }) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}
