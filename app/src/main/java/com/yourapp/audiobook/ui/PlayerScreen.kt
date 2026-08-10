package com.yourapp.audiobook.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import android.widget.Toast
import coil3.compose.AsyncImage
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.Bookmark
import com.yourapp.audiobook.player.EqualizerState
import com.yourapp.audiobook.player.SleepTimer
import com.yourapp.audiobook.player.SleepTimerMode
import com.yourapp.audiobook.ui.components.DownloadButton
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(navController: NavHostController) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val context = LocalContext.current
    val controller = app.playerController
    val viewModel: PlayerViewModel = viewModel()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val nowPlaying by controller.nowPlaying.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val favoriteKeys by app.favoritesStore.favoriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val equalizerState by viewModel.equalizerState.collectAsStateWithLifecycle()
    var showSleepDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.saveProgressNow() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.cycleSpeed() }) {
                    Text(formatSpeed(snapshot.speed))
                }
                sleepTimer?.let { timer ->
                    Text(
                        text = when (timer.mode) {
                            SleepTimerMode.TIME ->
                                formatSleepRemaining((timer.endAtMs ?: 0L) - System.currentTimeMillis())
                            SleepTimerMode.END_OF_CHAPTER -> "До конца главы"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { showSleepDialog = true }) {
                    Icon(Icons.Filled.Bedtime, contentDescription = "Таймер сна")
                }
                IconButton(
                    onClick = {
                        viewModel.addBookmark()
                        Toast.makeText(context, "Закладка добавлена", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = "Сохранить позицию")
                }
                IconButton(onClick = { showBookmarksDialog = true }) {
                    Icon(Icons.Filled.Bookmarks, contentDescription = "Закладки")
                }
                IconButton(onClick = { showEqualizerDialog = true }) {
                    Icon(Icons.Filled.Equalizer, contentDescription = "Эквалайзер")
                }
                nowPlaying?.let { playing ->
                    val isFavorite = app.bookCache.keyOf(playing.book) in favoriteKeys
                    IconButton(onClick = { scope.launch { app.favoritesStore.toggle(playing.book) } }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Избранное",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DownloadButton(app, app.bookCache.keyOf(playing.book))
                }
            }
        }

        val playing = nowPlaying
        if (playing == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ничего не играет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val pulseTransition = rememberInfiniteTransition(label = "coverPulse")
                    val pulse by pulseTransition.animateFloat(
                        initialValue = 0.97f,
                        targetValue = 1.03f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1600, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "coverScale",
                    )
                    AsyncImage(
                        model = playing.book.coverUrl,
                        contentDescription = playing.book.title,
                        modifier = Modifier
                            .size(240.dp)
                            .graphicsLayer {
                                val scale = if (snapshot.isPlaying) pulse else 1f
                                scaleX = scale
                                scaleY = scale
                            }
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.height(20.dp))
                val currentTrack = playing.tracks.getOrNull(snapshot.trackIndex)
                Text(
                    text = currentTrack?.title ?: playing.book.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = playing.book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                PlayerSlider(
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    onSeek = viewModel::seekTo,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatMs(snapshot.positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatMs(snapshot.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущая глава", modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.seekBy(-30_000L) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.Replay30,
                            contentDescription = "Назад 30 секунд",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(80.dp),
                    ) {
                        Icon(
                            imageVector = if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (snapshot.isPlaying) "Пауза" else "Воспроизвести",
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { viewModel.seekBy(30_000L) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Filled.Forward30,
                            contentDescription = "Вперёд 30 секунд",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Следующая глава", modifier = Modifier.size(36.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            HorizontalDivider()
            Text(
                "Главы",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(playing.tracks, key = { index, track -> "${track.url}#$index" }) { index, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { controller.playTrack(index) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (index == snapshot.trackIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (index == snapshot.trackIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (index == snapshot.trackIndex) {
                            Spacer(Modifier.width(8.dp))
                            EqualizerBars(playing = snapshot.isPlaying, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                        }
                        track.durationSeconds?.let {
                            Text(
                                formatSeconds(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (showSleepDialog) {
        SleepTimerDialog(
            activeTimer = sleepTimer,
            onSelectMinutes = { minutes ->
                viewModel.setSleepTimer(minutes)
                showSleepDialog = false
            },
            onSelectChapterEnd = {
                viewModel.setSleepTimerToEndOfChapter()
                showSleepDialog = false
            },
            onDisable = {
                viewModel.setSleepTimer(null)
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false },
        )
    }
    if (showBookmarksDialog) {
        BookmarkDialog(
            bookmarks = bookmarks,
            onSelect = { bookmark ->
                viewModel.seekToBookmark(bookmark)
                showBookmarksDialog = false
            },
            onDelete = viewModel::removeBookmark,
            onDismiss = { showBookmarksDialog = false },
        )
    }
    if (showEqualizerDialog) {
        EqualizerDialog(
            state = equalizerState,
            onSetEnabled = viewModel::setEqualizerEnabled,
            onSetBandLevel = viewModel::setEqualizerBandLevel,
            onReset = viewModel::resetEqualizer,
            onDismiss = { showEqualizerDialog = false },
        )
    }
}

@Composable
private fun EqualizerBars(playing: Boolean, tint: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val barSpecs = listOf(
        Triple(0, 14.dp, 420),
        Triple(180, 20.dp, 520),
        Triple(90, 16.dp, 460),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        barSpecs.forEachIndexed { i, (delayMs, barHeight, periodMs) ->
            val animated by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(periodMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delayMs),
                ),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .graphicsLayer { scaleY = if (playing) animated else 0.4f }
                    .clip(CircleShape)
                    .background(tint),
            )
        }
    }
}

@Composable
private fun SleepTimerDialog(
    activeTimer: SleepTimer?,
    onSelectMinutes: (Int) -> Unit,
    onSelectChapterEnd: () -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(15, 30, 45, 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Таймер сна") },
        text = {
            Column {
                options.forEach { minutes ->
                    val active = activeTimer?.mode == SleepTimerMode.TIME && activeTimer.minutes == minutes
                    TextButton(
                        onClick = { onSelectMinutes(minutes) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (active) "$minutes минут (активен)" else "$minutes минут")
                    }
                }
                val chapterActive = activeTimer?.mode == SleepTimerMode.END_OF_CHAPTER
                TextButton(
                    onClick = onSelectChapterEnd,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (chapterActive) "Дочитать до конца главы (активен)" else "Дочитать до конца главы")
                }
                if (activeTimer != null) {
                    TextButton(
                        onClick = onDisable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Выключить таймер")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun BookmarkDialog(
    bookmarks: List<Bookmark>,
    onSelect: (Bookmark) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Закладки") },
        text = {
            if (bookmarks.isEmpty()) {
                Text(
                    "Нет закладок. Нажмите на иконку закладки, чтобы сохранить позицию",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    bookmarks.forEachIndexed { i, bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(bookmark) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Глава ${bookmark.trackIndex + 1} · ${formatMs(bookmark.positionMs)}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onDelete(bookmark.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Удалить закладку",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (i < bookmarks.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun EqualizerDialog(
    state: EqualizerState,
    onSetEnabled: (Boolean) -> Unit,
    onSetBandLevel: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Эквалайзер") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!state.available) {
                    Text(
                        "Эквалайзер не поддерживается на этом устройстве",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Включить эквалайзер",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = state.enabled, onCheckedChange = onSetEnabled)
                    }
                    state.bands.forEach { band ->
                        Text(
                            bandLabel(band.freqHz),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = band.levelMb.toFloat(),
                            onValueChange = { onSetBandLevel(band.index, it.toInt()) },
                            valueRange = state.minLevelMb.toFloat()..state.maxLevelMb.toFloat(),
                            steps = ((state.maxLevelMb - state.minLevelMb) / 100 - 1).coerceAtLeast(0),
                            enabled = state.enabled,
                        )
                    }
                    TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                        Text("Сбросить")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

private fun bandLabel(freqHz: Int): String =
    if (freqHz >= 1000) {
        "%.1f кГц".format(freqHz / 1000f)
    } else {
        "$freqHz Гц"
    }

private fun formatSleepRemaining(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun PlayerSlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val rangeEnd = durationMs.coerceAtLeast(1L).toFloat()
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Slider(
        value = if (isDragging) dragValue else positionMs.toFloat().coerceIn(0f, rangeEnd),
        onValueChange = {
            isDragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            isDragging = false
            onSeek(dragValue.toLong())
        },
        enabled = durationMs > 0,
        valueRange = 0f..rangeEnd,
        modifier = Modifier.fillMaxWidth(),
    )
}