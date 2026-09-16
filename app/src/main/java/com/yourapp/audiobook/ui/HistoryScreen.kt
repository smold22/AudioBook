package com.yourapp.audiobook.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.AuthorGender
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.ui.components.bookItems
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope


@Composable
fun HistoryScreen(navController: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    val history by app.historyStore.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val deadKeys by app.deadBooksStore.deadKeys.collectAsStateWithLifecycle(initialValue = emptySet())
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
    val visibleHistory = remember(history, deadKeys, hideFemaleAuthors) {
        AuthorGender.filterFemale(
            history.map { it.book }.filterNot { "${it.sourceId}:${it.id}" in deadKeys },
            hideFemaleAuthors,
        )
    }
    val haze = screenHaze()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hazeSource(haze)) {
            if (visibleHistory.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            if (history.isEmpty()) "История пуста" else "В истории только недоступные книги",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = GlassHeaderHeight + 4.dp, bottom = GlassBottomClearance),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    bookItems(
                        books = visibleHistory,
                        viewMode = viewMode,
                        spanCount = spanCount,
                        onBookClick = { book ->
                            navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
                        },
                        action = { book ->
                            IconButton(onClick = {
                                scope.launch {
                                    app.historyStore.remove(app.bookCache.keyOf(book))
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Удалить из истории",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }
        }
        GlassHeader(
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Text(
                "История",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("Очистить")
                }
            }
        }
    }

    if (showClearConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить историю?") },
            text = { Text("Список прослушанных книг будет удалён.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch { app.historyStore.clear() }
                }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
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