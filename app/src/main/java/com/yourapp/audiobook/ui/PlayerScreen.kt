package com.yourapp.audiobook.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import android.content.Context
import android.net.Uri
import android.widget.Toast
import coil3.compose.AsyncImage
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.Bookmark
import com.yourapp.audiobook.player.EqualizerState
import com.yourapp.audiobook.player.NowPlaying
import com.yourapp.audiobook.player.SleepTimer
import com.yourapp.audiobook.player.SleepTimerMode
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.ui.components.DownloadButton
import com.yourapp.audiobook.ui.theme.nowPlayingColor
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
    val watchlistKeys by app.watchlistStore.watchlistKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val nextBookSuggestion by viewModel.nextBookSuggestion.collectAsStateWithLifecycle()
    val equalizerState by viewModel.equalizerState.collectAsStateWithLifecycle()
    var showSleepDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showChaptersDialog by remember { mutableStateOf(false) }
    var showTrackList by remember { mutableStateOf(true) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showReaderDialog by remember { mutableStateOf(false) }
    val trackListState = rememberLazyListState()
    val readerOptions by viewModel.readerOptions.collectAsStateWithLifecycle()
    val readerSearching by viewModel.readerSearching.collectAsStateWithLifecycle()
    val readerError by viewModel.readerError.collectAsStateWithLifecycle()

    LaunchedEffect(readerError) {
        readerError?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeReaderError()
        }
    }

    LaunchedEffect(snapshot.trackIndex) {
        if (snapshot.trackIndex >= 0) {
            trackListState.animateScrollToItem(snapshot.trackIndex)
        }
    }

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
        }

        val playing = nowPlaying
        if (playing == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ничего не играет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val controlsContent: @Composable ColumnScope.() -> Unit = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showSpeedDialog = true }) {
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
                    val tvMode = isTvMode
                    IconButton(onClick = {
                        if (tvMode) {
                            showTrackList = !showTrackList
                        } else {
                            showChaptersDialog = true
                        }
                    }) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "Плейлист")
                    }
                    val seriesUrl = playing.book.seriesUrl?.takeIf { it.isNotBlank() }
                    if (seriesUrl != null) {
                        IconButton(
                            onClick = {
                                val series = playing.book.seriesTitle ?: "Серия"
                                navController.navigate(
                                    "series/${Uri.encode(app.bookCache.keyOf(playing.book))}" +
                                        "?url=${Uri.encode(seriesUrl)}&title=${Uri.encode(series)}"
                                )
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Серия книг")
                        }
                    }
                    val isFavorite = app.bookCache.keyOf(playing.book) in favoriteKeys
                    IconButton(onClick = { scope.launch { app.favoritesStore.toggle(playing.book) } }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Избранное",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val isInWatchlist = app.bookCache.keyOf(playing.book) in watchlistKeys
                    IconButton(onClick = { scope.launch { app.watchlistStore.toggle(playing.book) } }) {
                        Icon(
                            imageVector = if (isInWatchlist) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Буду слушать",
                            tint = if (isInWatchlist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DownloadButton(app, app.bookCache.keyOf(playing.book))
                }
                Spacer(Modifier.height(4.dp))
                PlayerSlider(
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    onSeek = viewModel::seekTo,
                )
                Spacer(Modifier.height(8.dp))
                val currentTrackRemainingMs = (snapshot.durationMs - snapshot.positionMs).coerceAtLeast(0L)
                val subsequentTracksDurationMs = playing.tracks
                    .drop(snapshot.trackIndex + 1)
                    .sumOf { (it.durationSeconds ?: 0) * 1000L }
                val totalBookRemainingMs = if (snapshot.durationMs > 0) {
                    currentTrackRemainingMs + subsequentTracksDurationMs
                } else null

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatMs(snapshot.positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (totalBookRemainingMs != null && totalBookRemainingMs > 0) {
                        Text(
                            text = "Осталось: ${formatMs(totalBookRemainingMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "-${formatMs(currentTrackRemainingMs)}",
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
            if (isTvMode) {
            // ТВ-режим: обложка книги слева на треть экрана, управление справа.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ReaderSelector(
                        currentReader = playing.book.reader,
                        searching = readerSearching,
                        hasChoices = readerOptions.size > 1,
                        onClick = {
                            if (readerOptions.size > 1) {
                                showReaderDialog = true
                            } else {
                                Toast.makeText(context, "Других чтецов не найдено", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    AsyncImage(
                        model = playing.book.coverUrl,
                        contentDescription = playing.book.title,
                        imageLoader = app.imageLoader,
                        modifier = Modifier
                            .size(width = 220.dp, height = 330.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(12.dp))
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
                }
                Spacer(Modifier.width(24.dp))
                Column(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight(),
                ) {
                    // Область плейлиста над управлением. Занимает всё свободное место,
                    // а кнопки управления всегда остаются внизу.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        if (showTrackList) {
                            TrackList(
                                tracks = playing.tracks,
                                currentIndex = snapshot.trackIndex,
                                listState = trackListState,
                                onSelect = { controller.playTrack(it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    PlayerControls(
                        modifier = Modifier.fillMaxWidth(),
                        content = controlsContent,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ReaderSelector(
                    currentReader = playing.book.reader,
                    searching = readerSearching,
                    hasChoices = readerOptions.size > 1,
                    onClick = {
                        if (readerOptions.size > 1) {
                            showReaderDialog = true
                        } else {
                            Toast.makeText(context, "Других чтецов не найдено", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = playing.book.coverUrl,
                        contentDescription = playing.book.title,
                        imageLoader = app.imageLoader,
                        modifier = Modifier
                            .fillMaxHeight(0.88f)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val currentTrack = playing.tracks.getOrNull(snapshot.trackIndex)
                Text(
                    text = currentTrack?.title ?: playing.book.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = playing.book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PlayerControls(
                    modifier = Modifier.fillMaxWidth(),
                    content = controlsContent,
                )
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
    if (showChaptersDialog) {
        nowPlaying?.let { playing ->
            ChaptersDialog(
                tracks = playing.tracks,
                currentIndex = snapshot.trackIndex,
                listState = trackListState,
                onSelect = { index ->
                    controller.playTrack(index)
                    showChaptersDialog = false
                },
                onDismiss = { showChaptersDialog = false },
            )
        }
    }
    if (showSpeedDialog) {
        SpeedDialog(
            currentSpeed = snapshot.speed,
            onSelect = { speed ->
                viewModel.setSpeed(speed)
                showSpeedDialog = false
            },
            onDismiss = { showSpeedDialog = false },
        )
    }
    if (showReaderDialog) {
        ReaderPickerDialog(
            options = readerOptions,
            onSelect = { option ->
                viewModel.switchReader(option.book)
                showReaderDialog = false
            },
            onDismiss = { showReaderDialog = false },
        )
    }
    nextBookSuggestion?.let { nextBook ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissNextBookSuggestion() },
            title = { Text("Следующая книга в серии") },
            text = {
                Column {
                    Text(
                        text = "Книга закончилась. Хотите начать воспроизведение следующей книги серии?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = nextBook.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val authorOrReader = listOfNotNull(nextBook.author, nextBook.reader).joinToString(" · ")
                    if (authorOrReader.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = authorOrReader,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.playNextBook() },
                ) {
                    Text("Слушать")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissNextBookSuggestion() },
                ) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun TrackList(
    tracks: List<AudioTrack>,
    currentIndex: Int,
    listState: LazyListState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentColor = nowPlayingColor()
    LazyColumn(
        modifier = modifier,
        state = listState,
    ) {
        itemsIndexed(tracks, key = { index, track -> "${track.url}#$index" }) { index, track ->
            val isCurrent = index == currentIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(index) }
                    .tvFocus()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isCurrent) currentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) currentColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
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

@Composable
private fun ChaptersDialog(
    tracks: List<AudioTrack>,
    currentIndex: Int,
    listState: LazyListState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Главы") },
        text = {
            TrackList(
                tracks = tracks,
                currentIndex = currentIndex,
                listState = listState,
                onSelect = onSelect,
                modifier = Modifier.heightIn(max = 420.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun SpeedDialog(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Скорость воспроизведения") },
        text = {
            Column {
                speeds.forEach { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(speed) }
                            .tvFocus()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatSpeed(speed),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (speed == currentSpeed) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Выбрано",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово")
            }
        },
    )
}

@Composable
private fun ReaderSelector(
    currentReader: String?,
    searching: Boolean,
    hasChoices: Boolean,
    onClick: () -> Unit,
) {
    val reader = currentReader?.trim().orEmpty().ifBlank { "Выбрать чтеца" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .tvFocus()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = "Выбор чтеца: $reader",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.tvFocus(),
        )
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ReaderPickerDialog(
    options: List<ReaderOption>,
    onSelect: (ReaderOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбор чтеца") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .tvFocus()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = option.book.reader?.trim().orEmpty().ifBlank { "Чтец" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (option.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            val subtitle = listOfNotNull(
                                option.book.author,
                                option.book.durationText,
                            ).joinToString(" · ")
                            if (subtitle.isNotBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (option.isCurrent) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Текущий чтец",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово")
            }
        },
    )
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
                                .tvFocus()
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
                if (!state.available || state.maxLevelMb <= state.minLevelMb) {
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
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
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
private fun PlayerControls(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        content()
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .tvSliderDpadFocus(),
    ) {
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
}

/**
 * На Android TV слайдер перехватывает кнопки Вверх/Вниз (перемотка),
 * из-за чего фокус застревает на нём. Модификатор перехватывает эти
 * клавиши на предке слайдера и перемещает фокус, оставляя Влево/Вправо
 * для перемотки.
 */
@Composable
private fun Modifier.tvSliderDpadFocus(): Modifier {
    if (!isTvMode) return this
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    focusManager.moveFocus(FocusDirection.Up)
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusManager.moveFocus(FocusDirection.Down)
                    true
                }
                else -> false
            }
        } else {
            false
        }
    }
}
