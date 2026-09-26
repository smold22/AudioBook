package com.yourapp.audiobook.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.ImageFetchResult
import coil3.request.Options

/**
 * Генерирует локальную обложку-заглушку для книг без обложки (например, MDS).
 * Адрес вида: mdscover://{название} — из названия рисуются инициалы на цветном фоне.
 */
class InitialsCoverFetcher(
    private val title: String,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bitmap = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val (background, textColor) = paletteFor(title)

        canvas.drawColor(background)
        drawInitials(canvas, title, textColor)

        return ImageFetchResult(
            image = bitmap.asImage(shareable = true),
            isSampled = true,
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            if (data.scheme != "mdscover") return null
            val title = (data.authority ?: data.path ?: "").trim()
            if (title.isEmpty()) return null
            return InitialsCoverFetcher(title)
        }
    }
}

private const val COVER_WIDTH = 360
private const val COVER_HEIGHT = 540

private fun drawInitials(canvas: Canvas, title: String, textColor: Int) {
    val words = title.split(Regex("[\\s,\\-—\\(\\).!?;:\"']+"))
        .filter { it.isNotBlank() }
    val initials = StringBuilder()
    if (words.size >= 2) {
        words.take(3).forEach { word ->
            initials.append(word.take(1))
        }
    } else {
        val word = words.firstOrNull() ?: title
        val letters = word.filter { it.isLetterOrDigit() }
        initials.append(letters.take(2))
    }
    val text = initials.toString().uppercase()
    if (text.isEmpty()) return

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = when (text.length) {
            1 -> COVER_WIDTH * 0.42f
            2 -> COVER_WIDTH * 0.36f
            else -> COVER_WIDTH * 0.28f
        }
    }
    val bounds = Rect()
    textPaint.getTextBounds(text, 0, text.length, bounds)
    val x = (COVER_WIDTH - bounds.width()) / 2f - bounds.left
    val baseline = COVER_HEIGHT / 2f - (bounds.top + bounds.bottom) / 2f
    canvas.drawText(text, x, baseline, textPaint)
}

private fun paletteFor(title: String): Pair<Int, Int> {
    val index = (title.hashCode() % PALETTE.size).let { if (it < 0) it + PALETTE.size else it }
    val background = PALETTE[index]
    val textColor = if (luminance(background) > 0.55f) 0xFF2D3436.toInt() else 0xFFFFFFFF.toInt()
    return background to textColor
}

private fun luminance(color: Int): Float {
    val r = (color shr 16 and 0xFF) / 255f
    val g = (color shr 8 and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    return 0.299f * r + 0.587f * g + 0.114f * b
}

private val PALETTE = listOf(
    0xFF6C5CE7.toInt(),
    0xFFE17055.toInt(),
    0xFF00B894.toInt(),
    0xFF0984E3.toInt(),
    0xFFD63031.toInt(),
    0xFFE84393.toInt(),
    0xFFFDCB6E.toInt(),
    0xFF636E72.toInt(),
    0xFF00CEC9.toInt(),
    0xFFA29BFE.toInt(),
    0xFFFAB1A0.toInt(),
    0xFF55EFC4.toInt(),
)