package com.yourapp.audiobook

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.BitmapImage
import coil3.fetch.ImageFetchResult
import com.yourapp.audiobook.source.api.Book
import com.yourapp.audiobook.ui.InitialsCoverFetcher
import com.yourapp.audiobook.ui.components.BookCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookCardCoverRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mdsCoverIsRenderedOnBookCard() {
        val title = "Элита Горскейра 8. Академия Даркленд"
        val coverUrl = "mdscover://" + Uri.encode(title, UNRESERVED)
        val book = Book(
            sourceId = "mds",
            id = "1",
            title = title,
            url = "",
            coverUrl = coverUrl,
        )
        val expected = (runBlocking { InitialsCoverFetcher(title).fetch() } as ImageFetchResult)
            .image as BitmapImage
        val expectedPixel = expected.bitmap.getPixel(expected.bitmap.width / 2, 6)

        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                BookCard(book = book, onClick = {})
            }
        }

        val node = compose.onNodeWithContentDescription(title, useUnmergedTree = true)
        var actualPixel: Int? = null
        for (step in 0 until 80) {
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
            val bmp = runCatching { node.captureToImage().asAndroidBitmap() }.getOrNull()
            if (bmp != null && bmp.getPixel(bmp.width / 2, 6) == expectedPixel) {
                actualPixel = expectedPixel
                break
            }
            actualPixel = bmp?.getPixel(bmp.width / 2, 6)
        }

        assertEquals("cover was not rendered from $coverUrl", expectedPixel, actualPixel)
    }

    private companion object {
        const val UNRESERVED = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
}
