package com.yourapp.audiobook.ui.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Единственная точка чтения и записи настроек кастомизации интерфейса.
 *
 * Наружу отдаётся один [StateFlow] с [UiConfig], который раздаётся через
 * [LocalUiConfig]: экраны не подписываются на DataStore напрямую, поэтому
 * предпросмотр в настройках и обычные экраны всегда показывают одно и то же.
 */
class UiConfigViewModel(app: Application) : AndroidViewModel(app) {

    private val store: SettingsStore =
        (app as AudioBookApplication).settingsStore

    private val listDensity: Flow<ListDensity> =
        store.listDensity.map { ListDensity.fromId(it) }

    private val playerElements = combine(
        store.playerShowCover,
        store.playerShowTrackTitle,
        store.playerShowRemaining,
    ) { cover, title, remaining -> Triple(cover, title, remaining) }

    private val navigation = combine(
        store.navOrder,
        store.hiddenSections,
        store.startSection,
    ) { order, hidden, start ->
        Triple(order, hidden, start)
    }

    private val _loaded = MutableStateFlow(false)

    /**
     * Стало ли известно сохранённое значение.
     *
     * Пока оно не пришло, [config] содержит [UiConfig.Default], поэтому корень
     * приложения не строит граф навигации: иначе NavHost навсегда запомнил бы
     * стартовый раздел по умолчанию и проигнорировал бы сохранённый.
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val config: StateFlow<UiConfig> =
        combine(listDensity, playerElements, navigation) { density, player, nav ->
            UiConfig(
                listDensity = density,
                playerShowCover = player.first,
                playerShowTrackTitle = player.second,
                playerShowRemaining = player.third,
                navOrder = UiConfig.normalizeOrder(nav.first.toNavSections()),
                hiddenSections = nav.second.toNavSectionSet(),
                startSection = NavSection.fromRoute(nav.third) ?: NavSection.HOME,
            )
        }.onEach { _loaded.value = true }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = UiConfig.Default,
            )

    fun setListDensity(density: ListDensity) {
        viewModelScope.launch { store.setListDensity(density.id) }
    }

    fun setPlayerShowCover(show: Boolean) {
        viewModelScope.launch { store.setPlayerShowCover(show) }
    }

    fun setPlayerShowTrackTitle(show: Boolean) {
        viewModelScope.launch { store.setPlayerShowTrackTitle(show) }
    }

    fun setPlayerShowRemaining(show: Boolean) {
        viewModelScope.launch { store.setPlayerShowRemaining(show) }
    }

    /** Сохраняет новый порядок разделов. */
    fun setNavOrder(order: List<NavSection>) {
        val merged = UiConfig.normalizeOrder(config.value.withNavOrder(order).navOrder)
        viewModelScope.launch { store.setNavOrder(merged.map { it.route }) }
    }

    fun setSectionHidden(section: NavSection, hidden: Boolean) {
        val current = config.value
        val next = if (hidden) {
            if (!current.canHide(section)) return
            current.hiddenSections + section
        } else {
            current.hiddenSections - section
        }
        // «Я» скрыть нельзя: в настройках такой строки нет, и после восстановления
        // из резервной копии могла остаться старая отметка.
        val allowed = next - NavSection.ME
        viewModelScope.launch { store.setHiddenSections(allowed.map { it.route }.toSet()) }
    }

    fun setStartSection(section: NavSection) {
        viewModelScope.launch { store.setStartSection(section.route) }
    }
}

private fun String.toNavSections(): List<NavSection> =
    split(SettingsStore.SECTION_SEPARATOR)
        .mapNotNull { NavSection.fromRoute(it.trim()) }
        .ifEmpty { NavSection.entries }

private fun String.toNavSectionSet(): Set<NavSection> =
    split(SettingsStore.SECTION_SEPARATOR)
        .mapNotNull { NavSection.fromRoute(it.trim()) }
        .toSet()
