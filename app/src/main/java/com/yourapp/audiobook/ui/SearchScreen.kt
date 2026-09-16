package com.yourapp.audiobook.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.ui.components.bookItems
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(navController: NavHostController) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val viewModel: SearchViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val rawViewMode by app.settingsStore.viewMode.collectAsStateWithLifecycle(initialValue = SettingsStore.VIEW_LIST)
    val uiMode by app.settingsStore.uiMode.collectAsStateWithLifecycle(initialValue = null)
    val isTv = uiMode == SettingsStore.UI_MODE_TV
    // Сетка в 3 столбца доступна только в горизонтальном режиме и на Android TV.
    val viewMode = if (rawViewMode == SettingsStore.VIEW_GRID3 && !isLandscapeOrTv) SettingsStore.VIEW_GRID else rawViewMode
    // Compute span count for grid layout based on view mode and TV mode
    val spanCount = when {
        viewMode == SettingsStore.VIEW_LIST -> 1
        viewMode == SettingsStore.VIEW_GRID -> 2
        viewMode == SettingsStore.VIEW_GRID3 -> if (isTv) 5 else 3
        else -> 2 // default fallback
    }
    val sources = app.sourceRegistry.sources
    val history by app.searchHistory.queries.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hasSearched by remember { mutableStateOf(false) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        hasSearched = true
        scope.launch { app.searchHistory.add(q) }
        viewModel.search(q)
    }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var exactSearch by remember { mutableStateOf(false) }
    var searchByTitle by remember { mutableStateOf(true) }
    var searchByAuthor by remember { mutableStateOf(true) }
    var searchByReader by remember { mutableStateOf(true) }
    var filtersExpanded by remember { mutableStateOf(false) }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val displayCount = when (viewMode) {
                SettingsStore.VIEW_GRID3 -> (state.books.size + 2) / 3
                SettingsStore.VIEW_GRID -> (state.books.size + 1) / 2
                else -> state.books.size
            }
            displayCount >= 5 && lastVisible >= (displayCount / 4)
        }
    }
    LaunchedEffect(shouldLoadMore, state.books.size) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    val groupedBooks = remember(state.books) {
        if (selectedSourceId == null) state.books.groupBy { it.sourceId } else emptyMap()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Spacer(Modifier.height(GlassHeaderHeight))
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
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            )
            IconButton(onClick = { runSearch() }) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { filtersExpanded = !filtersExpanded }
                .tvFocus()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Параметры поиска",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (filtersExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (filtersExpanded) "Свернуть параметры" else "Развернуть параметры",
            )
        }
        AnimatedVisibility(visible = filtersExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = searchByTitle,
                        onCheckedChange = { searchByTitle = it },
                    )
                    Text("По названию", style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = searchByAuthor,
                        onCheckedChange = { searchByAuthor = it },
                    )
                    Text("По автору", style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = searchByReader,
                        onCheckedChange = { searchByReader = it },
                    )
                    Text("По чтецу", style = MaterialTheme.typography.bodyLarge)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { exactSearch = !exactSearch }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = exactSearch,
                        onCheckedChange = { exactSearch = it },
                    )
                    Column {
                        Text("Точный поиск", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Искать целое слово или словосочетание",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        LaunchedEffect(searchByTitle, searchByAuthor, searchByReader) {
            viewModel.setSearchFields(searchByTitle, searchByAuthor, searchByReader)
        }
        LaunchedEffect(exactSearch) {
            viewModel.setExactSearch(exactSearch)
        }

        if (state.books.isEmpty() && !state.loading && state.error == null) {
            if (history.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "История поиска",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        )
                        TextButton(onClick = { scope.launch { app.searchHistory.clear() } }) {
                            Text("Очистить")
                        }
                    }
                    history.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = item
                                    runSearch()
                                }
                                .tvFocus()
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { scope.launch { app.searchHistory.remove(item) } },
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Удалить из истории",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (hasSearched) "Ничего не найдено" else "Введите запрос, чтобы найти книгу",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = GlassBottomClearance),
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
                             spanCount = spanCount,
                             onBookClick = { book ->
                                 navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
                             },
                         )
                    }
                } else {
bookItems(
                         books = state.books,
                         viewMode = viewMode,
                         spanCount = spanCount,
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
        GlassHeader(
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Поиск", style = MaterialTheme.typography.titleLarge)
        }
    }
}
