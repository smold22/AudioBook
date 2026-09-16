package com.yourapp.audiobook.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.hsv
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.codekidlabs.storagechooser.Content
import com.codekidlabs.storagechooser.StorageChooser
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.AppIconSwitcher
import com.yourapp.audiobook.R
import com.yourapp.audiobook.UiModeRouter
import com.yourapp.audiobook.data.HomeGenre
import com.yourapp.audiobook.data.IgnoreSection

import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.source.api.Genre
import com.yourapp.audiobook.ui.theme.AccentPalettes
import com.yourapp.audiobook.update.UpdateStatus
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val themeOptions = listOf(
    SettingsStore.THEME_SYSTEM to "Системная",
    SettingsStore.THEME_LIGHT to "Светлая",
    SettingsStore.THEME_DARK to "Тёмная",
)

private val accentOptions = listOf(
    SettingsStore.ACCENT_DYNAMIC to "Динамический (системный)",
    SettingsStore.ACCENT_PURPLE to "Фиолетовый",
    SettingsStore.ACCENT_BLUE to "Синий",
    SettingsStore.ACCENT_CYAN to "Голубой",
    SettingsStore.ACCENT_TEAL to "Бирюзовый",
    SettingsStore.ACCENT_GREEN to "Зелёный",
    SettingsStore.ACCENT_ORANGE to "Оранжевый",
    SettingsStore.ACCENT_RED to "Красный",
    SettingsStore.ACCENT_PINK to "Розовый",
    SettingsStore.ACCENT_INDIGO to "Индиго",
    SettingsStore.ACCENT_BROWN to "Коричневый",
)

private val viewOptions = listOf(
    SettingsStore.VIEW_LIST to "Список",
    SettingsStore.VIEW_GRID to "Сетка в 2 столбца",
    SettingsStore.VIEW_GRID3 to "Сетка в 3 столбца",
)

private val fontOptions = listOf(
    SettingsStore.FONT_SMALL to "Маленький",
    SettingsStore.FONT_MEDIUM to "Средний",
    SettingsStore.FONT_LARGE to "Большой",
    SettingsStore.FONT_VERY_LARGE to "Очень большой",
)

private val underlayOptions = listOf(
    SettingsStore.TAB_UNDERLAY_LOW to "Низкая",
    SettingsStore.TAB_UNDERLAY_DEFAULT to "Стандартная",
    SettingsStore.TAB_UNDERLAY_HIGH to "Высокая",
)

