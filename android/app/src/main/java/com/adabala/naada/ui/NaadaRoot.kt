package com.adabala.naada.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import com.adabala.naada.playback.SavedQueue
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adabala.naada.ui.screens.AlbumScreen
import com.adabala.naada.ui.screens.ArtistScreen
import com.adabala.naada.ui.screens.ChartsScreen
import com.adabala.naada.ui.screens.DownloadsScreen
import com.adabala.naada.ui.screens.HistoryScreen
import com.adabala.naada.ui.screens.HomeScreen
import com.adabala.naada.ui.screens.LibraryScreen
import com.adabala.naada.ui.screens.LikedScreen
import com.adabala.naada.ui.screens.MiniPlayer
import com.adabala.naada.ui.screens.MoodScreen
import com.adabala.naada.ui.screens.NowPlayingScreen
import com.adabala.naada.ui.screens.PlaylistScreen
import com.adabala.naada.ui.screens.SearchScreen
import com.adabala.naada.ui.screens.SettingsScreen
import com.adabala.naada.ui.screens.SharedPlaylistScreen
import com.adabala.naada.ui.screens.ProfilesScreen
import com.adabala.naada.ui.screens.FolderScreen
import com.adabala.naada.ui.screens.AddProfileScreen
import com.adabala.naada.ui.screens.AdminScreen
import com.adabala.naada.ui.screens.TriviaScreen
import com.adabala.naada.ui.screens.NewReleasesScreen
import com.adabala.naada.ui.screens.FeaturedPlaylistsScreen
import com.adabala.naada.ui.components.LocalTabReselect
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import com.adabala.naada.ui.screens.UserPlaylistScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab("search", "Search", Icons.Outlined.Search, Icons.Rounded.Search),
    Tab("trivia", "Trivia", Icons.Outlined.Lightbulb, Icons.Rounded.Lightbulb),
    Tab("library", "Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)
private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
fun NaadaRoot(
    openPlayer: Boolean,
    onPlayerOpened: () -> Unit,
    sharedId: String? = null,
    onSharedOpened: () -> Unit = {},
) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val nc = rememberNavController()
    val nav = remember(nc) { Nav(nc) }
    val ui by graph.player.ui.collectAsState()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf("home") }
    val reselect = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    var undo by remember { mutableStateOf<SavedQueue?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(5000); undo = null } }
    LaunchedEffect(ui.song?.id) { if (ui.song != null) undo = null }

    LaunchedEffect(openPlayer) {
        if (openPlayer) { showPlayer = true; onPlayerOpened() }
    }
    // First run: ask who's listening, or send newcomers to join if the server needs it.
    LaunchedEffect(Unit) {
        val ps = graph.profiles.state.value
        if (ps.askOnLaunch && ps.profiles.size >= 2) { nav.profiles(); return@LaunchedEffect }
        if (ps.profiles.isEmpty() && graph.settings.state.value.token.isBlank()) {
            val info = runCatching { graph.api.serverInfo() }.getOrNull()
            if (info != null && info.tokenRequired && info.user == null) nav.addProfile()
        }
    }
    LaunchedEffect(sharedId) {
        if (sharedId != null) { showPlayer = false; runCatching { nav.shared(sharedId) }; onSharedOpened() }
    }
    LaunchedEffect(ui.error) {
        ui.error?.let { toast(context, it); graph.player.clearError() }
    }
    BackHandler(enabled = showPlayer) { showPlayer = false }

    CompositionLocalProvider(LocalNav provides nav, LocalTabReselect provides reselect) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                val backStack by nc.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                // Landing on a tab's main screen (by back, deep link…) makes it the current tab.
                LaunchedEffect(route) { if (route in tabRoutes) currentTab = route!! }
                // Going anywhere new closes the full-screen player (e.g. "Go to album").
                var lastEntry by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(backStack?.id) {
                    val id = backStack?.id
                    if (lastEntry != null && id != lastEntry) showPlayer = false
                    lastEntry = id
                }

                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        NavHost(
                            nc, startDestination = "home",
                            enterTransition = { fadeIn(tween(220)) },
                            exitTransition = { fadeOut(tween(180)) },
                        ) {
                            composable("home") { HomeScreen() }
                            composable("search") { SearchScreen() }
                            composable("library") { LibraryScreen() }
                            composable("album/{id}") { AlbumScreen(it.arg("id")) }
                            composable("artist/{id}") { ArtistScreen(it.arg("id")) }
                            composable("playlist/{id}") { PlaylistScreen(it.arg("id")) }
                            composable("userpl/{id}") { UserPlaylistScreen(it.arg("id")) }
                            composable("charts/{lang}") { ChartsScreen(it.arg("lang")) }
                            composable("newreleases/{lang}") { NewReleasesScreen(it.arg("lang")) }
                            composable("featured/{lang}") { FeaturedPlaylistsScreen(it.arg("lang")) }
                            composable("mood/{prompt}") { MoodScreen(it.arg("prompt")) }
                            composable("liked") { LikedScreen() }
                            composable("history") { HistoryScreen() }
                            composable("downloads") { DownloadsScreen() }
                            composable("settings") { SettingsScreen() }
                            composable("trivia") { TriviaScreen() }
                            composable("shared/{id}") { SharedPlaylistScreen(it.arg("id")) }
                            composable("profiles") { ProfilesScreen() }
                            composable("folder/{id}") { FolderScreen(it.arg("id")) }
                            composable("addprofile") { AddProfileScreen() }
                            composable("admin") { AdminScreen() }
                        }
                    }

                    AnimatedVisibility(visible = ui.song != null) {
                        MiniPlayer(onOpen = { showPlayer = true }, onDismiss = { undo = graph.player.dismiss() })
                    }
                    AnimatedVisibility(visible = undo != null) {
                        UndoBar("Player closed", onUndo = {
                            undo?.let { graph.player.restore(it) }
                            undo = null
                        })
                    }

                    NavigationBar(tonalElevation = 0.dp, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        tabs.forEach { tab ->
                            val selected = currentTab == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    showPlayer = false
                                    when {
                                        // Already on this tab's main screen: scroll it to the top.
                                        selected && route == tab.route -> reselect.tryEmit(tab.route)
                                        // Deeper inside this tab: go back to its main screen.
                                        selected && nc.popBackStack(tab.route, inclusive = false) -> Unit
                                        // Another tab: switch, keeping each tab's own history.
                                        else -> {
                                            currentTab = tab.route
                                            nc.navigate(tab.route) {
                                                popUpTo(nc.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                },
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showPlayer && ui.song != null,
                    enter = slideInVertically(tween(320)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(260)) { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    NowPlayingScreen(onClose = { showPlayer = false })
                }
            }
        }
    }
}

@Composable
private fun UndoBar(text: String, onUndo: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.weight(1f))
            TextButton(onClick = onUndo) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.arg(name: String): String =
    Uri.decode(arguments?.getString(name).orEmpty())
