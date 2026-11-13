package com.yourapp.audiobook.ui
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yourapp.audiobook.AudioBookApplication
import com.yourapp.audiobook.data.SettingsStore
import com.yourapp.audiobook.ui.components.LocalNavController
import com.yourapp.audiobook.ui.components.MiniPlayer
import com.yourapp.audiobook.ui.config.LocalUiConfig
import com.yourapp.audiobook.ui.config.NavSection
import com.yourapp.audiobook.ui.config.navSectionIcon
import com.yourapp.audiobook.ui.config.navSectionLabel
import dev.chrisbanes.haze.rememberHazeState

private data class TabItem(
    val section: NavSection,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** Разделы навигации: иконка. Порядок, видимость и подписи — из настроек. */
private val tabs: List<TabItem> = NavSection.entries.map {
    TabItem(it, navSectionIcon(it))
}

/** Пересобирает список вкладок по разделам, сохраняя иконки. */
private fun List<TabItem>.forSections(sections: List<NavSection>): List<TabItem> {
    val bySection = associate { it.section to it }
    return sections.mapNotNull { bySection[it] }
}

@Composable
fun AppNavHost(onExitApp: () -> Unit) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val nowPlaying by app.playerController.nowPlaying.collectAsStateWithLifecycle()
    val nextBookSuggestion by app.playerController.nextBookSuggestion.collectAsStateWithLifecycle()
    val hideTabLabels by app.settingsStore.hideTabLabels.collectAsStateWithLifecycle(initialValue = false)
    val tabUnderlayHeight by app.settingsStore.tabUnderlayHeight.collectAsStateWithLifecycle(initialValue = SettingsStore.TAB_UNDERLAY_DEFAULT)
    val closeOnBackLongPress by app.settingsStore.closeOnBackLongPress.collectAsStateWithLifecycle(initialValue = false)
    val openPlayerOnLaunch by app.settingsStore.openPlayerOnLaunch.collectAsStateWithLifecycle(initialValue = false)
    val themeMode by app.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = SettingsStore.THEME_SYSTEM)
    // Тёмная тема: текст навигации белый; светлая — чёрный (onSurface).
    val darkTheme = when (themeMode) {
        SettingsStore.THEME_DARK -> true
        SettingsStore.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    // Иконка выбранной вкладки контрастна подложке-индикатору (акцентному цвету):
    // светлый/белый акцент — чёрная иконка, тёмный/чёрный акцент — белая.
    val navIconColor =
        if (MaterialTheme.colorScheme.primary.luminance() > 0.5f) Color.Black else Color.White
    val navTextColor = if (darkTheme) Color.White else MaterialTheme.colorScheme.onSurface
    val navUnselectedColor =
        if (darkTheme) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    var playerOpenedOnLaunch by remember { mutableStateOf(false) }
    // Порядок и состав нижней навигации берём из настроек, поэтому вкладки
    // строятся заново при каждом изменении. Подписи берём из ресурсов, чтобы
    // язык экрана и язык настроек совпадали.
    val uiConfig = LocalUiConfig.current
    // visibleSections уже умеет подставлять HOME, если скрыты все разделы
    // (например, после восстановления из старой резервной копии), поэтому
    // нижняя панель не может оказаться пустой. Разделы только для ТВ в неё
    // не попадают: на смартфоне «Избранное» и «Буду слушать» живут в «Я».
    val visibleTabs = remember(uiConfig.visibleSections) {
        tabs.forSections(uiConfig.visibleSections)
    }
    // Боковая панель TV: все разделы в порядке объявления, без настроек —
    // на ТВ их и скрывать нечем.
    val tvTabs = remember { tabs.forSections(NavSection.TV) }
    val tabRoutes = remember(visibleTabs) { visibleTabs.map { it.section.route } }
    val navRoutes = remember(visibleTabs, tvTabs) { (visibleTabs + tvTabs).map { it.section.route } }
    // Стартовый раздел запоминается один раз: пересоздавать граф навигации во
    // время работы приложения нельзя, поэтому смена раздела применяется при
    // следующем запуске. AppRoot дожидается первого значения из DataStore,
    // так что здесь уже не значение по умолчанию, а сохранённое.
    val startRoute = remember { uiConfig.effectiveStartSection.route }
    // Нижний системный отступ (жестовая зона/системная панель): добавляется к высоте подложки.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val underlayHeight = remember(tabUnderlayHeight) {
        when (tabUnderlayHeight) {
            SettingsStore.TAB_UNDERLAY_LOW -> 64.dp
            SettingsStore.TAB_UNDERLAY_HIGH -> 96.dp
            else -> 80.dp
        }
    }

    // Плеер открываем независимо от того, успела ли загрузиться последняя
    // книга: иначе на чистой установке без истории настройка не срабатывала бы.
    LaunchedEffect(openPlayerOnLaunch, playerOpenedOnLaunch) {
        if (openPlayerOnLaunch && !playerOpenedOnLaunch) {
            navController.navigate("player") { launchSingleTop = true }
            playerOpenedOnLaunch = true
        }
    }

    // Когда книга закончилась и есть следующая в серии — открываем плеер,
    // чтобы пользователь увидел предложение воспроизвести её.
    LaunchedEffect(nextBookSuggestion, currentRoute) {
        if (nextBookSuggestion != null && currentRoute != "player") {
            navController.navigate("player") { launchSingleTop = true }
        }
    }

    BackHandler(enabled = closeOnBackLongPress && currentRoute in navRoutes) {
        onExitApp()
    }

    val tvMode = isTvMode

    // На Android TV после «Слушать» сразу открывается плеер: оставаться на
    // странице книги незачем, а вернуться с неё с пульта неудобно.
    // Реагируем именно на запуск воспроизведения, а не на смену книги —
    // иначе повторное «Слушать» для уже загруженной книги ничего бы не делало.
    val playStarts by app.playerController.playStarts.collectAsStateWithLifecycle()
    LaunchedEffect(tvMode, playStarts) {
        if (tvMode && playStarts > 0 && currentRoute != "player") {
            navController.navigate("player") { launchSingleTop = true }
        }
    }

    val showMini = nowPlaying != null && currentRoute != "player"
    val showNav = !tvMode && currentRoute in tabRoutes
    val hazeState = rememberHazeState()
    val glassShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    CompositionLocalProvider(LocalHazeState provides hazeState, LocalNavController provides navController) {
    if (tvMode) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (currentRoute != "player") {
                    TvSideNav(
                        tabs = tvTabs,
                        currentRoute = currentRoute,
                        hideLabels = hideTabLabels,
                        iconColor = navIconColor,
                        textColor = navTextColor,
                        unselectedColor = navUnselectedColor,
                        onTabSelected = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = startRoute,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    appRoutes(navController)
                }
            }
            if (showMini) {
                val mini = nowPlaying
                if (mini != null) {
                    Box(Modifier.navigationBarsPadding()) {
                        MiniPlayer(
                            nowPlaying = mini,
                            player = app.playerController.player,
                            onClick = { navController.navigate("player") },
                            onToggle = { app.playerController.togglePlayPause() },
                        )
                    }
                }
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            NavHost(
                navController = navController,
                startDestination = startRoute,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
            ) {
                appRoutes(navController)
            }
            if (showMini || showNav) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .glass(hazeState, shape = glassShape),
                ) {
                    if (showMini) {
                        val mini = nowPlaying
                        if (mini != null) {
                            if (showNav) {
                                MiniPlayer(
                                    nowPlaying = mini,
                                    player = app.playerController.player,
                                    onClick = { navController.navigate("player") },
                                    onToggle = { app.playerController.togglePlayPause() },
                                )
                            } else {
                                Box(Modifier.navigationBarsPadding()) {
                                    MiniPlayer(
                                        nowPlaying = mini,
                                        player = app.playerController.player,
                                        onClick = { navController.navigate("player") },
                                        onToggle = { app.playerController.togglePlayPause() },
                                    )
                                }
                            }
                        }
                    }
                    if (showNav) {
                        NavigationBar(
                            modifier = Modifier.height(underlayHeight + navBarInset),
                            containerColor = Color.Transparent,
                        ) {
                            visibleTabs.forEach { tab ->
                                val selected = currentRoute == tab.section.route
                                val label = navSectionLabel(tab.section)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(tab.section.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = null) },
                                    label = if (hideTabLabels) null else { { Text(label) } },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = navIconColor,
                                        selectedTextColor = navTextColor,
                                        indicatorColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = navUnselectedColor,
                                        unselectedTextColor = navUnselectedColor,
                                    ),
                                    modifier = Modifier.height(underlayHeight).tvFocus(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * Боковая панель навигации (закладки слева) для режима Android TV.
 */
@Composable
private fun TvSideNav(
    tabs: List<TabItem>,
    currentRoute: String?,
    hideLabels: Boolean,
    iconColor: Color,
    textColor: Color,
    unselectedColor: Color,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(112.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Одинаковые отступы сверху и снизу: блок пунктов по центру экрана.
        Spacer(Modifier.weight(1f))
        tabs.forEachIndexed { index, tab ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            val route = tab.section.route
            val label = navSectionLabel(tab.section)
            NavigationRailItem(
                selected = currentRoute == route,
                onClick = { onTabSelected(route) },
                icon = { Icon(tab.icon, contentDescription = label) },
                label = if (hideLabels) null else {
                    { Text(label, style = MaterialTheme.typography.labelSmall) }
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = iconColor,
                    selectedTextColor = textColor,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = unselectedColor,
                    unselectedTextColor = unselectedColor,
                ),
                modifier = Modifier.tvFocus().height(52.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun NavGraphBuilder.appRoutes(navController: NavHostController) {
    composable("home") {
        HomeScreen(navController)
    }
    composable("new") {
        NewBooksScreen(navController)
    }
    composable("search") {
        SearchScreen(navController)
    }
    composable("genres") {
        GenresScreen(navController)
    }
    composable("history") {
        HistoryScreen(navController)
    }
    composable("me") {
        ProfileScreen(navController)
    }
    composable("favorites") {
        FavoritesScreen(navController)
    }
    composable("donate") {
        DonateScreen(navController)
    }
    composable("watchlist") {
        WatchlistScreen(navController)
    }
    composable(
        route = "genre/{url}?name={name}",
        arguments = listOf(
            navArgument("url") { type = NavType.StringType },
            navArgument("name") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        val url = entry.arguments?.getString("url") ?: return@composable
        val name = entry.arguments?.getString("name").orEmpty()
        GenreBooksScreen(genreUrl = url, genreName = name, navController = navController)
    }
    composable(
        route = "series/{bookKey}?url={url}&title={title}",
        arguments = listOf(
            navArgument("bookKey") { type = NavType.StringType },
            navArgument("url") {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument("title") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        val bookKey = entry.arguments?.getString("bookKey") ?: return@composable
        val seriesUrl = entry.arguments?.getString("url").orEmpty()
        val title = entry.arguments?.getString("title").orEmpty()
        val sourceId = bookKey.substringBefore(":")
        SeriesBooksScreen(
            sourceId = sourceId,
            seriesUrl = seriesUrl,
            seriesTitle = title,
            navController = navController,
        )
    }
    composable("source") {
        SourceScreen(navController)
    }
    composable(
        route = "person/{name}?mode={mode}",
        arguments = listOf(
            navArgument("name") { type = NavType.StringType },
            navArgument("mode") {
                type = NavType.StringType
                defaultValue = "author"
            },
        ),
    ) { entry ->
        val name = entry.arguments?.getString("name") ?: return@composable
        val mode = entry.arguments?.getString("mode") ?: "author"
        PersonBooksScreen(personName = name, mode = mode, navController = navController)
    }
    composable("settings") {
        SettingsScreen(navController)
    }
    composable("downloads") {
        DownloadsScreen(navController)
    }
    composable(
        route = "book/{key}",
        arguments = listOf(navArgument("key") { type = NavType.StringType }),
    ) { entry ->
        val key = entry.arguments?.getString("key") ?: return@composable
        BookScreen(bookKey = key, navController = navController)
    }
    composable(
        route = "similar/{bookKey}",
        arguments = listOf(navArgument("bookKey") { type = NavType.StringType }),
    ) { entry ->
        val bookKey = entry.arguments?.getString("bookKey") ?: return@composable
        SimilarBooksScreen(bookKey = bookKey, navController = navController)
    }
    composable("player") {
        PlayerScreen(navController)
    }
}