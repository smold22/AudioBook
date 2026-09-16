package com.yourapp.audiobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourapp.audiobook.data.SettingsStore
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.rememberHazeState

/**
 * Нижний отступ скроллируемого контента, чтобы он не прятался
 * под стеклянной нижней панелью (мини-плеер + вкладки).
 */
val GlassBottomClearance: Dp = 184.dp

/** Высота верхней стеклянной шапки экранов. */
val GlassHeaderHeight: Dp = 60.dp

/**
 * Текущее состояние Haze для экранов внутри [AppNavHost].
 * Позволяет стеклянным элементам (шапки, панели, диалоги) искать источник размытия
 * без явной передачи через параметры.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Возвращает HazeState из [LocalHazeState], а если его нет — создаёт изолированное.
 * Внутри приложения всегда берётся общий стейт из AppNavHost.
 */
@Composable
fun screenHaze(): HazeState = LocalHazeState.current ?: rememberHazeState()

/**
 * Эффект «матового стекла»: размытие фона позади элемента + тонировка цветом surface.
 * На устройствах без поддержки размытия используется полупрозрачная подложка (fallbackTint).
 * Если нет активного состояния размытия (вне AppNavHost), эффект игнорируется.
 */
@Composable
fun Modifier.glass(
    hazeState: HazeState? = LocalHazeState.current,
    shape: Shape = RoundedCornerShape(24.dp),
    blurRadius: Dp = 22.dp,
): Modifier {
    // В режиме Android TV стекло и размытие не используются — плоская подложка.
    if (LocalUiMode.current == SettingsStore.UI_MODE_TV) {
        return this.clip(shape).background(MaterialTheme.colorScheme.surface)
    }
    val state = hazeState ?: return this
    val surface = MaterialTheme.colorScheme.surface
    return this
        .clip(shape)
        .hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = surface.copy(alpha = 0.92f),
                tint = HazeDefaults.tint(surface.copy(alpha = 0.5f)),
                blurRadius = blurRadius,
                noiseFactor = 0.05f,
                fallbackTint = HazeDefaults.tint(surface.copy(alpha = 0.95f)),
            ),
        )
}

/**
 * Верхняя стеклянная «шапка» экрана. Оверлей: контент прокручивается под ней
 * и размывается через [glass].
 */
@Composable
fun GlassHeader(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GlassHeaderHeight)
            .glass(shape = shape, blurRadius = 20.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}