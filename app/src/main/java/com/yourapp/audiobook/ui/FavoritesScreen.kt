package com.yourapp.audiobook.ui

import android.net.Uri
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.ui.components.bookItems
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(navController: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as AudioBookApplication
    val scope = rememberCoroutineScope()
    val favorites by app.favoritesStore.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val deadKeys by app.deadBooksStore.deadKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    val viewMode by app.settingsStore.viewMode.collectAsStateWithLifecycle(initialValue = SettingsStore.VIEW_LIST)
    val visibleFavorites = remember(favorites, deadKeys) {
        favorites.filterNot { "${it.sourceId}:${it.id}" in deadKeys }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Избранное", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()

        if (visibleFavorites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (favorites.isEmpty()) "В избранном пусто" else "В избранном только недоступные книги",
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
                    books = visibleFavorites,
                    viewMode = viewMode,
                    onBookClick = { book ->
                        navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
                    },
                    action = { book ->
                        IconButton(onClick = {
                            scope.launch { app.favoritesStore.remove(app.bookCache.keyOf(book)) }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = "Убрать из избранного",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}