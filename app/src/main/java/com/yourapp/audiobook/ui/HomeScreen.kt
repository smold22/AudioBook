package com.yourapp.audiobook.ui

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.ui.components.BookSectionRow
import com.yourapp.audiobook.ui.components.bookItems
import dev.chrisbanes.haze.hazeSource

@Composable
fun HomeScreen(navController: NavHostController) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val haze = screenHaze()
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedSourceId by app.settingsStore.selectedSourceId.collectAsStateWithLifecycle(initialValue = null)
    var effectiveSourceId by remember { mutableStateOf<String?>(null) }
    val uiMode by app.settingsStore.uiMode.collectAsStateWithLifecycle(initialValue = null)
    val isTv = uiMode == SettingsStore.UI_MODE_TV
    LaunchedEffect(selectedSourceId) {
        effectiveSourceId = app.activeSource()?.id
    }
    val homeGenres by app.settingsStore.homeGenres.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeHomeGenres by viewModel.activeHomeGenres.collectAsStateWithLifecycle()
    val rawViewMode by app.settingsStore.viewMode.collectAsStateWithLifecycle(initialValue = SettingsStore.VIEW_LIST)
    // Сетка в 3 столбца доступна только в горизонтальном режиме и на Android TV.
    val viewMode = if (rawViewMode == SettingsStore.VIEW_GRID3 && !isLandscapeOrTv) SettingsStore.VIEW_GRID else rawViewMode
    val listState = rememberLazyListState()

    val newBooks by viewModel.newBooks.collectAsStateWithLifecycle()

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
        if (state.books.isEmpty() && !state.loading) {
            viewModel.refresh()
        }
    }
    LaunchedEffect(effectiveSourceId) {
        viewModel.refreshForSource(effectiveSourceId)
    }
    LaunchedEffect(homeGenres) {
        viewModel.refresh()
    }
    LaunchedEffect(shouldLoadMore, state.books.size, state.loading) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    // Compute span count for grid layout based on view mode and TV mode
    val spanCount = when {
        viewMode == SettingsStore.VIEW_LIST -> 1
        viewMode == SettingsStore.VIEW_GRID -> 2
        viewMode == SettingsStore.VIEW_GRID3 -> if (isTv) 5 else 3
        else -> 2 // default fallback
    }

    val openBook: (Book) -> Unit = { book ->
        navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hazeSource(haze)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = GlassHeaderHeight + 4.dp, bottom = GlassBottomClearance),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (newBooks.isNotEmpty()) {
                    item(key = "new-books-row") {
                        BookSectionRow(
                            title = "Новинки",
                            books = newBooks.take(8),
                            onBookClick = openBook,
                            onShowAll = { navController.navigate("new") },
                        )
                    }
                }
                item(key = "all-books-title") {
                        Text(
                            text = when {
                                activeHomeGenres.isEmpty() -> "Все книги"
                                activeHomeGenres.size == 1 -> activeHomeGenres.first().name
                                else -> activeHomeGenres.joinToString(", ") { it.name }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                }
                bookItems(
                    books = state.books,
                    viewMode = viewMode,
                    spanCount = spanCount,
                    onBookClick = openBook,
                )
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
                            Button(onClick = { viewModel.refresh() }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Повторить")
                            }
                        }
                    }
                }
            }
        }
        GlassHeader(
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Text(
                "Главная",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            IconButton(onClick = { navController.navigate("search") }) {
                Icon(Icons.Filled.Search, contentDescription = "Поиск")
            }
            GoogleSyncButton()
            IconButton(onClick = { navController.navigate("source") }) {
                Icon(Icons.Filled.Info, contentDescription = "Источники")
            }
        }
    }
}

/**
 * Кнопка синхронизации с Google в шапке главного экрана.
 * Не вошедший пользователь — открывает вход через Google, вошедший — запускает
 * синхронизацию. Во время синхронизации значок вращается.
 */
@Composable
private fun GoogleSyncButton() {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    val syncState by app.syncManager.state.collectAsStateWithLifecycle()
    val googleSignIn = rememberGoogleSignInController()
    val spin = if (syncState.syncing) {
        val transition = rememberInfiniteTransition(label = "syncSpin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "syncRotation",
        )
    } else {
        null
    }
    IconButton(
        onClick = {
            if (syncState.signedIn) {
                app.syncManager.syncNow()
            } else {
                googleSignIn.onSignInClick()
            }
        },
        enabled = googleSignIn.available && !syncState.syncing,
    ) {
        Icon(
            imageVector = Icons.Outlined.Sync,
            contentDescription = "Синхронизация с Google",
            tint = if (syncState.syncing) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            modifier = spin?.let { Modifier.rotate(it.value) } ?: Modifier,
        )
    }
}