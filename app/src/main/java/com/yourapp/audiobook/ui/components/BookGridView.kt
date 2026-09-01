package com.yourapp.audiobook.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.ui.rememberAppImageLoader
import com.yourapp.audiobook.ui.tvFocus

/**
 * Выводит список книг в ленивом контейнере списком или сеткой
 * (в два столбца, либо в три — в горизонтальном режиме и на Android TV)
 * в зависимости от настройки «Вид».
 */
fun LazyListScope.bookItems(
    books: List<Book>,
    viewMode: String,
    onBookClick: (Book) -> Unit,
    key: (Book) -> Any = { "${it.sourceId}:${it.id}" },
    action: (@Composable (Book) -> Unit)? = null,
) {
    if (viewMode == SettingsStore.VIEW_GRID || viewMode == SettingsStore.VIEW_GRID3) {
        val columns = if (viewMode == SettingsStore.VIEW_GRID3) 3 else 2
        val rows = books.chunked(columns)
        rows.forEachIndexed { rowIndex, row ->
            item(key = "grid-row-" + row.joinToString("|") { key(it).toString() }) {
                BookGridRow(
                    books = row,
                    prefetchBooks = books.drop((rowIndex + 1) * columns).take(PREFETCH_COUNT),
                    onBookClick = onBookClick,
                    action = action,
                )
            }
        }
    } else {
        items(books, key = key) { book ->
            if (action != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        BookCard(book = book, onClick = { onBookClick(book) })
                    }
                    action(book)
                }
            } else {
                BookCard(book = book, onClick = { onBookClick(book) })
            }
        }
    }
}

@Composable
private fun BookGridRow(
    books: List<Book>,
    prefetchBooks: List<Book>,
    onBookClick: (Book) -> Unit,
    action: (@Composable (Book) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    val imageLoader = remember { SingletonImageLoader.get(context) }
    val prefetchUrls = remember(prefetchBooks) {
        prefetchBooks.mapNotNull { it.coverUrl }.joinToString("\u0001")
    }
    LaunchedEffect(prefetchUrls) {
        prefetchUrls.split('\u0001').filter { it.isNotEmpty() }.forEach { url ->
            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        books.forEach { book ->
            BookGridItem(
                book = book,
                onClick = { onBookClick(book) },
                modifier = Modifier.weight(1f),
            ) {
                action?.invoke(book)
            }
        }
    }
}

@Composable
private fun BookGridItem(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val imageLoader = rememberAppImageLoader()
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .tvFocus()
            .padding(bottom = 8.dp),
    ) {
        Box {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = book.title,
                placeholder = CoverPlaceholder,
                error = CoverError,
                imageLoader = imageLoader,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(11f / 15f)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            if (action != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    action()
                }
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        book.author?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        sourceName(book)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val PREFETCH_COUNT = 4

private val CoverPlaceholder = ColorPainter(Color(0xFFE8E6E3))
private val CoverError = ColorPainter(Color(0xFFD2CFCC))