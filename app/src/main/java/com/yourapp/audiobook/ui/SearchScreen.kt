package com.yourapp.audiobook.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.ui.components.bookItems

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(navController: NavHostController) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val viewModel: SearchViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val rawViewMode by app.settingsStore.viewMode.collectAsStateWithLifecycle(initialValue = SettingsStore.VIEW_LIST)
    // Сетка в 3 столбца доступна только в горизонтальном режиме и на Android TV.
    val viewMode = if (rawViewMode == SettingsStore.VIEW_GRID3 && !isLandscapeOrTv) SettingsStore.VIEW_GRID else rawViewMode
    val sources = app.sourceRegistry.sources
    var query by remember { mutableStateOf("") }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val displayCount = when (viewMode) {
                SettingsStore.VIEW_GRID3 -> (state.books.size + 2) / 3
                SettingsStore.VIEW_GRID -> (state.books.size + 1) / 2
                else -> state.books.size
            }
            displayCount >= 5 && lastVisible >= (displayCount - 3)
        }
    }
    LaunchedEffect(shouldLoadMore, state.books.size) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    val groupedBooks = remember(state.books) {
        if (selectedSourceId == null) state.books.groupBy { it.sourceId } else emptyMap()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Поиск") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
            )
            IconButton(onClick = { viewModel.search(query) }) {
                Icon(Icons.Filled.Search, contentDescription = "Поиск")
            }
        }
        ExposedDropdownMenuBox(
            expanded = sourceMenuExpanded,
            onExpandedChange = { sourceMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = sources.firstOrNull { it.id == selectedSourceId }?.name ?: "Все источники",
                onValueChange = {},
                readOnly = true,
                label = { Text("Источник поиска") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceMenuExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            ExposedDropdownMenu(
                expanded = sourceMenuExpanded,
                onDismissRequest = { sourceMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Все источники") },
                    onClick = {
                        sourceMenuExpanded = false
                        selectedSourceId = null
                        viewModel.setSource(null)
                    },
                )
                sources.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(source.name)
                                Text(
                                    source.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            sourceMenuExpanded = false
                            selectedSourceId = source.id
                            viewModel.setSource(source.id)
                        },
                    )
                }
            }
        }

        if (state.books.isEmpty() && !state.loading && state.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Введите запрос, чтобы найти книгу",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (groupedBooks.isNotEmpty()) {
                    groupedBooks.forEach { (sourceId, books) ->
                        val source = sources.firstOrNull { it.id == sourceId }
                        item(key = "header-$sourceId") {
                            Text(
                                text = source?.name ?: sourceId,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        bookItems(
                            books = books,
                            viewMode = viewMode,
                            onBookClick = { book ->
                                navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
                            },
                        )
                    }
                } else {
                    bookItems(
                        books = state.books,
                        viewMode = viewMode,
                        onBookClick = { book ->
                            navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
                        },
                    )
                }
                if (state.loading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                state.error?.let { message ->
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
