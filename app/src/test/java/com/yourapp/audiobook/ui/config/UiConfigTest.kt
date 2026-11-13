package com.yourapp.audiobook.ui.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiConfigTest {

    @Test
    fun panelKeepsOnlyHomeGenresHistoryDownloadsAndMe() {
        // «Избранное» и «Буду слушать» живут только в боковой панели ТВ, а на
        // смартфоне открываются из «Я».
        assertEquals(
            listOf(
                NavSection.HOME,
                NavSection.GENRES,
                NavSection.HISTORY,
                NavSection.DOWNLOADS,
                NavSection.ME,
            ),
            UiConfig.Default.visibleSections,
        )
    }

    @Test
    fun tvRailPutsFavoritesAndWatchlistRightAfterHome() {
        assertEquals(
            listOf(
                NavSection.HOME,
                NavSection.FAVORITES,
                NavSection.WATCHLIST,
                NavSection.GENRES,
                NavSection.HISTORY,
                NavSection.DOWNLOADS,
                NavSection.ME,
            ),
            NavSection.TV,
        )
    }

    @Test
    fun tvRailStaysFixedWhilePhonePanelFollowsSettings() {
        // На ТВ настроек разделов нет, поэтому боковая панель не должна зависеть
        // от того, что пользователь переставил или скрыл на смартфоне.
        val config = UiConfig(
            navOrder = listOf(
                NavSection.DOWNLOADS,
                NavSection.ME,
                NavSection.HOME,
                NavSection.GENRES,
                NavSection.HISTORY,
            ),
            hiddenSections = setOf(NavSection.GENRES),
        )

        assertEquals(
            listOf(
                NavSection.DOWNLOADS,
                NavSection.ME,
                NavSection.HOME,
                NavSection.HISTORY,
            ),
            config.visibleSections,
        )
        assertEquals(
            listOf(
                NavSection.HOME,
                NavSection.FAVORITES,
                NavSection.WATCHLIST,
                NavSection.GENRES,
                NavSection.HISTORY,
                NavSection.DOWNLOADS,
                NavSection.ME,
            ),
            NavSection.TV,
        )
        // Разделы только для ТВ в нижнюю панель не попадают ни при каком порядке.
        assertTrue(NavSection.TV.filter { it.tvOnly }.none { it in config.visibleSections })
    }

    @Test
    fun normalizeOrderKeepsSavedOrderAndAppendsNewSections() {
        val order = UiConfig.normalizeOrder(listOf(NavSection.ME, NavSection.HOME))

        assertEquals(listOf(NavSection.ME, NavSection.HOME), order.take(2))
        // Разделы, добавленные в приложение позже, дописываются в конец,
        // иначе они пропали бы из панели до следующего открытия настроек.
        assertEquals(NavSection.entries.toSet(), order.toSet())
    }

    @Test
    fun normalizeOrderDropsDuplicates() {
        val order = UiConfig.normalizeOrder(
            listOf(NavSection.GENRES, NavSection.GENRES, NavSection.HOME),
        )

        assertEquals(NavSection.entries.size, order.size)
        assertEquals(NavSection.entries.size, order.distinct().size)
    }

    @Test
    fun visibleSectionsFollowOrderWithoutHidden() {
        val head = listOf(NavSection.ME, NavSection.HISTORY, NavSection.HOME)
        val order = head + NavSection.CONFIGURED.filterNot { it in head }
        val config = UiConfig(
            navOrder = order,
            hiddenSections = setOf(NavSection.HISTORY),
        )

        assertEquals(order.filterNot { it == NavSection.HISTORY }, config.visibleSections)
    }

    @Test
    fun visibleSectionsNeverDropMeEvenWhenHiddenInStorage() {
        // Раньше «Я» можно было скрыть, и такая отметка могла остаться в
        // настройке после восстановления из резервной копии. Раздел закреплён,
        // поэтому в панели он обязан остаться при любых настройках.
        val config = UiConfig(hiddenSections = NavSection.entries.toSet())

        assertTrue(NavSection.ME in config.visibleSections)
    }

    @Test
    fun meSectionIsNotConfigurable() {
        // В настройках у «Я» нет ни строки, ни чекбокса, как и у разделов,
        // которые видны только на ТВ: настроек разделов на ТВ нет.
        assertFalse(NavSection.ME in NavSection.CONFIGURED)
        assertEquals(
            listOf(NavSection.HOME, NavSection.GENRES, NavSection.HISTORY, NavSection.DOWNLOADS),
            NavSection.CONFIGURED,
        )
        assertTrue(NavSection.entries.containsAll(NavSection.CONFIGURED))
        NavSection.CONFIGURED.forEach { assertFalse(it.name, it.tvOnly) }
    }

    @Test
    fun meSectionCannotBeHidden() {
        val config = UiConfig()

        assertFalse(config.canHide(NavSection.ME))
    }

    @Test
    fun lastConfigurableSectionCannotBeHidden() {
        val config = UiConfig(
            hiddenSections = NavSection.CONFIGURED.toSet() - NavSection.HOME,
        )

        // Остались «Главная» и закреплённое «Я»: скрыть можно только
        // последний настраиваемый раздел, а его трогать нельзя.
        assertFalse(config.canHide(NavSection.HOME))
        assertTrue(NavSection.ME in config.visibleSections)
    }

    @Test
    fun canHideAllowedForConfigurableSectionsWhileMoreVisible() {
        val config = UiConfig()

        NavSection.CONFIGURED.forEach { assertTrue(it.name, config.canHide(it)) }
    }

    @Test
    fun withNavOrderKeepsMeOnItsPlace() {
        // Порядок задаём целиком, чтобы тест не ломался от новых разделов.
        val rest = NavSection.CONFIGURED.filterNot { it == NavSection.HOME }
        val config = UiConfig(
            navOrder = listOf(NavSection.HOME, NavSection.ME) + rest,
        )

        val moved = listOf(NavSection.DOWNLOADS, NavSection.HOME) + rest.filterNot {
            it == NavSection.DOWNLOADS
        }
        val updated = config.withNavOrder(moved)

        // Сравниваем только то, что видно в нижней панели: разделы только для
        // ТВ дописываются в конец порядка и на его месте не стоят.
        assertEquals(
            listOf(NavSection.DOWNLOADS, NavSection.ME, NavSection.HOME) + rest.filterNot {
                it == NavSection.DOWNLOADS
            },
            updated.navOrder.filterNot { it.tvOnly },
        )
    }

    @Test
    fun withNavOrderDropsStoredHiddenMarkOfMe() {
        val config = UiConfig(hiddenSections = setOf(NavSection.ME, NavSection.HISTORY))

        val updated = config.withNavOrder(config.navOrder)

        assertFalse(NavSection.ME in updated.hiddenSections)
        assertTrue(NavSection.HISTORY in updated.hiddenSections)
    }

    @Test
    fun withNavOrderKeepsEverySection() {
        val config = UiConfig()

        val updated = config.withNavOrder(listOf(NavSection.DOWNLOADS, NavSection.HOME))

        assertEquals(NavSection.entries.toSet(), updated.navOrder.toSet())
        assertEquals(NavSection.entries.size, updated.navOrder.size)
    }

    @Test
    fun effectiveStartSectionReplacesHiddenStart() {
        val config = UiConfig(
            navOrder = NavSection.entries,
            hiddenSections = setOf(NavSection.HISTORY),
            startSection = NavSection.HISTORY,
        )

        assertEquals(NavSection.HOME, config.effectiveStartSection)
    }

    @Test
    fun effectiveStartSectionKeepsVisibleChoice() {
        val config = UiConfig(
            hiddenSections = setOf(NavSection.HISTORY),
            startSection = NavSection.DOWNLOADS,
        )

        assertEquals(NavSection.DOWNLOADS, config.effectiveStartSection)
    }

    @Test
    fun defaultsMatchPreviousLookAndBehavior() {
        val config = UiConfig.Default

        assertEquals(ListDensity.NORMAL, config.listDensity)
        assertTrue(config.playerShowCover)
        assertTrue(config.playerShowTrackTitle)
        assertTrue(config.playerShowRemaining)
        assertEquals(NavSection.entries, config.navOrder)
        assertEquals(NavSection.HOME, config.startSection)
    }

    @Test
    fun densityFallsBackToDefaultOnUnknownId() {
        // Неизвестное значение из старой резервной копии не должно ломать запуск.
        assertEquals(ListDensity.NORMAL, ListDensity.fromId("huge"))
        assertEquals(ListDensity.NORMAL, ListDensity.fromId(null))
    }

    @Test
    fun navSectionFromRouteIgnoresUnknownRoute() {
        assertEquals(NavSection.GENRES, NavSection.fromRoute("genres"))
        assertEquals(null, NavSection.fromRoute("settings"))
        assertEquals(null, NavSection.fromRoute(null))
    }
}
