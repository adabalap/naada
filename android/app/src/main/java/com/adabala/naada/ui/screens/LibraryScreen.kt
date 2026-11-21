package com.adabala.naada.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adabala.naada.data.SyncState
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.components.Artwork
import com.adabala.naada.ui.theme.Brand
import com.adabala.naada.ui.theme.LocalNaadaStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val lib by graph.library.library.collectAsState()
    val sync by graph.library.syncState.collectAsState()
    val downloads by graph.playback.downloads.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val grid = settings.libraryGrid
    var creating by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; graph.library.sync(); refreshing = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    val (icon, desc) = when (sync) {
                        SyncState.Syncing -> Icons.Rounded.CloudSync to "Syncing"
                        SyncState.Offline -> Icons.Rounded.CloudOff to "Offline — changes will sync later"
                        SyncState.Error -> Icons.Rounded.ErrorOutline to "Sync problem — check Settings"
                        SyncState.Idle -> Icons.Rounded.CloudDone to "Synced with your server"
                    }
                    IconButton(onClick = { graph.library.scheduleSync() }) {
                        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New playlist") }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile("Liked", "${lib.likes.size} songs", Icons.Rounded.Favorite,
                        Brush.linearGradient(LocalNaadaStyle.current.hero.take(2).reversed()), Modifier.weight(1f), nav::liked)
                    QuickTile("Downloads", "${downloads.values.count { it.done }} offline", Icons.Rounded.DownloadForOffline,
                        Brush.linearGradient(listOf(Brand.Teal, Brand.Purple)), Modifier.weight(1f), nav::downloads)
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = nav::history).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Recently played", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${lib.history.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { graph.settings.setLibraryGrid(!grid) } }) {
                        Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                            if (grid) "Show as list" else "Show as tiles")
                    }
                }
            }
            if (lib.playlists.isEmpty()) item {
                Text(
                    "Create playlists here or from any song's ⋮ menu. They sync to your server, so they're safe across phones.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            if (grid) items(lib.playlists.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEach { p ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { nav.userPlaylist(p.id) }.padding(6.dp)
                        ) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                PlaylistMosaic(p.songs.take(4).map { it.image }, size = maxWidth)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.songs.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            } else items(lib.playlists) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.userPlaylist(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaylistMosaic(p.songs.take(4).map { it.image })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.songs.size} songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        com.adabala.naada.ui.components.ScrollToTopButton(listState, tab = "library")
    }

    if (creating) {
        NameDialog("New playlist", "", onDismiss = { creating = false }) { name ->
            val id = graph.library.createPlaylist(name)
            creating = false
            nav.userPlaylist(id)
        }
    }
}

@Composable
private fun QuickTile(title: String, subtitle: String, icon: ImageVector, brush: Brush, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(brush).clickable(onClick = onClick).padding(16.dp)
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(18.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PlaylistMosaic(images: List<String>, size: androidx.compose.ui.unit.Dp = 56.dp) {
    // A 2×2 grid only when there are four different covers; otherwise one cover
    // (a playlist from a single album shouldn't show the same art four times).
    val distinct = images.filter { it.isNotBlank() }.distinct()
    val images = if (distinct.size >= 4) distinct.take(4) else distinct.take(1)
    Box(Modifier.size(size).clip(RoundedCornerShape(LocalNaadaStyle.current.corner * 0.7f)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        when {
            images.isEmpty() -> Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            images.size < 4 -> Artwork(images[0], Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp))
            else -> Column {
                Row(Modifier.weight(1f)) {
                    Artwork(images[0], Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp))
                    Artwork(images[1], Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp))
                }
                Row(Modifier.weight(1f)) {
                    Artwork(images[2], Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp))
                    Artwork(images[3], Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp))
                }
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
