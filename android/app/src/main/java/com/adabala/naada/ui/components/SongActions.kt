package com.adabala.naada.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adabala.naada.data.Song
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.toast
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    song: Song,
    onDismiss: () -> Unit,
    fromUserPlaylist: String? = null,
) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lib by graph.library.library.collectAsState()
    val downloads by graph.playback.downloads.collectAsState()
    val liked = lib.likes.any { it.id == song.id }
    val dl = downloads[song.id]
    var pickPlaylist by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.image, size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            fun close(block: () -> Unit) { block(); scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }

            Action(Icons.Rounded.SkipNext, "Play next") { close { graph.player.playNext(song); toast(context, "Playing next") } }
            Action(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { close { graph.player.addToQueue(listOf(song)); toast(context, "Added to queue") } }
            Action(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove from Liked" else "Like") {
                close { graph.library.toggleLike(song) }
            }
            Action(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { pickPlaylist = true }
            when {
                dl?.done == true -> Action(Icons.Rounded.Delete, "Remove download") { close { graph.player.removeDownload(song.id) } }
                dl?.active == true -> Action(Icons.Rounded.Download, "Downloading… ${dl.percent.toInt().coerceAtLeast(0)}%") {}
                else -> Action(Icons.Rounded.Download, "Download") { close { graph.player.download(listOf(song)); toast(context, "Downloading") } }
            }
            Action(Icons.Rounded.Radio, "Start song radio") {
                close {
                    graph.scope.launch {
                        val songs = runCatching { graph.api.radio(song.id, song.language.ifEmpty { "hindi" }).songs }.getOrDefault(emptyList())
                        graph.player.play(listOf(song) + songs.filter { it.id != song.id })
                        toast(context, if (songs.isEmpty()) "Radio unavailable — playing the song" else "Radio started")
                    }
                }
            }
            if (song.albumId.isNotEmpty()) Action(Icons.Rounded.Album, "Go to album") { close { nav.album(song.albumId) } }
            if (fromUserPlaylist != null) Action(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist") {
                close { graph.library.removeFromPlaylist(fromUserPlaylist, song.id) }
            }
            Action(Icons.Rounded.Share, "Share") {
                close {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "🎵 ${song.title} — ${song.artist}" + if (song.album.isNotBlank()) " (${song.album})" else "")
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share song"))
                }
            }
        }
    }

    if (pickPlaylist) {
        AddToPlaylistDialog(listOf(song)) { pickPlaylist = false; onDismiss() }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val lib by graph.library.library.collectAsState()
    var creating by remember { mutableStateOf(lib.playlists.isEmpty()) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (creating) "New playlist" else "Add to playlist") },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
            } else {
                LazyColumn {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(12.dp))
                            Text("New playlist", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(lib.playlists) { p ->
                        Text(
                            "${p.name}  ·  ${p.songs.size}",
                            Modifier.fillMaxWidth().clickable {
                                graph.library.addToPlaylist(p.id, songs)
                                toast(context, "Added to ${p.name}")
                                onDone()
                            }.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(enabled = name.isNotBlank(), onClick = {
                graph.library.createPlaylist(name, songs)
                toast(context, "Created ${name.trim()}")
                onDone()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
