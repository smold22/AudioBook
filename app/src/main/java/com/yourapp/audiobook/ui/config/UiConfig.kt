package com.yourapp.audiobook.ui.config

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourapp.audiobook.R
import com.yourapp.audiobook.data.SettingsStore

/** Плотность списков: множитель размеров обложек, строк и отступов. */
@Immutable
enum class ListDensity(val id: String, val scale: Float) {
    COMPACT(SettingsStore.DENSITY_COMPACT, 0.84f),
    NORMAL(SettingsStore.DENSITY_NORMAL, 1f),
    SPACIOUS(SettingsStore.DENSITY_SPACIOUS, 1.2f),
    ;

    /** Подпись в настройках. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            COMPACT -> R.string.list_density_compact
            NORMAL -> R.string.list_density_normal
            SPACIOUS -> R.string.list_density_spacious
        }

    companion object {
        val Default = NORMAL

        fun fromId(id: String?): ListDensity = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Раздел навигации. [route] совпадает с маршрутом в `AppNavHost`.
 *
 * «Избранное» и «Буду слушать» помечены [tvOnly]: в боковую панель Android TV
 * они входят сразу после «Главной», а в нижней панели смартфона места нет —
 * на телефоне списки открываются из «Я».
 */
@Immutable
enum class NavSection(val route: String, val tvOnly: Boolean = false) {
    HOME("home"),
    FAVORITES("favorites", tvOnly = true),
    WATCHLIST("watchlist", tvOnly = true),
    GENRES("genres"),
    HISTORY("history"),
    DOWNLOADS("downloads"),
    ME("me"),
    ;

