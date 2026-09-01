package com.yourapp.audiobook.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.ui.rememberAppImageLoader
import com.yourapp.audiobook.ui.tvFocus

private val CoverPlaceholder = ColorPainter(Color(0xFFE8E6E3))
private val CoverError = ColorPainter(Color(0xFFD2CFCC))

/**
 * Горизонтальная подборка книг (секция «Популярное», «Новинки», «Цикл» и т.п.)
 * — аналог подборок в исходном приложении «Аудиокниги».
 */
@Composable
fun BookSectionRow(title: String, books: List<Book>, onBookClick: (Book) -> Unit) {
    val app = LocalContext.current.applicationContext as? AudioBookApplication
    val deadKeys = if (app != null) {
        app.deadBooksStore.deadKeys.collectAsStateWithLifecycle(initialValue = emptySet()).value
    } else {
        emptySet()
    }
    val visibleBooks = books.filterNot { "${it.sourceId}:${it.id}" in deadKeys }
    if (visibleBooks.isEmpty()) return
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(
                visibleBooks.distinctBy { "${it.sourceId}:${it.id}" },
                key = { "${it.sourceId}:${it.id}" },
            ) { book ->
                MiniBookCard(book = book, onClick = { onBookClick(book) })
            }
        }
    }
}

@Composable
private fun MiniBookCard(book: Book, onClick: () -> Unit) {
    val imageLoader = rememberAppImageLoader()
    Column(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick)
            .tvFocus()
            .padding(bottom = 8.dp),
    ) {
        AsyncImage(
            model = book.coverUrl,
            contentDescription = book.title,
            placeholder = CoverPlaceholder,
            error = CoverError,
            imageLoader = imageLoader,
            modifier = Modifier
                .size(width = 110.dp, height = 150.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
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
