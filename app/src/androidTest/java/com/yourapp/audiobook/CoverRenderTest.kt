package com.yourapp.audiobook

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.Image
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.yourapp.audiobook.source.extra.BazaKnigSource
import com.yourapp.audiobook.source.extra.KnigiAudioNetSource
import com.yourapp.audiobook.ui.InitialsCoverFetcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverRenderTest {

    private val app: AudioBookApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as AudioBookApplication

    private fun load(url: String) = runBlocking {
        withTimeoutOrNull(60_000) {
            app.imageLoader.execute(ImageRequest.Builder(app).data(url).build())
        }
    }

    @Test
    fun mdscoverUrlProducesCoverForExactTitle() {
        val title = "Элита Горскейра 8. Академия Даркленд - Анна Одувалова"
        val url = "mdscover://" + Uri.encode(title, UNRESERVED)
        val result = load(url)
        assertNotNull("mdscover request timed out: $url", result)
        assertTrue("mdscover failed: $result", result is SuccessResult)
        val actual = (result as SuccessResult).image.asAndroidBitmap()
        val expected = (runBlocking { InitialsCoverFetcher(title).fetch() } as ImageFetchResult)
            .image
            .asAndroidBitmap()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertTrue("bitmap differs from direct fetch", samePixels(expected, actual))
    }

    @Test
    fun mdscoverUrlWorksForSingleWordTitle() {
        val result = load("mdscover://" + Uri.encode("Муайто", UNRESERVED))
        assertTrue("mdscover failed: $result", result is SuccessResult)
        val bitmap = (result as SuccessResult).image.asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun remoteCoverWithoutRefererDecodes() {
        val home = runBlocking { withTimeoutOrNull(60_000) { BazaKnigSource().home(1) } }.orEmpty()
        val book = home.firstOrNull { !it.coverUrl.isNullOrBlank() }
        assertNotNull("baza_knig home is empty", book)
        val result = load(book!!.coverUrl!!)
        assertNotNull("cover request timed out", result)
        assertTrue("remote cover failed for ${book.coverUrl}: $result", result is SuccessResult)
        val bitmap = (result as SuccessResult).image.asAndroidBitmap()
        assertTrue("empty bitmap", bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun remoteCoverWithRefParamDecodes() {
        val home = runBlocking { withTimeoutOrNull(60_000) { KnigiAudioNetSource().home(1) } }.orEmpty()
        val book = home.firstOrNull { !it.coverUrl.isNullOrBlank() }
        assertNotNull("knigiaudio_net home is empty", book)
        val coverUrl = book!!.coverUrl!!
        assertTrue("expected ref param in $coverUrl", Uri.parse(coverUrl).getQueryParameter("ref") != null)
        val result = load(coverUrl)
        assertNotNull("cover request timed out", result)
        assertTrue("ref cover failed for $coverUrl: $result", result is SuccessResult)
        val bitmap = (result as SuccessResult).image.asAndroidBitmap()
        assertTrue("empty bitmap", bitmap.width > 0 && bitmap.height > 0)
    }

    private fun Image.asAndroidBitmap(): Bitmap =
        (this as? BitmapImage)?.bitmap ?: toBitmap(width, height)

    private fun samePixels(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false
            }
        }
        return true
    }

    private companion object {
        const val UNRESERVED = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
}
