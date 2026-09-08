package com.yourapp.audiobook.ui

import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.download.DownloadService
import com.yourapp.audiobook.download.DownloadStatus
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.ui.components.BookSectionRow
import com.yourapp.audiobook.ui.components.DownloadButton
import com.yourapp.audiobook.ui.components.rememberDownloadStarter
import com.yourapp.audiobook.ui.components.rememberTrackDownloadStarter
import kotlinx.coroutines.launch

class BookViewModelFactory(
    private val app: AudioBookApplication,
    private val bookKey: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BookViewModel(app, bookKey) as T
}

@Composable
fun BookScreen(bookKey: String, navController: NavHostController) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val viewModel: BookViewModel = viewModel(
        key = bookKey,
        factory = BookViewModelFactory(app, bookKey),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val favoriteKeys by app.favoritesStore.favoriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    val isFavorite = bookKey in favoriteKeys
    val watchlistKeys by app.watchlistStore.watchlistKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    val isInWatchlist = bookKey in watchlistKeys
    val downloadStates by app.downloadManager.states.collectAsStateWithLifecycle()
    val downloadedKeys by app.downloadManager.downloadedKeys.collectAsStateWithLifecycle()
    val downloadedTracks by app.downloadManager.downloadedTracks.collectAsStateWithLifecycle()
    val startDownload = rememberDownloadStarter(app, bookKey)
    val startTrackDownload = rememberTrackDownloadStarter(app, bookKey)

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
                Text("Книга", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(4.dp))
                state.book?.let { book ->
                    IconButton(onClick = { scope.launch { app.favoritesStore.toggle(book) } }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Избранное",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { scope.launch { app.watchlistStore.toggle(book) } }) {
                        Icon(
                            imageVector = if (isInWatchlist) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Буду слушать",
                            tint = if (isInWatchlist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DownloadButton(app, bookKey)
                }
            }
        }

        when {
        state.error != null && state.details == null -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                Button(onClick = { viewModel.load() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Повторить")
                }
            }
        }

        state.details == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        else -> {
            val details = state.details!!
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { BookHeader(
                    state = state,
                    fallbackTitle = details.book.title,
                    onAuthorClick = { author ->
                        navController.navigate("person/${Uri.encode(author)}?mode=author")
                    },
                    onReaderClick = { reader ->
                        navController.navigate("person/${Uri.encode(reader)}?mode=reader")
                    },
                ) }
                val series = state.book?.seriesTitle
                if (series != null && state.book?.seriesUrl != null) {
                    item(key = "seriesTitle") {
                        Text(
                            text = "Серия: $series",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    state.book?.seriesUrl?.let { seriesUrl ->
                                        navController.navigate(
                                            "series/${Uri.encode(app.bookCache.keyOf(state.book!!))}?url=${Uri.encode(seriesUrl)}&title=${Uri.encode(series)}",
                                        )
                                    }
                                }
                                .tvFocus()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (state.related.isNotEmpty()) {
                    item(key = "related") {
                        BookSectionRow(title = "Похожие книги", books = state.related) { book ->
                            navController.navigate("book/${Uri.encode(app.bookCache.keyOf(book))}")
                        }
                    }
                }
                if (details.tracks.isNotEmpty()) {
                    state.progress?.let { progress ->
                        item {
                            Button(
                                onClick = { viewModel.continuePlayback() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    "Продолжить с главы ${progress.trackIndex + 1} · ${formatMs(progress.positionMs)}",
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        if (details.tracks.isEmpty() && details.torrentUrl != null) {
                            "Скачать и слушать"
                        } else {
                            "Главы (${details.tracks.size})"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (details.tracks.isEmpty()) {
                    item {
                        if (details.torrentUrl != null) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    "Книга распространяется через торрент. После завершения загрузки книга станет доступна для прослушивания.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                val state = downloadStates[bookKey]
                                val downloaded = bookKey in downloadedKeys
                                Button(
                                    onClick = startDownload,
                                    enabled = !downloaded && state?.status != DownloadStatus.DOWNLOADING,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    when {
                                        downloaded -> Text("Книга скачана")
                                        state?.status == DownloadStatus.DOWNLOADING -> {
                                            CircularProgressIndicator(
                                                progress = { (state?.percent ?: 0) / 100f },
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text("Скачивание ${state?.percent ?: 0}%")
                                        }
                                        state?.status == DownloadStatus.ERROR -> Text("Повторить скачивание")
                                        else -> Text("Скачать и слушать")
                                    }
                                }
                            }
                        } else {
                            Text(
                                "Треки недоступны: книга удалена или доступен только ознакомительный фрагмент",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                itemsIndexed(details.tracks, key = { index, track -> "${track.url}#$index" }) { index, track ->
                    TrackRow(
                        track = track,
                        index = index,
                        isCurrent = index == state.currentTrackIndex,
                        onClick = { viewModel.playTrack(index) },
                        downloaded = bookKey in downloadedKeys ||
                            DownloadService.trackFileName(index, track) in downloadedTracks[bookKey].orEmpty(),
                        downloadEnabled = downloadStates[bookKey]?.status != DownloadStatus.DOWNLOADING,
                        onDownload = { startTrackDownload(index) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun BookHeader(
    state: BookScreenState,
    fallbackTitle: String,
    onAuthorClick: (String) -> Unit,
    onReaderClick: (String) -> Unit,
) {
    val book = state.book
    val imageLoader = rememberAppImageLoader()
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = book?.coverUrl,
                contentDescription = book?.title,
                imageLoader = imageLoader,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                book?.seriesTitle?.let { series ->
                    Text(
                        text = series + (book.seriesIndex?.let { " · Книга $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = book?.title ?: fallbackTitle,
                    style = MaterialTheme.typography.headlineSmall,
                )
                book?.genre?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                book?.author?.let { author ->
                    Text(
                        text = "Автор: $author",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onAuthorClick(author) }.tvFocus(),
                    )
                }
                book?.reader?.let { reader ->
                    Text(
                        text = "Читает: $reader",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onReaderClick(reader) }.tvFocus(),
                    )
                }
                book?.durationText?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.details?.description?.let { description ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun TrackRow(
    track: AudioTrack,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    downloaded: Boolean,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .tvFocus()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.durationSeconds?.let { duration ->
                Text(
                    text = formatSeconds(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (downloaded) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Трек скачан",
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            IconButton(onClick = onDownload, enabled = downloadEnabled) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Скачать трек",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}