private enum class BackupAction { BACKUP, RESTORE }

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
    var showAccentDialog by remember { mutableStateOf(false) }
    var showIconDialog by remember { mutableStateOf(false) }
    var showViewDialog by remember { mutableStateOf(false) }
    var showHomeGenreDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showUnderlayDialog by remember { mutableStateOf(false) }
    var showUiModeDialog by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestorePath by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<BackupAction?>(null) }
    var showHiddenGenresDialog by remember { mutableStateOf(false) }
    val ignoredGenres by app.settingsStore.ignoredFlow(IgnoreSection.GENRE)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val themeMode by app.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = SettingsStore.THEME_SYSTEM)
    val accentColor by app.settingsStore.accentColor.collectAsStateWithLifecycle(initialValue = SettingsStore.ACCENT_DYNAMIC)
    val appIcon by app.settingsStore.appIcon.collectAsStateWithLifecycle(initialValue = SettingsStore.APP_ICON_DEFAULT)
    val customAccentHex by app.settingsStore.customAccentColor.collectAsStateWithLifecycle(initialValue = null)
    val uiMode by app.settingsStore.uiMode.collectAsStateWithLifecycle(initialValue = SettingsStore.UI_MODE_AUTO)
    val isTvMode = uiMode == SettingsStore.UI_MODE_TV
    val resumeOnLaunch by app.settingsStore.resumeOnLaunch.collectAsStateWithLifecycle(initialValue = false)
    val openPlayerOnLaunch by app.settingsStore.openPlayerOnLaunch.collectAsStateWithLifecycle(initialValue = false)
    val viewModeRaw by app.settingsStore.viewMode.collectAsStateWithLifecycle(initialValue = SettingsStore.VIEW_LIST)
    // Сетка в 3 столбца доступна только в горизонтальном режиме и на Android TV;
    // в портретном режиме такая настройка отображается как обычная сетка.
    val wideView = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE || isTvMode
    val tvDevice = remember(context) { UiModeRouter.isTvDevice(context) }
    val viewMode = if (viewModeRaw == SettingsStore.VIEW_GRID3 && !wideView) SettingsStore.VIEW_GRID else viewModeRaw
    val hideTabLabels by app.settingsStore.hideTabLabels.collectAsStateWithLifecycle(initialValue = false)
    val homeGenres by app.settingsStore.homeGenres.collectAsStateWithLifecycle(initialValue = emptyList())
    val fontMode by app.settingsStore.fontScale.collectAsStateWithLifecycle(initialValue = SettingsStore.FONT_MEDIUM)
    val tabUnderlayHeight by app.settingsStore.tabUnderlayHeight.collectAsStateWithLifecycle(initialValue = SettingsStore.TAB_UNDERLAY_DEFAULT)
    val hideFemaleAuthors by app.settingsStore.hideFemaleAuthors.collectAsStateWithLifecycle(initialValue = false)
    val closeOnBackLongPress by app.settingsStore.closeOnBackLongPress.collectAsStateWithLifecycle(initialValue = false)
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    val folderUri by app.settingsStore.downloadFolder.collectAsStateWithLifecycle(initialValue = null)
    val updateStatus by app.updateManager.status.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            scope.launch { app.settingsStore.setDownloadFolder(uri.toString()) }
            app.downloadManager.scanFolder(uri.toString()) { count ->
                val message = when {
                    count == 0 -> "Книг в папке не найдено"
                    count == 1 -> "Найдена 1 книга"
                    else -> "Найдено книг: $count"
                }
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
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
                    val watchlist = app.watchlistStore.snapshot()
                    val history = app.historyStore.snapshot()
                    val progress = app.progressStore.snapshot()
                    val theme = app.settingsStore.themeMode.first()
                    val filePath = app.backupManager.backup(
                        path, favorites, watchlist, history, progress, theme,
                    ) ?: throw java.io.IOException("Копия не создана")
                    "Резервная копия создана: избранное ${favorites.size}, " +
                        "отложено ${watchlist.size}, история ${history.size}, позиций ${progress.size}. $filePath"
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
    val accentLabel = when (accentColor) {
        SettingsStore.ACCENT_CUSTOM -> "Свой цвет"
        else -> accentOptions.firstOrNull { it.first == accentColor }?.second
            ?: accentOptions.first().second
    }
    val viewLabel = when {
        viewMode == SettingsStore.VIEW_GRID && isTvMode -> "Сетка в 2 столбца"
        viewMode == SettingsStore.VIEW_GRID3 && isTvMode -> "Столбцы"
        else -> viewOptions.firstOrNull { it.first == viewMode }?.second ?: "Список"
   }
    val fontLabel = fontOptions.firstOrNull { it.first == fontMode }?.second ?: "Средний"
    val underlayLabel = underlayOptions.firstOrNull { it.first == tabUnderlayHeight }?.second ?: "Стандартная"
    val uiModeLabel = when (uiMode) {
        SettingsStore.UI_MODE_TV -> "Android TV (пульт ДУ)"
        SettingsStore.UI_MODE_TOUCH -> "Сенсорный экран"
        else -> "Авто (по типу устройства)"
    }

    fun restoreNow(path: String) {
        scope.launch {
            backupBusy = true
            backupMessage = runCatching {
                val data = app.backupManager.restore(path)
                app.favoritesStore.restore(data.favorites.orEmpty())
                app.watchlistStore.restore(data.watchlist.orEmpty())
                app.historyStore.restore(data.history.orEmpty())
                app.progressStore.import(data.progress.orEmpty())
                data.theme?.let { app.settingsStore.setThemeMode(it) }
                "Восстановлено: избранное ${data.favorites?.size ?: 0}, " +
                    "отложено ${data.watchlist?.size ?: 0}, " +
                    "история ${data.history?.size ?: 0}, позиций ${data.progress?.size ?: 0}"
            }.getOrElse { "Ошибка: ${it.message}" }
            backupBusy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .hazeSource(screenHaze())
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(GlassHeaderHeight))
            
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { pickFolder.launch(null) }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Папка для скачанных книг", style = MaterialTheme.typography.bodyLarge)
                Text(
                    folderName ?: "По умолчанию — папка Audiobook",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showThemeDialog = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Palette, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Тема оформления", style = MaterialTheme.typography.bodyLarge)
                Text(
                    themeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
        if (!tvDevice) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAccentDialog = true }
                    .tvFocus()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val accentPreview = when {
                    accentColor == SettingsStore.ACCENT_CUSTOM -> {
                        customAccentHex?.toLongOrNull(16)?.toInt()?.let { Color(it) }
                    }
                    else -> AccentPalettes[accentColor]?.let {
                        if (isSystemInDarkTheme()) it.darkPrimary else it.lightPrimary
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Акцентный цвет", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        accentLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(accentPreview ?: MaterialTheme.colorScheme.primary),
                )
            }
            
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showIconDialog = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Иконка приложения", style = MaterialTheme.typography.bodyLarge)
                Text(
                    AppIconSwitcher.options.firstOrNull { it.key == appIcon }?.label ?: "Стандартная",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Image(
                painter = painterResource(
                    AppIconSwitcher.options.firstOrNull { it.key == appIcon }?.res ?: R.mipmap.ic_launcher,
                ),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showUiModeDialog = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Tv, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Режим управления", style = MaterialTheme.typography.bodyLarge)
                Text(
                    uiModeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showViewDialog = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ViewModule, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Вид", style = MaterialTheme.typography.bodyLarge)
                Text(
                    viewLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showHomeGenreDialog = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Category, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Жанр на главной", style = MaterialTheme.typography.bodyLarge)
                Text(
                    homeGenres.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: "Все книги",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
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
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showFontDialog = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.TextFields, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Шрифт", style = MaterialTheme.typography.bodyLarge)
                Text(
                    fontLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
        if (!tvDevice) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showUnderlayDialog = true }
                    .tvFocus()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ViewModule, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Высота подложки вкладок", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        underlayLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            
        }
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
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Открывать окно плеера при запуске", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "При открытии приложения сразу откроется экран плеера, если что-то играет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = openPlayerOnLaunch,
                onCheckedChange = { scope.launch { app.settingsStore.setOpenPlayerOnLaunch(it) } },
            )
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Закрывать приложение нажатием «Назад»", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Нажатие «Назад» на главных экранах полностью закроет приложение",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = closeOnBackLongPress,
                onCheckedChange = { scope.launch { app.settingsStore.setCloseOnBackLongPress(it) } },
            )
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showHiddenGenresDialog = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FilterAltOff, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Скрывать жанры", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (ignoredGenres.isEmpty()) "Книги таких жанров не показывать" else "В списке: ${ignoredGenres.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Скрывать авторов-женщин", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Определяется по имени и фамилии (например, Анна Иванова). Возможны ошибки на редких именах",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = hideFemaleAuthors,
                onCheckedChange = { scope.launch { app.settingsStore.setHideFemaleAuthors(it) } },
            )
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !backupBusy) { onBackupClick() }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Backup, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Создать резервную копию", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Сохранить избранное, историю и прогресс в JSON-файл",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !backupBusy) { onRestoreClick() }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Восстановить из файла", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Загрузить избранное, историю и позиции из копии",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        backupMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/listen_everyone"))
                        context.startActivity(intent)
                    }
                }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Связаться в Telegram", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "@listen_everyone — поддержка и обратная связь по приложению",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCacheConfirm = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Очистить кеш", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Удалить временные файлы обложек и страниц",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (cacheCleared) {
            Text(
                "Кеш очищен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showClearConfirm = true }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("Очистить прогресс прослушивания", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Удалить сохранённые позиции всех книг",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (clearDone) {
            Text(
                "Прогресс очищен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        
        val updateSubtitle = when (val s = updateStatus) {
            is UpdateStatus.Idle -> "Проверить наличие новой версии"
            is UpdateStatus.Checking -> "Проверка обновлений…"
            is UpdateStatus.UpToDate -> "У вас последняя версия"
            is UpdateStatus.Available -> "Доступна версия ${s.info.versionName} — нажмите"
            is UpdateStatus.Downloading -> if (s.percent >= 0) "Скачивание… ${s.percent}%" else "Скачивание…"
            is UpdateStatus.Ready -> "Обновление скачано — нажмите, чтобы установить"
            is UpdateStatus.Failed -> s.message
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    when (val s = app.updateManager.status.value) {
                        is UpdateStatus.Ready -> app.updateManager.requestInstallPrompt()
                        else -> app.updateManager.checkForUpdates(auto = false)
                    }
                }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text("Обновление приложения", style = MaterialTheme.typography.bodyLarge)
                Text(
                    updateSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val downloading = updateStatus as? UpdateStatus.Downloading
                if (downloading != null) {
                    if (downloading.percent >= 0) {
                        LinearProgressIndicator(
                            progress = { downloading.percent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
            when (val s = updateStatus) {
                is UpdateStatus.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                is UpdateStatus.Downloading ->
                    if (s.percent >= 0) Text(
                        "${s.percent}%",
                        style = MaterialTheme.typography.labelLarge,
                    )
                else -> Spacer(Modifier.size(20.dp))
            }
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
        Spacer(Modifier.height(GlassBottomClearance))
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
                                .tvFocus()
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

    if (showAccentDialog) {
        AccentColorDialog(
            currentAccent = accentColor,
            currentCustomHex = customAccentHex,
            onDismiss = { showAccentDialog = false },
            onApply = { accent, customArgb ->
                scope.launch {
                    app.settingsStore.setAccentColor(accent)
                    if (accent == SettingsStore.ACCENT_CUSTOM && customArgb != null) {
                        app.settingsStore.setCustomAccentColor(customArgb)
                    }
                }
                showAccentDialog = false
            },
        )
    }

    if (showIconDialog) {
        AlertDialog(
            onDismissRequest = { showIconDialog = false },
            title = { Text("Иконка приложения") },
            text = {
                Column {
                    AppIconSwitcher.options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settingsStore.setAppIcon(option.key) }
                                    AppIconSwitcher.apply(context, option.key)
                                    showIconDialog = false
                                }
                                .tvFocus()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(option.res),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                            RadioButton(
                                selected = appIcon == option.key,
                                onClick = {},
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIconDialog = false }) {
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
                    val options = when {
                        isTvMode -> viewOptions
                            .filterNot { it.first == SettingsStore.VIEW_GRID }
                            .map {
                                if (it.first == SettingsStore.VIEW_GRID3) {
                                    it.copy(second = "Столбцы")
                                } else {
                                    it
                                }
                            }
                        wideView -> viewOptions
                        else -> viewOptions.filterNot { it.first == SettingsStore.VIEW_GRID3 }
                    }
                    options.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settingsStore.setViewMode(mode) }
                                }
                                .tvFocus()
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

    if (showHomeGenreDialog) {
        HomeGenreDialog(
            current = homeGenres,
            onDismiss = { showHomeGenreDialog = false },
            onCommit = { genres ->
                scope.launch { app.settingsStore.setHomeGenres(genres) }
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
                                .tvFocus()
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

    if (showUnderlayDialog) {
        AlertDialog(
            onDismissRequest = { showUnderlayDialog = false },
            title = { Text("Высота подложки вкладок") },
            text = {
                Column {
                    underlayOptions.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settingsStore.setTabUnderlayHeight(mode) }
                                }
                                .tvFocus()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            RadioButton(
                                selected = tabUnderlayHeight == mode,
                                onClick = { scope.launch { app.settingsStore.setTabUnderlayHeight(mode) } },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUnderlayDialog = false }) {
                    Text("Готово")
                }
            },
        )
    }

    if (showUiModeDialog) {
        AlertDialog(
            onDismissRequest = { showUiModeDialog = false },
            title = { Text("Режим управления") },
            text = {
                Column {
                    val options = listOf(
                        SettingsStore.UI_MODE_AUTO to "Авто (по типу устройства)",
                        SettingsStore.UI_MODE_TOUCH to "Сенсорный экран",
                        SettingsStore.UI_MODE_TV to "Android TV (пульт ДУ)",
                    )
                    options.forEach { (mode, label) ->
                        val switchMode = {
                            scope.launch {
                                if (mode != uiMode) {
                                    app.settingsStore.setUiMode(mode)
                                    UiModeRouter.launchForMode(context, mode)
                                }
                            }
                            showUiModeDialog = false
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = switchMode)
                                .tvFocus()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            RadioButton(
                                selected = uiMode == mode || (uiMode == null && mode == SettingsStore.UI_MODE_AUTO),
                                onClick = switchMode,
                            )
                        }
                    }
                    Text(
                        "В режиме «Авто» тип определяется по устройству (Android TV — пульт ДУ, остальные — сенсорный экран). " +
                            "В ТВ-режиме элементы получают синюю обводку при навигации с пульта.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showUiModeDialog = false }) {
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

    if (showHiddenGenresDialog) {
        HiddenGenresDialog(
            ignoredGenres = ignoredGenres,
            onToggle = { name ->
                scope.launch {
                    val existing = ignoredGenres.firstOrNull {
                        app.normalizeGenreName(it) == app.normalizeGenreName(name)
                    }
                    if (existing != null) {
                        app.settingsStore.removeIgnored(IgnoreSection.GENRE, existing)
                    } else {
                        app.settingsStore.addIgnored(IgnoreSection.GENRE, name)
                    }
                }
            },
            onDismiss = { showHiddenGenresDialog = false },
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

    GlassHeader(
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
        }
        Text("Настройки", style = MaterialTheme.typography.titleLarge)
    }
    }
}

/**
 * Выбор жанра, книги которого показываются на главном экране.
 * «Все книги» сбрасывает выбор; жанры берутся из текущего источника.
 */
@Composable
private fun HomeGenreDialog(
    current: List<HomeGenre>,
    onDismiss: () -> Unit,
    onCommit: (List<HomeGenre>) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    var sourceId by remember { mutableStateOf<String?>(null) }
    var genres by remember { mutableStateOf<List<Genre>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        genres = null
        error = null
        runCatching {
            val source = app.activeSource()
            sourceId = source?.id
            source?.genres()?.distinctBy { it.url }.orEmpty()
        }.onSuccess { genres = it }
            .onFailure { error = it.message ?: "Ошибка загрузки жанров" }
    }
    fun toggle(genre: Genre) {
        val sid = sourceId ?: return
        val key = app.normalizeGenreName(genre.name)
        val updated = if (current.any { app.normalizeGenreName(it.name) == key }) {
            current.filterNot { app.normalizeGenreName(it.name) == key }
        } else {
            current + HomeGenre(sourceId = sid, url = genre.url, name = genre.name)
        }
        onCommit(updated)
    }
    fun removeByName(name: String) {
        val key = app.normalizeGenreName(name)
        onCommit(current.filterNot { app.normalizeGenreName(it.name) == key })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Жанр на главной") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCommit(emptyList()) }
                        .tvFocus()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = current.isEmpty(),
                        onCheckedChange = { onCommit(emptyList()) },
                    )
                    Text("Все книги", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
                
                val items = genres
                when {
                    items == null && error == null -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    items != null && items.isEmpty() -> {
                        Text(
                            "Список жанров пуст",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items != null -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            items.forEach { genre ->
                                val checked = current.any { app.normalizeGenreName(it.name) == app.normalizeGenreName(genre.name) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggle(genre) }
                                        .tvFocus()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { toggle(genre) },
                                    )
                                    Text(
                                        genre.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            val extra = current.filter { entry ->
                                items.none { app.normalizeGenreName(it.name) == app.normalizeGenreName(entry.name) }
                            }.distinctBy { app.normalizeGenreName(it.name) }
                            if (extra.isNotEmpty()) {
                                
                                Text(
                                    "Выбранные, но отсутствующие в этом источнике",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                extra.forEach { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { removeByName(entry.name) }
                                            .tvFocus()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = true,
                                            onCheckedChange = { removeByName(entry.name) },
                                        )
                                        Text(
                                            entry.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    "Книги отмеченных жанров будут показываться на главном экране вместо всех книг",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

/**
 * Выбор жанров для скрытия: чекбоксы для множественного выбора.
 * Жанры загружаются из текущего источника (как в [HomeGenreDialog]).
 */
@Composable
private fun HiddenGenresDialog(
    ignoredGenres: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    var genres by remember { mutableStateOf<List<Genre>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        genres = null
        error = null
        runCatching {
            val source = app.activeSource()
            source?.genres()?.distinctBy { it.url }?.sortedBy { it.name }.orEmpty()
        }.onSuccess { genres = it }
            .onFailure { error = it.message ?: "Ошибка загрузки жанров" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скрывать жанры") },
        text = {
            Column {
                val items = genres
                when {
                    items == null && error == null -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    items != null && items.isEmpty() -> {
                        Text(
                            "Список жанров пуст",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items != null -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            items.forEach { genre ->
                                val checked = ignoredGenres.any { app.normalizeGenreName(it) == app.normalizeGenreName(genre.name) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggle(genre.name) }
                                        .tvFocus()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { onToggle(genre.name) },
                                    )
                                    Text(
                                        genre.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            val extra = ignoredGenres.filter { name ->
                                items.none { app.normalizeGenreName(it.name) == app.normalizeGenreName(name) }
                            }.sorted()
                            if (extra.isNotEmpty()) {
                                
                                Text(
                                    "Скрытые, но отсутствующие в этом источнике",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                extra.forEach { name ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onToggle(name) }
                                            .tvFocus()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = true,
                                            onCheckedChange = { onToggle(name) },
                                        )
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    "Книги отмеченных жанров не будут отображаться",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

/** Круглый образец цвета с подписью для палитры акцентных цветов. */
@Composable
private fun AccentSwatch(
    accent: String,
    color: Color,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .tvFocus()
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = label,
                    tint = Color.White,
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Преобразование цвета в HSV (оттенок, насыщенность, яркость). */
private fun colorToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val sat = if (max == 0f) 0f else delta / max
    return floatArrayOf(hue, sat, max)
}

/** Диалог выбора акцентного цвета: системный, палитра с прозрачностью и быстрые цвета. */
@Composable
private fun AccentColorDialog(
    currentAccent: String,
    currentCustomHex: String?,
    onDismiss: () -> Unit,
    onApply: (accent: String, customArgb: Int?) -> Unit,
) {
    var pendingAccent by remember { mutableStateOf(currentAccent) }
    val initialColor = if (currentAccent == SettingsStore.ACCENT_CUSTOM) {
        currentCustomHex?.toLongOrNull(16)?.toInt()?.let { Color(it) }
    } else {
        AccentPalettes[currentAccent]?.lightPrimary
    } ?: Color.hsv(270f, 0.6f, 0.8f, 1f)
    val initialHsv = remember(initialColor) { colorToHsv(initialColor) }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var sat by remember { mutableStateOf(initialHsv[1]) }
    var value by remember { mutableStateOf(initialHsv[2]) }
    var alpha by remember { mutableStateOf(initialColor.alpha) }
    val customColor = remember(hue, sat, value, alpha) { Color.hsv(hue, sat, value, alpha) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Акцентный цвет") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AccentSwatch(
                        accent = SettingsStore.ACCENT_DYNAMIC,
                        color = MaterialTheme.colorScheme.primary,
                        selected = pendingAccent == SettingsStore.ACCENT_DYNAMIC,
                        label = "Системный",
                        onClick = { pendingAccent = SettingsStore.ACCENT_DYNAMIC },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Палитра", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SaturationValueArea(
                    hueColor = Color.hsv(hue, 1f, 1f),
                    sat = sat,
                    value = value,
                    onChange = { s, v ->
                        sat = s
                        value = v
                        pendingAccent = SettingsStore.ACCENT_CUSTOM
                    },
                )
                Spacer(Modifier.height(8.dp))
                HueBar(
                    hue = hue,
                    onHueChange = { h ->
                        hue = h
                        pendingAccent = SettingsStore.ACCENT_CUSTOM
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Прозрачность",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(alpha * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AlphaBar(
                    alpha = alpha,
                    color = Color.hsv(hue, sat, value, 1f),
                    onAlphaChange = { a ->
                        alpha = a
                        pendingAccent = SettingsStore.ACCENT_CUSTOM
                    },
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorPreview(customColor)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Выбранный цвет", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            String.format("#%08X", customColor.toArgb()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Быстрый выбор", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                accentOptions
                    .filter { it.first != SettingsStore.ACCENT_DYNAMIC }
                    .chunked(4)
                    .forEach { rowAccents ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            rowAccents.forEach { (accent, label) ->
                                AccentSwatch(
                                    accent = accent,
                                    color = AccentPalettes[accent]?.let {
                                        if (isSystemInDarkTheme()) it.darkPrimary else it.lightPrimary
                                    } ?: MaterialTheme.colorScheme.primary,
                                    selected = pendingAccent == accent,
                                    label = label,
                                    onClick = { pendingAccent = accent },
                                )
                            }
                        }
                    }
                Spacer(Modifier.height(8.dp))
                Text(
                    "«Системный» использует цвет Android 12+ (из обоев), на старых версиях — фиолетовый",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val customArgb = if (pendingAccent == SettingsStore.ACCENT_CUSTOM) {
                    customColor.toArgb()
                } else {
                    null
                }
                onApply(pendingAccent, customArgb)
            }) {
                Text("Готово")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

/** Область насыщенности и яркости выбранного оттенка. */
@Composable
private fun SaturationValueArea(
    hueColor: Color,
    sat: Float,
    value: Float,
    onChange: (sat: Float, value: Float) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        1f - (change.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    onChange(
                        (pos.x / size.width).coerceIn(0f, 1f),
                        1f - (pos.y / size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val x = sat * size.width
        val y = (1f - value) * size.height
        drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(x, y))
        drawCircle(Color.Black, radius = 9.dp.toPx(), center = Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}

/** Горизонтальная полоса оттенков 0–360. */
@Composable
private fun HueBar(hue: Float, onHueChange: (Float) -> Unit) {
    val gradientColors = remember {
        (0..360 step 12).map { Color.hsv(it.toFloat(), 1f, 1f) }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onHueChange((change.position.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    onHueChange((pos.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(gradientColors))
        val x = (hue / 360f) * size.width
        drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(x, size.height / 2))
        drawCircle(Color.Black, radius = 8.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(2.dp.toPx()))
    }
}

/** Ползунок прозрачности выбранного цвета. */
@Composable
private fun AlphaBar(alpha: Float, color: Color, onAlphaChange: (Float) -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onAlphaChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    onAlphaChange((pos.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val cell = 6.dp.toPx()
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) {
                val idx = ((x / cell).toInt() + (y / cell).toInt()) % 2
                drawRect(
                    color = if (idx == 0) Color(0xFF9E9E9E) else Color(0xFFE0E0E0),
                    topLeft = Offset(x, y),
                    size = Size(cell, cell),
                )
                y += cell
            }
            x += cell
        }
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, color)))
        val thumbX = alpha * size.width
        drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(thumbX, size.height / 2))
        drawCircle(Color.Black, radius = 8.dp.toPx(), center = Offset(thumbX, size.height / 2), style = Stroke(2.dp.toPx()))
    }
}

/** Кружок предпросмотра цвета на шахматном фоне (видна прозрачность). */
@Composable
private fun ColorPreview(color: Color) {
    Canvas(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    ) {
        val cell = 6.dp.toPx()
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) {
                val idx = ((x / cell).toInt() + (y / cell).toInt()) % 2
                drawRect(
                    color = if (idx == 0) Color(0xFF9E9E9E) else Color(0xFFE0E0E0),
                    topLeft = Offset(x, y),
                    size = Size(cell, cell),
                )
                y += cell
            }
            x += cell
        }
        drawCircle(color)
    }
}
