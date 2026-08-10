package com.yourapp.audiobook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yourapp.audiobook.AudioBookApplication
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
fun AppNavHost() {
    val app = LocalContext.current.applicationContext as AudioBookApplication
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val nowPlaying by app.playerController.nowPlaying.collectAsStateWithLifecycle()
    val hideTabLabels by app.settingsStore.hideTabLabels.collectAsStateWithLifecycle(initialValue = false)
    val tabRoutes = tabs.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val showMini = nowPlaying != null && currentRoute != "player"
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
                        NavigationBar {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
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
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
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
    }
}