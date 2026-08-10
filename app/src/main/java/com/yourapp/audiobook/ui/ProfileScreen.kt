package com.yourapp.audiobook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

@Composable
fun ProfileScreen(navController: NavHostController) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Я",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        ProfileRow(
            title = "Избранное",
            subtitle = "Отмеченные книги",
            icon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
            onClick = { navController.navigate("favorites") },
        )
        HorizontalDivider()
        ProfileRow(
            title = "Загрузки",
            subtitle = "Скачанные книги",
            icon = { Icon(Icons.Filled.Download, contentDescription = null) },
            onClick = { navController.navigate("downloads") },
        )
        HorizontalDivider()
        ProfileRow(
            title = "Настройки",
            subtitle = "Тема, кеш, бэкап",
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = { navController.navigate("settings") },
        )
        HorizontalDivider()
    }
}

@Composable
private fun ProfileRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}