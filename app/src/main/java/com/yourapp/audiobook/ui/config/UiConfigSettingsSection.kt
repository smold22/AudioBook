package com.yourapp.audiobook.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yourapp.audiobook.R
import com.yourapp.audiobook.ui.isTvMode
import com.yourapp.audiobook.ui.tvFocus
import kotlin.math.roundToInt

/** Высота строки раздела в списке настроек. */
private val NavRowHeight = 60.dp

/**
 * Блок настроек внешнего вида: плотность списков, порядок и состав разделов
 * навигации, стартовый экран, элементы плеера.
 *
 * Состояние берётся из [LocalUiConfig], а запись идёт через
 * [LocalUiConfigViewModel] — тот же, что работает в корне приложения,
 * поэтому предпросмотр и обычные экраны всегда согласованы.
 */
@Composable
fun UiConfigSettingsSection(modifier: Modifier = Modifier) {
    val viewModel = LocalUiConfigViewModel.current
    val config = LocalUiConfig.current
    // На TV перетаскивание неудобно, поэтому порядок меняется стрелками.
    val useArrows = isTvMode
    var showDensityDialog by remember { mutableStateOf(false) }
    var showStartDialog by remember { mutableStateOf(false) }

    Column(modifier) {
        SubHeader(stringResource(R.string.ui_customization_title))
        UiConfigPreview(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        SettingsRow(
            icon = { Icon(Icons.Outlined.ViewAgenda, contentDescription = null) },
            title = stringResource(R.string.list_density_title),
            description = stringResource(R.string.list_density_description),
            value = stringResource(config.listDensity.labelRes),
            onClick = { showDensityDialog = true },
        )

        NavSectionOrderList(
            config = config,
            useArrows = useArrows,
            onOrderChange = viewModel::setNavOrder,
            onVisibilityChange = viewModel::setSectionHidden,
        )

        SettingsRow(
            icon = { Icon(Icons.Outlined.PlayCircle, contentDescription = null) },
            title = stringResource(R.string.start_section_title),
            description = buildString {
                append(stringResource(R.string.start_section_description))
                if (config.startSection in config.hiddenSections) {
                    append(" · ")
                    append(stringResource(R.string.start_section_hidden_fallback))
                }
            },
            value = navSectionLabel(config.startSection),
            onClick = { showStartDialog = true },
        )
        Text(
            text = stringResource(R.string.start_section_applies_on_restart),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        SubHeader(stringResource(R.string.player_elements_title))
        SwitchRow(
            title = stringResource(R.string.player_show_cover),
            description = stringResource(R.string.player_show_cover_description),
            checked = config.playerShowCover,
            onCheckedChange = viewModel::setPlayerShowCover,
        )
        SwitchRow(
            title = stringResource(R.string.player_show_track_title),
            description = stringResource(R.string.player_show_track_title_description),
            checked = config.playerShowTrackTitle,
            onCheckedChange = viewModel::setPlayerShowTrackTitle,
        )
        SwitchRow(
            title = stringResource(R.string.player_show_remaining),
            description = stringResource(R.string.player_show_remaining_description),
            checked = config.playerShowRemaining,
            onCheckedChange = viewModel::setPlayerShowRemaining,
        )
    }

    if (showDensityDialog) {
        ChoiceDialog(
            title = stringResource(R.string.list_density_title),
            options = ListDensity.entries,
            selected = config.listDensity,
            label = { stringResource(it.labelRes) },
            onSelect = {
                viewModel.setListDensity(it)
                showDensityDialog = false
            },
            onDismiss = { showDensityDialog = false },
        )
    }
    if (showStartDialog) {
        ChoiceDialog(
            title = stringResource(R.string.start_section_title),
            // «Избранное» и «Буду слушать» есть только в боковой панели ТВ,
            // поэтому на смартфоне их не предлагаем.
            options = if (isTvMode) NavSection.TV else NavSection.PHONE,
            selected = config.startSection,
            label = { navSectionLabel(it) },
            onSelect = {
                viewModel.setStartSection(it)
                showStartDialog = false
            },
            onDismiss = { showStartDialog = false },
        )
    }
}

@Composable
private fun SubHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Строка настройки: значок, описание и текущее значение справа. */
@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .tvFocus()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 16.dp)) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Диалог выбора одного значения из перечисления. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .tvFocus()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
    )
}