    companion object {
        fun fromRoute(route: String?): NavSection? = entries.firstOrNull { it.route == route }

        /**
         * Разделы боковой панели Android TV — все и в порядке объявления.
         *
         * Порядок и скрытие разделов настраиваются только на смартфоне (на ТВ
         * блок настроек скрыт), поэтому у панели он не зависит.
         */
        val TV: List<NavSection> = entries

        /** Разделы нижней панели смартфона. */
        val PHONE: List<NavSection> = entries.filterNot { it.tvOnly }

        /**
         * Разделы, которые пользователь может двигать и скрывать в настройках.
         *
         * «Я» сюда не входит: его место в панели фиксировано, поэтому в списке
         * разделов настроек такой строки нет. Разделы только для ТВ тоже не
         * входят: настроек разделов на ТВ нет, а список должен совпадать с
         * тем, что реально видно в нижней панели.
         */
        val CONFIGURED: List<NavSection> = PHONE.filterNot { it == ME }
    }
}

/**
 * Всё, чем пользователь кастомизирует внешний вид, в одном иммутабельном объекте.
 * Экземпляр раздаётся через [LocalUiConfig], поэтому любой экран читает ровно
 * те же значения, что и предпросмотр в настройках.
 */
@Immutable
data class UiConfig(
    val listDensity: ListDensity = ListDensity.Default,
    val playerShowCover: Boolean = true,
    val playerShowTrackTitle: Boolean = true,
    val playerShowRemaining: Boolean = true,
    val navOrder: List<NavSection> = NavSection.entries,
    val hiddenSections: Set<NavSection> = emptySet(),
    val startSection: NavSection = NavSection.HOME,
) {
    /** Разделы в заданном порядке, без скрытых. Разделы только для ТВ не входят. */
    val visibleSections: List<NavSection> =
        navOrder.filterNot {
            it.tvOnly || (it in hiddenSections && it in NavSection.CONFIGURED)
        }.ifEmpty { listOf(NavSection.HOME) }

    /** Стартовый раздел с учётом скрытых: скрытый раздел заменяется первым видимым. */
    val effectiveStartSection: NavSection
        get() = if (startSection in visibleSections) startSection else visibleSections.first()

    /**
     * Можно ли скрыть [section].
     *
     * Последний настраиваемый раздел остаётся на месте, иначе в панели не
     * осталось бы ничего, кроме «Я».
     */
    fun canHide(section: NavSection): Boolean =
        section in NavSection.CONFIGURED &&
            section !in hiddenSections &&
            visibleSections.count { it in NavSection.CONFIGURED && it != section } > 0

    /**
     * Возвращает копию с новым порядком настраиваемых разделов.
     *
     * Разделы вне [NavSection.CONFIGURED] остаются на своих местах: порядок из
     * настроек приходит без них, и дописывать их в конец нельзя — «Я» должно
     * сохранить позицию, которая была при настройке.
     */
    fun withNavOrder(configuredOrder: List<NavSection>): UiConfig {
        // Порядок из настроек важнее канонического, поэтому берём его первым,
        // а недостающие разделы дописываем в конце.
        val wanted = (configuredOrder + NavSection.CONFIGURED)
            .filter { it in NavSection.CONFIGURED }
            .distinct()
        val slots = navOrder.count { it in NavSection.CONFIGURED }
        val merged = ArrayList<NavSection>(navOrder.size)
        var next = 0
        for (section in navOrder) {
            if (section in NavSection.CONFIGURED) {
                if (next < slots && next < wanted.size) merged += wanted[next]
                next++
            } else {
                merged += section
            }
        }
        return copy(navOrder = normalizeOrder(merged), hiddenSections = hiddenSections - NavSection.ME)
    }

    companion object {
        val Default = UiConfig()

        /**
         * Приводит сохранённый порядок к полному списку: убирает повторы и
         * дописывает в конец разделы, добавленные в приложение позже.
         */
        fun normalizeOrder(order: List<NavSection>): List<NavSection> {
            val result = LinkedHashSet(order)
            NavSection.entries.forEach { result.add(it) }
            return result.toList()
        }
    }
}

/**
 * Размеры элементов списков, посчитанные из [UiConfig.listDensity].
 * Базовые значения совпадают с теми, что были захардкожены до появления настройки,
 * поэтому [ListDensity.NORMAL] не меняет вёрстку.
 */
@Immutable
data class UiMetrics(
    val listCoverWidth: Dp,
    val listCoverHeight: Dp,
    val listRowHorizontalPadding: Dp,
    val listRowVerticalPadding: Dp,
    val listTextGap: Dp,
    val gridHorizontalPadding: Dp,
    val gridSpacing: Dp,
    val gridItemBottomPadding: Dp,
    val sectionHorizontalPadding: Dp,
    val sectionTitleVerticalPadding: Dp,
    val sectionRowHorizontalPadding: Dp,
    val sectionSpacing: Dp,
    val miniCoverWidth: Dp,
    val miniCoverHeight: Dp,
    val trackHorizontalPadding: Dp,
    val trackVerticalPadding: Dp,
    /** Обложка на странице книги. */
    val bookHeaderCoverWidth: Dp,
    val bookHeaderCoverHeight: Dp,
    /** Обложка в плеере на Android TV. */
    val tvPlayerCoverWidth: Dp,
    val tvPlayerCoverHeight: Dp,
)

/** Базовые размеры до масштабирования плотностью. */
private object BaseMetrics {
    val listCoverWidth = 76.dp
    val listCoverHeight = 114.dp
    val listRowHorizontalPadding = 16.dp
    val listRowVerticalPadding = 8.dp
    val listTextGap = 12.dp
    val gridHorizontalPadding = 16.dp
    val gridSpacing = 10.dp
    val gridItemBottomPadding = 8.dp
    val sectionHorizontalPadding = 16.dp
    val sectionTitleVerticalPadding = 8.dp
    val sectionRowHorizontalPadding = 12.dp
    val sectionSpacing = 10.dp
    val miniCoverWidth = 110.dp
    val miniCoverHeight = 150.dp
    val trackHorizontalPadding = 16.dp
    val trackVerticalPadding = 12.dp
    val bookHeaderCoverWidth = 140.dp
    val bookHeaderCoverHeight = 210.dp
    val tvPlayerCoverWidth = 220.dp
    val tvPlayerCoverHeight = 330.dp
}

/** Текущие размеры элементов списков. */
@Composable
fun uiMetrics(): UiMetrics {
    val scale = LocalUiConfig.current.listDensity.scale
    return remember(scale) { BaseMetrics.scaled(scale) }
}

private fun BaseMetrics.scaled(scale: Float) = UiMetrics(
    listCoverWidth = listCoverWidth * scale,
    listCoverHeight = listCoverHeight * scale,
    listRowHorizontalPadding = listRowHorizontalPadding * scale,
    listRowVerticalPadding = listRowVerticalPadding * scale,
    listTextGap = listTextGap * scale,
    gridHorizontalPadding = gridHorizontalPadding * scale,
    gridSpacing = gridSpacing * scale,
    gridItemBottomPadding = gridItemBottomPadding * scale,
    sectionHorizontalPadding = sectionHorizontalPadding * scale,
    sectionTitleVerticalPadding = sectionTitleVerticalPadding * scale,
    sectionRowHorizontalPadding = sectionRowHorizontalPadding * scale,
    sectionSpacing = sectionSpacing * scale,
    miniCoverWidth = miniCoverWidth * scale,
    miniCoverHeight = miniCoverHeight * scale,
    trackHorizontalPadding = trackHorizontalPadding * scale,
    trackVerticalPadding = trackVerticalPadding * scale,
    bookHeaderCoverWidth = bookHeaderCoverWidth * scale,
    bookHeaderCoverHeight = bookHeaderCoverHeight * scale,
    tvPlayerCoverWidth = tvPlayerCoverWidth * scale,
    tvPlayerCoverHeight = tvPlayerCoverHeight * scale,
)
