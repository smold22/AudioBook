package com.yourapp.audiobook.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.yourapp.audiobook.source.api.Book

/** NavController, доступный из любого места дерева композиции приложения. */
val LocalNavController = staticCompositionLocalOf<NavHostController?> { null }

/**
 * Контекстное меню книги, открываемое долгим нажатием на карточку:
 * «Найти все книги автора» и «Найти книги этого чтеца».
 */
@Composable
fun BookContextMenu(book: Book?, onDismiss: () -> Unit) {
    if (book == null) return
    val nav = LocalNavController.current ?: return
    val author = book.author?.takeIf { it.isNotBlank() }
    val reader = book.reader?.takeIf { it.isNotBlank() }
    if (author == null && reader == null) {
        onDismiss()
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (author != null) {
                    BookContextMenuItem(
                        label = "Найти все книги автора",
                        onClick = {
                            onDismiss()
                            nav.navigate("person/${Uri.encode(author)}?mode=author")
                        },
                    )
                }
                if (reader != null) {
                    BookContextMenuItem(
                        label = "Найти книги этого чтеца",
                        onClick = {
                            onDismiss()
                            nav.navigate("person/${Uri.encode(reader)}?mode=reader")
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun BookContextMenuItem(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}