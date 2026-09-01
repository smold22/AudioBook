package com.yourapp.audiobook.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.yourapp.audiobook.player.NowPlaying
import com.yourapp.audiobook.ui.rememberAppImageLoader
import com.yourapp.audiobook.ui.tvFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun rememberIsPlaying(player: ExoPlayer): Boolean {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    LaunchedEffect(player) {
        while (isActive) {
            isPlaying = player.isPlaying
            delay(500)
        }
    }
    return isPlaying
}

@Composable
fun MiniPlayer(
    nowPlaying: NowPlaying,
    player: ExoPlayer,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val isPlaying = rememberIsPlaying(player)
    val imageLoader = rememberAppImageLoader()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .tvFocus()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = nowPlaying.book.coverUrl,
            contentDescription = nowPlaying.book.title,
            imageLoader = imageLoader,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = nowPlaying.book.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = nowPlaying.book.author.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
            )
        }
    }
}