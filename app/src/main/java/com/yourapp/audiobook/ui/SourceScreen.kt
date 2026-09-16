package com.yourapp.audiobook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

@Composable
fun SourceScreen(navController: NavHostController) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val allSources = app.sourceRegistry.sources
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val hiddenIds by app.settingsStore.hiddenSources.collectAsStateWithLifecycle(initialValue = emptySet())
    val sources = allSources.filter { it.id !in hiddenIds }
    val status by app.sourceHealth.status.collectAsStateWithLifecycle(initialValue = emptyMap())
    val currentlyChecking by app.sourceHealth.currentlyChecking.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(Unit) {
        selectedId = app.settingsStore.currentSourceId()
        // Start sequential check of all sources on screen load
        scope.launch {
            app.sourceHealth.checkAllSequentially(sources)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hazeSource(screenHaze())) {
            Text(
                "Каталог, поиск и жанры используют выбранный источник.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(top = GlassHeaderHeight),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = GlassBottomClearance),
            ) {
                items(sources, key = { it.id }) { source ->
                    val isSelected = selectedId == source.id
                    val isChecking = currentlyChecking == source.id
                    val isKnown = status.containsKey(source.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    app.settingsStore.setSourceId(source.id)
                                    selectedId = source.id
                                    navController.popBackStack()
                                }
                            }
                            .tvFocus()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "ID: ${source.id}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                source.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            when {
                                isChecking -> {
                                    // Show a spinner and checking text
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Проверка...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                isKnown -> {
                                    if (status[source.id] == true) {
                                        Text(
                                            "Работает",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF00C853), // Green
                                        )
                                    } else {
                                        Text(
                                            "Не отвечает",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                else -> {
                                    Text(
                                        "Не проверено",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (isSelected) {
                            Spacer(Modifier.padding(horizontal = 8.dp))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Выбран",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                if (sources.isEmpty()) {
                    item {
                        Text(
                            if (allSources.isEmpty()) "Источники не зарегистрированы"
                            else "Все источники скрыты",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
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
            Text("Источники", style = MaterialTheme.typography.titleLarge)
        }
    }
}