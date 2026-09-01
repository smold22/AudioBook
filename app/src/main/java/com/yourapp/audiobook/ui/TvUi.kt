package com.yourapp.audiobook.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.yourapp.audiobook.data.SettingsStore
import kotlin.math.min
import kotlinx.coroutines.launch

/** Цвет обводки фокусированного элемента в режиме Android TV. */
val TvFocusBlue: Color = Color(0xFF2196F3)

/**
 * Режим управления интерфейсом: сенсорный экран или пульт ДУ (Android TV).
 * Значение по умолчанию — сенсорный экран.
 */
val LocalUiMode = compositionLocalOf { SettingsStore.UI_MODE_TOUCH }

val isTvMode: Boolean
    @Composable get() = LocalUiMode.current == SettingsStore.UI_MODE_TV

/**
 * Горизонтальная ориентация экрана или режим Android TV:
 * широкий интерфейс, где доступна сетка в 3 столбца.
 */
val isLandscapeOrTv: Boolean
    @Composable get() =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE || isTvMode

/**
 * Индикация фокуса для режима Android TV: синяя обводка по контуру элемента
 * (с адаптивным скруглением) + лёгкая подсветка при нажатии.
 * Подставляется в [androidx.compose.foundation.LocalIndication], поэтому
 * автоматически применяется ко всем интерактивным элементам
 * ([clickable], кнопкам, иконкам, вкладкам и т.п.).
 */
@Immutable
class FocusBorderIndication(
    val color: Color = TvFocusBlue,
    val borderWidth: Dp = 3.dp,
    val cornerRadius: Dp = 12.dp,
    val fillAlpha: Float = 0.12f,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusBorderIndicationNode(interactionSource, this)

    override fun equals(other: Any?): Boolean =
        other is FocusBorderIndication &&
            other.color == color &&
            other.borderWidth == borderWidth &&
            other.cornerRadius == cornerRadius &&
            other.fillAlpha == fillAlpha

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + borderWidth.hashCode()
        result = 31 * result + cornerRadius.hashCode()
        result = 31 * result + fillAlpha.hashCode()
        return result
    }
}

private class FocusBorderIndicationNode(
    private val interactionSource: InteractionSource,
    private val indication: FocusBorderIndication,
) : Modifier.Node(), DrawModifierNode {

    private var isFocused by mutableStateOf(false)
    private var isPressed by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> isFocused = true
                    is FocusInteraction.Unfocus -> isFocused = false
                    is PressInteraction.Press -> isPressed = true
                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> isPressed = false
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!isFocused) return
        val maxRadius = min(indication.cornerRadius.toPx(), size.minDimension / 2f)
        val halfWidth = indication.borderWidth.toPx() / 2f
        val fillColor = indication.color.copy(alpha = indication.fillAlpha + if (isPressed) 0.15f else 0f)
        drawRoundRect(
            color = fillColor,
            cornerRadius = CornerRadius(maxRadius),
        )
        inset(halfWidth, halfWidth) {
            drawRoundRect(
                color = indication.color,
                cornerRadius = CornerRadius((maxRadius - halfWidth).coerceAtLeast(0f)),
                style = Stroke(width = indication.borderWidth.toPx()),
            )
        }
    }
}

/**
 * Плавное увеличение элемента при фокусе (для пульта ДУ).
 * Обводку рисует [FocusBorderIndication], поэтому здесь добавляется только масштаб.
 */
@Composable
fun Modifier.tvFocus(enabled: Boolean = true): Modifier {
    if (!enabled || LocalUiMode.current != SettingsStore.UI_MODE_TV) return this
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.03f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "tvFocusScale",
    )
    return onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = scale; scaleY = scale }
}