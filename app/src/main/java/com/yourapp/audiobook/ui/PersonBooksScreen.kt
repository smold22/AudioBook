package com.yourapp.audiobook.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.ui.components.bookItems
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.platform.LocalContext

@Composable
fun PersonBooksScreen(personName: String, mode: String, navController: NavHostController) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val viewModel: PersonBooksViewModel = viewModel(
        key = "$mode:$personName",
        factory = PersonBooksViewModelFactory(app, personName, mode),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchingIn by viewModel.searchingIn.collectAsStateWithLifecycle(initialValue = null)
    val listState = rememberLazyListState()
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
    LaunchedEffect(Unit) {
        if (state.books.isEmpty() && !state.loading) viewModel.refresh()
    }
    LaunchedEffect(shouldLoadMore, state.books.size, state.loading) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    val prefix = if (mode == "reader") "Чтец" else "Автор"

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hazeSource(screenHaze())) {

        if (state.books.isEmpty() && !state.loading && state.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = GlassHeaderHeight + 4.dp, bottom = GlassBottomClearance),
                modifier = Modifier.fillMaxSize(),
            ) {
                bookItems(
                    books = state.books,
                    viewMode = viewMode,
                    spanCount = spanCount,
                    onBookClick = { book ->
                        navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
                    },
                )
                if (state.loading) {
                    item(key = "searching") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = searchingIn?.let { "Ищу в источнике: $it…" } ?: "Ищу…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                            Button(onClick = { viewModel.refresh() }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Повторить")
                            }
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
            Text(
                text = "$prefix: $personName",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}