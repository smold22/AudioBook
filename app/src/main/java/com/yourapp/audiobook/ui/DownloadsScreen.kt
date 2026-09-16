package com.yourapp.audiobook.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.AuthorGender
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.ui.components.bookItems
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class DownloadedEntry(
    val bookKey: String,
    val book: Book,
)

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app as AudioBookApplication

    private val _items = MutableStateFlow<List<DownloadedEntry>>(emptyList())
    val items: StateFlow<List<DownloadedEntry>> = _items.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                appContext.downloadManager.downloadedBooks,
                appContext.downloadManager.downloadedTracks,
                appContext.downloadManager.metaVersion,
            ) { books, tracks, _ ->
                val keys = books.keys + tracks.keys
                keys.mapNotNull { bookKey ->
                    runCatching { appContext.downloadManager.offlineDetails(bookKey) }.getOrNull()
                        ?.book
                        ?.let { DownloadedEntry(bookKey, it) }
                }.sortedBy { it.book.title }
            }.collect { _items.value = it }
        }
    }

    fun deleteDownloadedBook(bookKey: String) {
        viewModelScope.launch {
            appContext.downloadManager.deleteDownloadedBook(bookKey)
        }
    }
}

@Composable
fun DownloadsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    val viewModel: DownloadsViewModel = viewModel()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val hideFemaleAuthors by app.settingsStore.hideFemaleAuthors.collectAsStateWithLifecycle(initialValue = false)
    val rawViewMode by app.settingsStore.viewMode.collectAsStateWithLifecycle(initialValue = SettingsStore.VIEW_LIST)
    // Сетка в 3 столбца доступна только в горизонтальном режиме и на Android TV.
    val viewMode = if (rawViewMode == SettingsStore.VIEW_GRID3 && !isLandscapeOrTv) SettingsStore.VIEW_GRID else rawViewMode
    val uiMode by app.settingsStore.uiMode.collectAsStateWithLifecycle(initialValue = null)
    val isTv = uiMode == SettingsStore.UI_MODE_TV
    // Compute span count for grid layout based on view mode and TV mode
    val spanCount = when {
        viewMode == SettingsStore.VIEW_LIST -> 1
        viewMode == SettingsStore.VIEW_GRID -> 2
        viewMode == SettingsStore.VIEW_GRID3 -> if (isTv) 5 else 3
        else -> 2 // default fallback
    }
    var pendingDelete by remember { mutableStateOf<DownloadedEntry?>(null) }
    val visibleItems = remember(items, hideFemaleAuthors) {
        AuthorGender.filterFemale(items.map { it.book }, hideFemaleAuthors)
    }

    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    var permissionRequested by remember { mutableStateOf(false) }
    if (!permissionRequested) {
        LaunchedEffect(Unit) {
            permissionRequested = true
            val granted = ContextCompat.checkSelfPermission(context, storagePermission) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                storagePermissionLauncher.launch(storagePermission)
            }
        }
    }

    val haze = screenHaze()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hazeSource(haze)) {

        if (items.isEmpty() || visibleItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (items.isEmpty()) "Нет скачанных книг" else "Все скачанные книги скрыты фильтром",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                    if (items.isEmpty()) {
                        androidx.compose.material3.TextButton(onClick = {
                            app.downloadManager.scanCurrentFolder { count ->
                                val message = when {
                                    count == 0 -> "Книг в папке не найдено"
                                    count == 1 -> "Найдена 1 книга"
                                    else -> "Найдено книг: $count"
                                }
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Сканировать папку")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = GlassHeaderHeight + 4.dp, bottom = GlassBottomClearance),
                modifier = Modifier.fillMaxSize(),
            ) {
                bookItems(
                    books = visibleItems,
                    viewMode = viewMode,
                    spanCount = spanCount,
                    onBookClick = { book ->
                        navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
                    },
                    action = { book ->
                        IconButton(onClick = {
                            pendingDelete = items.firstOrNull { it.bookKey == app.bookCache.keyOf(book) }
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Удалить",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }

        pendingDelete?.let { entry ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Удалить книгу?") },
                text = { Text("«${entry.book.title}» будет удалена вместе с файлами с устройства.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteDownloadedBook(entry.bookKey)
                            pendingDelete = null
                        },
                    ) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text("Отмена")
                    }
                },
            )
        }
        }
        GlassHeader(
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Text("Загрузки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
            IconButton(onClick = {
                app.downloadManager.scanCurrentFolder { count ->
                    val message = when {
                        count == 0 -> "Книг в папке не найдено"
                        count == 1 -> "Найдена 1 книга"
                        else -> "Найдено книг: $count"
                    }
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Сканировать папку", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}