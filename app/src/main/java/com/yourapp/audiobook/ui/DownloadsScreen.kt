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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            appContext.downloadManager.downloadedBooks.collect { books ->
                val entries = books.keys.mapNotNull { bookKey ->
                    runCatching { appContext.downloadManager.offlineDetails(bookKey) }.getOrNull()
                        ?.book
                        ?.let { DownloadedEntry(bookKey, it) }
                }
                _items.value = entries.sortedBy { it.book.title }
            }
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
    val viewMode by app.settingsStore.viewMode.collectAsStateWithLifecycle(initialValue = SettingsStore.VIEW_LIST)
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

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Загрузки", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Нет скачанных книг",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                bookItems(
                    books = visibleItems,
                    viewMode = viewMode,
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
}