/**
 * Разделы навигации: порядок и видимость.
 *
 * Порядок хранится в [NavOrderDragState], а не берётся напрямую из [config]:
 * запись в DataStore занимает дольше кадра, поэтому при быстром
 * перетаскивании список успевал бы «откатываться» назад. Настройка
 * сохраняется один раз в конце перетаскивания или сразу при нажатии стрелки.
 *
 * Экран настроек уже прокручивается, поэтому здесь обычный [Column]:
 * вложенный вертикальный скролл конфликтовал бы с родительским.
 */
@Composable
private fun NavSectionOrderList(
    config: UiConfig,
    useArrows: Boolean,
    onOrderChange: (List<NavSection>) -> Unit,
    onVisibilityChange: (NavSection, Boolean) -> Unit,
) {
    // «Я» в списке нет: раздел закреплён и не настраивается. Перетаскивание
    // поэтому идёт по укороченному списку, а сохранённый порядок
    // восстанавливается целиком в [UiConfig.withNavOrder].
    val configuredOrder = config.navOrder.filter { it in NavSection.CONFIGURED }
    val dragState = rememberNavOrderDragState(
        initialOrder = configuredOrder,
        onOrderChange = onOrderChange,
    )
    val order = dragState.order

    // Подхватываем внешнее изменение порядка (например, восстановление из
    // резервной копии), но не затираем им ещё не сохранённую правку.
    LaunchedEffect(config.navOrder) {
        val fromConfig = config.navOrder.filter { it in NavSection.CONFIGURED }
        if (fromConfig == order) {
            dragState.isDirty = false
        } else if (!dragState.isDirty) {
            dragState.order = fromConfig
        }
    }

    Column(Modifier.padding(vertical = 4.dp)) {
        order.forEachIndexed { index, section ->
            NavSectionRow(
                section = section,
                isHidden = section in config.hiddenSections,
                canHide = config.canHide(section),
                isStart = section == config.startSection,
                useArrows = useArrows,
                isFirst = index == 0,
                isLast = index == order.lastIndex,
                dragOffset = dragState.offsetFor(index),
                isDragging = dragState.draggingIndex == index,
                onStep = { step ->
                    dragState.move(index, index + step).also { onOrderChange(it) }
                },
                dragState = dragState,
                index = index,
                onVisibilityChange = { onVisibilityChange(section, it) },
            )
        }
        if (config.visibleSections.none { it in NavSection.CONFIGURED }) {
            Text(
                text = stringResource(R.string.nav_section_last_visible),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** Строка раздела: ручка или стрелки, значок, название, чекбокс видимости. */
@Composable
private fun NavSectionRow(
    section: NavSection,
    isHidden: Boolean,
    canHide: Boolean,
    isStart: Boolean,
    useArrows: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    dragOffset: Float,
    isDragging: Boolean,
    onStep: (Int) -> Unit,
    dragState: NavOrderDragState,
    index: Int,
    onVisibilityChange: (Boolean) -> Unit,
) {
    val contentAlpha = if (isHidden) 0.45f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NavRowHeight)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                if (isDragging) {
                    shadowElevation = 8f
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            }
            .background(
                if (isDragging) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                } else {
                    Color.Transparent
                },
            )
            .onGloballyPositioned { dragState.onRowHeightMeasured(it.size.height) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (useArrows) {
            IconButton(onClick = { onStep(-1) }, enabled = !isFirst) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.nav_move_up))
            }
            IconButton(onClick = { onStep(1) }, enabled = !isLast) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.nav_move_down))
            }
        } else {
            // Ручка активна у всех строк: первую можно перетащить вниз,
            // последнюю — вверх, а onDrag сам ограничивает крайние позиции.
            DragHandle(index = index, dragState = dragState)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = navSectionIcon(section),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = navSectionLabel(section),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            if (isStart) {
                Text(
                    text = stringResource(R.string.nav_section_start_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // Скрытый раздел должен снова включаться, поэтому чекбокс неактивен
        // только у последнего видимого — его нельзя скрыть.
        Checkbox(
            checked = !isHidden,
            onCheckedChange = { onVisibilityChange(!it) },
            enabled = isHidden || canHide,
        )
    }
}

/** Ручка перетаскивания: удержание и движение пальцем меняют порядок. */
@Composable
private fun DragHandle(
    index: Int,
    dragState: NavOrderDragState,
) {
    val startDrag = rememberUpdatedState<(Int) -> Unit>(dragState::onDragStart)
    val drag = rememberUpdatedState<(Float) -> Unit>(dragState::onDrag)
    val stopDrag = rememberUpdatedState<() -> Unit>(dragState::onDragEnd)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.small)
            .pointerInput(index) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startDrag.value(index) },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.value(amount.y)
                    },
                    onDragEnd = { stopDrag.value() },
                    onDragCancel = { stopDrag.value() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = stringResource(R.string.nav_drag_handle),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Порядок разделов во время перетаскивания.
 *
 * Хранит рабочий список отдельно от [UiConfig], потому что запись в DataStore
 * дольше кадра: если рисовать по сохранённому порядку, список при быстром
 * перетаскивании дёргался бы назад. Настройка записывается один раз —
 * в конце перетаскивания, — поэтому промежуточные шаги в DataStore не летят.
 */
@Stable
private class NavOrderDragState(
    initialOrder: List<NavSection>,
    private val onOrderChange: (List<NavSection>) -> Unit,
) {
    /** Порядок, который сейчас нарисован на экране. */
    var order by mutableStateOf(initialOrder)

    /** Есть ли несохранённая правка порядка. */
    var isDirty by mutableStateOf(false)

    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** Смещение перетаскиваемой строки в пикселях. */
    private var dragOffset by mutableFloatStateOf(0f)

    /** Высота строки: измеряется один раз, все строки одинаковые. */
    private var rowHeightPx = 0f

    fun onRowHeightMeasured(heightPx: Int) {
        if (rowHeightPx == 0f) rowHeightPx = heightPx.toFloat()
    }

    /** Возвращает [index] в позицию [to] и запоминает результат. */
    fun move(index: Int, to: Int): List<NavSection> {
        if (index !in order.indices || to !in order.indices || index == to) return order
        order = order.toMutableList().apply { add(to, removeAt(index)) }
        isDirty = true
        return order
    }

    fun onDragStart(index: Int) {
        if (rowHeightPx <= 0f) return
        draggingIndex = index
        dragOffset = 0f
    }

    fun onDrag(deltaY: Float) {
        val from = draggingIndex ?: return
        if (rowHeightPx <= 0f) return
        dragOffset += deltaY
        val steps = (dragOffset / rowHeightPx).roundToInt()
        if (steps == 0) return
        val to = (from + steps).coerceIn(0, order.lastIndex)
        if (to != from) {
            move(from, to)
            draggingIndex = to
            // Строка заняла соседний слот, поэтому гасим накопленный сдвиг,
            // иначе она скакнёт на величину перемещения.
            dragOffset -= steps * rowHeightPx
        }
    }

    fun onDragEnd() {
        if (draggingIndex != null && isDirty) onOrderChange(order)
        draggingIndex = null
        dragOffset = 0f
    }

    fun offsetFor(index: Int): Float = if (draggingIndex == index) dragOffset else 0f
}

@Composable
private fun rememberNavOrderDragState(
    initialOrder: List<NavSection>,
    onOrderChange: (List<NavSection>) -> Unit,
): NavOrderDragState = remember {
    NavOrderDragState(initialOrder, onOrderChange)
}
