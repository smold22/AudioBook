package com.yourapp.audiobook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.yourapp.audiobook.AudioBookApplication
import kotlinx.coroutines.launch

@Composable
fun SourceScreen(navController: NavHostController) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val allSources = app.sourceRegistry.sources
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val hiddenIds by app.settingsStore.hiddenSources.collectAsStateWithLifecycle(initialValue = emptySet())
    val sources = allSources.filter { it.id !in hiddenIds }

    LaunchedEffect(Unit) {
        selectedId = app.settingsStore.currentSourceId()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Источники", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        Text(
            "Каталог, поиск и жанры используют выбранный источник.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(sources, key = { it.id }) { source ->
                val isSelected = selectedId == source.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                app.settingsStore.setSourceId(source.id)
                                selectedId = source.id
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
}