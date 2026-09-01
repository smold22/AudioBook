package com.yourapp.audiobook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
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
import com.yourapp.audiobook.ui.components.MiniPlayer

private data class TabItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val tabs = listOf(
    TabItem("home", "Главная", Icons.Filled.Home),
    TabItem("search", "Поиск", Icons.Filled.Search),
    TabItem("genres", "Жанры", Icons.AutoMirrored.Filled.List),
    TabItem("history", "История", Icons.Filled.History),
    TabItem("me", "Я", Icons.Filled.Person),
)

@Composable
fun AppNavHost(onExitApp: () -> Unit) {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val nowPlaying by app.playerController.nowPlaying.collectAsStateWithLifecycle()
    val hideTabLabels by app.settingsStore.hideTabLabels.collectAsStateWithLifecycle(initialValue = false)
    val tabUnderlayHeight by app.settingsStore.tabUnderlayHeight.collectAsStateWithLifecycle(initialValue = SettingsStore.TAB_UNDERLAY_DEFAULT)
    val closeOnBackLongPress by app.settingsStore.closeOnBackLongPress.collectAsStateWithLifecycle(initialValue = false)
    val openPlayerOnLaunch by app.settingsStore.openPlayerOnLaunch.collectAsStateWithLifecycle(initialValue = false)
    var playerOpenedOnLaunch by remember { mutableStateOf(false) }
    val tabRoutes = tabs.map { it.route }
    // Нижний системный отступ (жестовая зона/системная панель): добавляется к высоте подложки.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val underlayHeight = remember(tabUnderlayHeight) {
        when (tabUnderlayHeight) {
            SettingsStore.TAB_UNDERLAY_LOW -> 64.dp
            SettingsStore.TAB_UNDERLAY_HIGH -> 96.dp
            else -> 80.dp
        }
    }

    LaunchedEffect(openPlayerOnLaunch, nowPlaying, playerOpenedOnLaunch) {
        if (openPlayerOnLaunch && !playerOpenedOnLaunch && nowPlaying != null) {
            navController.navigate("player") { launchSingleTop = true }
            playerOpenedOnLaunch = true
        }
    }

    BackHandler(enabled = closeOnBackLongPress && currentRoute in tabRoutes) {
        onExitApp()
    }

    val tvMode = isTvMode

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val showMini = nowPlaying != null && currentRoute != "player"
            if (tvMode) {
                if (showMini) {
                    val mini = nowPlaying
                    if (mini != null) {
                        Column(Modifier.navigationBarsPadding()) {
                            MiniPlayer(
                                nowPlaying = mini,
                                player = app.playerController.player,
                                onClick = { navController.navigate("player") },
                                onToggle = { app.playerController.togglePlayPause() },
                            )
                        }
                    }
                }
            } else {
                val showNav = currentRoute in tabRoutes
                if (showMini || showNav) {
                    Column(
                        modifier = if (showMini && !showNav) Modifier.navigationBarsPadding() else Modifier,
                    ) {
                        if (showMini) {
                            val mini = nowPlaying
                            if (mini != null) {
                                MiniPlayer(
                                    nowPlaying = mini,
                                    player = app.playerController.player,
                                    onClick = { navController.navigate("player") },
                                    onToggle = { app.playerController.togglePlayPause() },
                                )
                            }
                        }
                        if (showNav) {
                            NavigationBar(
                                modifier = Modifier.height(underlayHeight + navBarInset),
                            ) {
                                tabs.forEach { tab ->
                                    val selected = currentRoute == tab.route
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(tab.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = if (hideTabLabels) null else { { Text(tab.label) } },
                                        modifier = Modifier.height(underlayHeight).tvFocus(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        if (tvMode) {
            Row(modifier = Modifier.fillMaxSize()) {
                TvSideNav(
                    tabs = tabs,
                    currentRoute = currentRoute,
                    hideLabels = hideTabLabels,
                    onTabSelected = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.padding(start = innerPadding.calculateLeftPadding(LayoutDirection.Ltr)),
                )
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(innerPadding),
                ) {
                    appRoutes(navController)
                }
            }
        } else {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                appRoutes(navController)
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
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxHeight().width(112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Одинаковые отступы сверху и снизу: блок пунктов по центру экрана.
        Spacer(Modifier.weight(1f))
        tabs.forEachIndexed { index, tab ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            NavigationRailItem(
                selected = currentRoute == tab.route,
                onClick = { onTabSelected(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = if (hideLabels) null else {
                    { Text(tab.label, style = MaterialTheme.typography.labelSmall) }
                },
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
    composable("player") {
        PlayerScreen(navController)
    }
}