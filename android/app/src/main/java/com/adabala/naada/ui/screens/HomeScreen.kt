package com.adabala.naada.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.components.AlbumShelf
import com.adabala.naada.ui.components.Load
import com.adabala.naada.ui.components.LoadingBox
import com.adabala.naada.ui.components.MessageBox
import com.adabala.naada.ui.components.PlaylistShelf
import com.adabala.naada.ui.components.SongCardShelf
import com.adabala.naada.ui.components.rememberLoader
import com.adabala.naada.ui.theme.Brand
import com.adabala.naada.ui.theme.LocalNaadaStyle
import kotlinx.coroutines.launch
import java.util.Calendar

val LANGS = listOf("telugu" to "తెలుగు", "hindi" to "हिन्दी", "tamil" to "தமிழ்", "english" to "English")

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late-night listening"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.state.collectAsState()
    val lib by graph.library.library.collectAsState()
    val profiles by graph.profiles.state.collectAsState()
    val lang = settings.homeLang
    val home = rememberLoader(lang, settings.serverUrl, settings.token, profiles.active) { graph.api.home(lang) }
    val recent = remember(lib.history) { graph.library.recentSongs(16) }
    val downloads by graph.playback.downloads.collectAsState()
    val downloadsCount = downloads.values.count { it.done }
    var profileMenu by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = home.refreshing,
        onRefresh = { home.reload(); graph.library.scheduleSync() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("నాద · ${greeting()}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(
                            profiles.current?.let { "Hi, ${it.name.substringBefore(' ')}" } ?: "What's playing today?",
                            style = MaterialTheme.typography.headlineMedium, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    val me = profiles.current
                    Box(Modifier.clip(CircleShape).clickable { profileMenu = true }.padding(4.dp)) {
                        if (me != null) Avatar(me.name, me.color, 40.dp)
                        else Icon(Icons.Rounded.AccountCircle, "Profile & settings", Modifier.size(40.dp))
                    }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LANGS) { (code, label) ->
                        FilterChip(
                            selected = lang == code,
                            onClick = { scope.launch { graph.settings.setHomeLang(code) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item { QuickAccess(lib.likes.size, downloadsCount, lib.playlists.take(4)) }
            item { MoodCard(onSubmit = { nav.mood(it) }) }

            if (recent.isNotEmpty()) item {
                SongCardShelf("Jump back in", recent, onPlay = { graph.player.play(recent, it) }, action = "History", onAction = nav::history)
            }

            when (val s = home.state) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item {
                    MessageBox("Couldn't load Home", s.message, action = if (s.auth) "Join or sign in" else "Retry",
                        onAction = { if (s.auth) nav.profiles() else home.reload() })
                }
                is Load.Ready -> {
                    val h = s.value
                    item {
                        SongCardShelf("Top charts", h.trending.take(15), onPlay = { graph.player.play(h.trending, it) },
                            action = "See all", onAction = { nav.charts(lang) })
                    }
                    item { AlbumShelf("New releases", h.newAlbums) { nav.album(it.id) } }
                    item { PlaylistShelf("Curated playlists", h.playlists) { nav.playlist(it.id) } }
                }
            }
            if (recent.size >= 3) item { TriviaTeaser(onClick = nav::trivia) }
        }
    }
    if (profileMenu) ProfileMenuSheet(onDismiss = { profileMenu = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileMenuSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val profiles by graph.profiles.state.collectAsState()
    val sync by graph.library.syncState.collectAsState()
    val me = profiles.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (me != null) Avatar(me.name, me.color, 52.dp)
                else Icon(Icons.Rounded.AccountCircle, null, Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(me?.name ?: "No profile yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            me == null -> "Using the owner's library"
                            me.isAdmin -> "Owner · " + syncLabel(sync)
                            else -> syncLabel(sync)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            MenuRow(Icons.Rounded.SwitchAccount, if (profiles.profiles.size > 1) "Switch profile" else "Profiles") { onDismiss(); nav.profiles() }
            MenuRow(Icons.Rounded.PersonAdd, "Add a profile") { onDismiss(); nav.addProfile() }
            if (me?.isAdmin == true) MenuRow(Icons.Rounded.Groups, "Family & friends") { onDismiss(); nav.admin() }
            MenuRow(Icons.Rounded.Settings, "Settings") { onDismiss(); nav.settings() }
        }
    }
}

private fun syncLabel(s: com.adabala.naada.data.SyncState) = when (s) {
    com.adabala.naada.data.SyncState.Idle -> "Library synced"
    com.adabala.naada.data.SyncState.Syncing -> "Syncing…"
    com.adabala.naada.data.SyncState.Offline -> "Offline — will sync later"
    com.adabala.naada.data.SyncState.Error -> "Sync problem — check Settings"
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Spotify-style quick tiles: one tap to your most-used places. */
@Composable
private fun QuickAccess(liked: Int, downloads: Int, playlists: List<com.adabala.naada.data.UserPlaylist>) {
    val nav = LocalNav.current
    data class Tile(val title: String, val images: List<String>, val icon: androidx.compose.ui.graphics.vector.ImageVector?, val brush: Brush?, val onClick: () -> Unit)
    val style = LocalNaadaStyle.current
    val tiles = buildList {
        add(Tile("Liked songs", emptyList(), Icons.Rounded.Favorite, Brush.linearGradient(style.hero.take(2)), nav::liked))
        add(Tile(if (downloads > 0) "Downloads · $downloads" else "Downloads", emptyList(), Icons.Rounded.DownloadForOffline,
            Brush.linearGradient(listOf(Brand.Teal, Brand.Purple)), nav::downloads))
        playlists.forEach { p -> add(Tile(p.name, p.songs.take(4).map { it.image }, null, null) { nav.userPlaylist(p.id) }) }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { t ->
                    Row(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(style.corner * 0.6f))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = t.onClick),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (t.icon != null && t.brush != null) {
                            Box(Modifier.size(56.dp).background(t.brush), contentAlignment = Alignment.Center) {
                                Icon(t.icon, null, tint = Color.White)
                            }
                        } else PlaylistMosaic(t.images, size = 56.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(t.title, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun MoodCard(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(LocalNaadaStyle.current.heroBrush).padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Play a vibe", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text("Describe a mood and Naada builds the playlist.", color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        TextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text("rainy evening Ilaiyaraaja melodies…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = .95f),
                unfocusedContainerColor = Color.White.copy(alpha = .9f),
                focusedTextColor = Color(0xFF1A1917), unfocusedTextColor = Color(0xFF1A1917),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedPlaceholderColor = Color(0xFF636059), unfocusedPlaceholderColor = Color(0xFF636059),
            ),
            trailingIcon = {
                IconButton(enabled = text.isNotBlank(), onClick = { onSubmit(text.trim()) }) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Brand.Purple), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Go", tint = Color.White)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) onSubmit(text.trim()) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TriviaTeaser(onClick: () -> Unit) {
    Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Lightbulb, null, tint = Brand.Gold, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Trivia from your listening", style = MaterialTheme.typography.titleMedium)
            Text("Stories behind the songs you've been playing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
    }
}
