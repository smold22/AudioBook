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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
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
import com.yourapp.audiobook.source.api.AudioTrack
import com.yourapp.audiobook.ui.components.BookSectionRow
import com.yourapp.audiobook.ui.components.DownloadButton
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
                }
                DownloadButton(app, bookKey)
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
                item {
                    Text(
                        "Главы (${details.tracks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (details.tracks.isEmpty()) {
                    item {
                        Text(
                            "Треки недоступны: книга удалена или доступен только ознакомительный фрагмент",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                itemsIndexed(details.tracks, key = { index, track -> "${track.url}#$index" }) { index, track ->
                    TrackRow(
                        track = track,
                        index = index,
                        isCurrent = index == state.currentTrackIndex,
                        onClick = { viewModel.playTrack(index) },
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
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = book?.coverUrl,
                contentDescription = book?.title,
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
                        modifier = Modifier.clickable { onAuthorClick(author) },
                    )
                }
                book?.reader?.let { reader ->
                    Text(
                        text = "Читает: $reader",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onReaderClick(reader) },
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Слушать",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}