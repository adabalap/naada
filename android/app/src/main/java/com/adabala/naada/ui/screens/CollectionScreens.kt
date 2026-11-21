package com.adabala.naada.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import com.adabala.naada.ui.components.ScrollToTopButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adabala.naada.data.Album
import com.adabala.naada.data.Song
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.components.AlbumShelf
import com.adabala.naada.ui.components.Artwork
import com.adabala.naada.ui.components.Load
import com.adabala.naada.ui.components.LoadingBox
import com.adabala.naada.ui.components.MessageBox
import com.adabala.naada.ui.components.SongActionsSheet
import com.adabala.naada.ui.components.ShareSheet
import com.adabala.naada.ui.components.SongRow
import com.adabala.naada.ui.components.rememberArtColors
import com.adabala.naada.ui.components.rememberLoader
import com.adabala.naada.ui.toast

/** Shared layout for every list of songs: albums, playlists, artists, liked… */
@Composable
fun CollectionScaffold(
    title: String,
    subtitle: String,
    image: String,
    songs: List<Song>,
    modifier: Modifier = Modifier,
    circleArt: Boolean = false,
    mosaic: Boolean = false,
    numbered: Boolean = false,
    userPlaylistId: String? = null,
    description: String = "",
    emptyTitle: String = "Nothing here yet",
    emptyBody: String = "",
    emptyIcon: ImageVector = Icons.Rounded.LibraryMusic,
    headerActions: @Composable () -> Unit = {},
    topActions: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val ui by graph.player.ui.collectAsState()
    val lib by graph.library.library.collectAsState()
    val downloads by graph.playback.downloads.collectAsState()
    val art = rememberArtColors(image.ifEmpty { songs.firstOrNull()?.image })
    var actionFor by remember { mutableStateOf<Song?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val allDownloaded = songs.isNotEmpty() && songs.all { downloads[it.id]?.done == true }
    val top = (art?.muted ?: MaterialTheme.colorScheme.primaryContainer).copy(alpha = .55f)

    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(top, Color.Transparent)))
                    .statusBarsPadding()
            ) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    topActions()
                    if (songs.isNotEmpty()) IconButton(onClick = { sharing = true }) { Icon(Icons.Rounded.Share, "Share") }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (mosaic) PlaylistMosaic(songs.take(4).map { it.image }, size = 200.dp)
                    else Artwork(
                        image.ifEmpty { songs.firstOrNull()?.image.orEmpty() }, size = 220.dp,
                        shape = if (circleArt) CircleShape else RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(
                        subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    if (description.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(16.dp))
                    // Secondary actions on the left, one big Play on the right — never squeezed.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(
                            enabled = songs.isNotEmpty(),
                            onClick = {
                                if (allDownloaded) toast(context, "Already downloaded")
                                else { graph.player.download(songs); toast(context, "Downloading ${songs.size} songs") }
                            }
                        ) { Icon(if (allDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download, "Download all") }
                        headerActions()
                        Spacer(Modifier.weight(1f))
                        FilledTonalIconButton(enabled = songs.isNotEmpty(), onClick = { graph.player.play(songs, shuffle = true) },
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Shuffle, "Shuffle")
                        }
                        FilledIconButton(enabled = songs.isNotEmpty(), onClick = { graph.player.play(songs, 0) },
                            modifier = Modifier.size(60.dp)) {
                            Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(34.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (songs.isEmpty()) item { MessageBox(emptyTitle, emptyBody, icon = emptyIcon) }
        itemsIndexed(songs) { i, s ->
            SongRow(
                s, onClick = { graph.player.play(songs, i) }, onMore = { actionFor = s },
                playing = ui.song?.id == s.id, liked = lib.likes.any { it.id == s.id },
                downloaded = downloads[s.id]?.done == true, index = if (numbered) i + 1 else null,
            )
        }
        item { footer() }
    }
    ScrollToTopButton(listState)
    }

    actionFor?.let { s -> SongActionsSheet(s, onDismiss = { actionFor = null }, fromUserPlaylist = userPlaylistId) }
    if (sharing) ShareSheet(title, songs, onDismiss = { sharing = false })
}

@Composable
private fun <T> Loaded(state: Load<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    val nav = LocalNav.current
    when (state) {
        is Load.Loading -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            IconButton(onClick = nav::back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            LoadingBox()
        }
        is Load.Failed -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            IconButton(onClick = nav::back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            MessageBox("Couldn't load this", state.message, action = "Retry", onAction = retry)
        }
        is Load.Ready -> content(state.value)
    }
}

@Composable
fun AlbumScreen(id: String) {
    val graph = LocalGraph.current
    val l = rememberLoader(id) { graph.api.album(id) }
    Loaded(l.state, l.reload) { a: Album ->
        CollectionScaffold(a.title, listOf(a.artist, a.year).filter { it.isNotBlank() }.joinToString(" · "), a.image, a.songs, numbered = true)
    }
}

@Composable
fun ArtistScreen(id: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val l = rememberLoader(id) { graph.api.artist(id) }
    Loaded(l.state, l.reload) { a ->
        CollectionScaffold(a.name, "Top songs", a.image, a.songs, circleArt = true,
            footer = { AlbumShelf("Albums", a.albums) { nav.album(it.id) } })
    }
}

@Composable
fun PlaylistScreen(id: String) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val l = rememberLoader(id) { graph.api.playlist(id) }
    Loaded(l.state, l.reload) { p ->
        CollectionScaffold(p.title, "${p.songs.size} songs", p.image, p.songs, headerActions = {
            FilledTonalIconButton(onClick = {
                graph.library.createPlaylist(p.title, p.songs)
                toast(context, "Saved to your library")
            }) { Icon(Icons.Rounded.FavoriteBorder, "Save to library") }
        })
    }
}

@Composable
fun ChartsScreen(lang: String) {
    val graph = LocalGraph.current
    val l = rememberLoader(lang) { graph.api.charts(lang) }
    Loaded(l.state, l.reload) { c ->
        val label = LANGS.firstOrNull { it.first == lang }?.second ?: lang
        CollectionScaffold(c.title.ifBlank { "Top charts" }, label, c.songs.firstOrNull()?.image.orEmpty(), c.songs, numbered = true)
    }
}

@Composable
fun MoodScreen(prompt: String) {
    val graph = LocalGraph.current
    val lang = graph.currentLang()
    val l = rememberLoader(prompt) { graph.api.mood(prompt, lang) }
    Loaded(l.state, l.reload) { m ->
        when {
            m.error == "ai_disabled" -> CollectionScaffold(prompt, "", "", emptyList(),
                emptyTitle = "AI isn't set up", emptyBody = "Add a Gemini API key on the server (config.json) to build playlists from a vibe.",
                emptyIcon = Icons.Rounded.AutoAwesome)
            m.error == "daily_limit" -> CollectionScaffold(prompt, "", "", emptyList(),
                emptyTitle = "That's today's AI playlists", emptyBody = "Your daily AI allowance is used up — try again tomorrow.",
                emptyIcon = Icons.Rounded.AutoAwesome)
            m.songs.isEmpty() -> CollectionScaffold(prompt, "", "", emptyList(),
                emptyTitle = "Couldn't build that one", emptyBody = "The AI is busy or found nothing. Try again in a minute, or describe it differently.",
                emptyIcon = Icons.Rounded.AutoAwesome)
            else -> CollectionScaffold(m.title.ifBlank { prompt }, "A vibe: “$prompt”", "", m.songs,
                mosaic = true, description = m.description, headerActions = {
                    val context = LocalContext.current
                    FilledTonalIconButton(onClick = {
                        graph.library.createPlaylist(m.title.ifBlank { prompt }, m.songs)
                        toast(context, "Saved as a playlist")
                    }) { Icon(Icons.Rounded.FavoriteBorder, "Save as playlist") }
                })
        }
    }
}

@Composable
fun LikedScreen() {
    val graph = LocalGraph.current
    val lib by graph.library.library.collectAsState()
    CollectionScaffold("Liked songs", "${lib.likes.size} songs", "", lib.likes, mosaic = true,
        emptyTitle = "No liked songs yet", emptyBody = "Tap ♡ on anything you love. Likes sync to your server.",
        emptyIcon = Icons.Rounded.FavoriteBorder)
}

@Composable
fun HistoryScreen() {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val lib by graph.library.library.collectAsState()
    val songs = remember(lib.history) { lib.history.map { it.song }.distinctBy { it.id } }
    var confirm by remember { mutableStateOf(false) }
    CollectionScaffold("Recently played", "${songs.size} songs", "", songs, mosaic = true,
        emptyTitle = "Nothing played yet", emptyBody = "Songs you play show up here, on every device.",
        emptyIcon = Icons.Rounded.History,
        topActions = {
            if (songs.isNotEmpty()) IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear history") }
        })
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Clear listening history?") },
        text = { Text("Removes recently played from this phone and your server. Likes and playlists stay.") },
        confirmButton = { TextButton(onClick = { confirm = false; graph.library.clearHistory(); toast(context, "History cleared") }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun SharedPlaylistScreen(id: String) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val l = rememberLoader(id) { graph.api.sharedPlaylist(id) }
    Loaded(l.state, l.reload) { p ->
        CollectionScaffold(p.name, "Shared with you · ${p.songs.size} songs", "", p.songs, mosaic = true,
            headerActions = {
                FilledTonalIconButton(onClick = {
                    graph.library.createPlaylist(p.name, p.songs)
                    toast(context, "Saved to your library")
                }) { Icon(Icons.Rounded.FavoriteBorder, "Save to library") }
            })
    }
}

@Composable
fun DownloadsScreen() {
    val graph = LocalGraph.current
    val downloads by graph.playback.downloads.collectAsState()
    val done = downloads.values.filter { it.done }.sortedByDescending { it.updatedAt }.map { it.song }
    val active = downloads.values.count { it.active }
    CollectionScaffold(
        "Downloads",
        buildString {
            append("${done.size} songs offline")
            if (active > 0) append(" · $active downloading")
        },
        "", done, mosaic = true,
        emptyTitle = "No downloads yet", emptyBody = "Download songs or whole albums from their ⋮ menu to play without internet.",
        emptyIcon = Icons.Rounded.Download,
    )
}

@Composable
fun UserPlaylistScreen(id: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val lib by graph.library.library.collectAsState()
    val pl = lib.playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (pl == null) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            MessageBox("Playlist not found", "It may have been deleted on another device.", action = "Back", onAction = nav::back)
        }
        return
    }
    CollectionScaffold(
        pl.name, "${pl.songs.size} songs", "", pl.songs, mosaic = true, userPlaylistId = id,
        emptyTitle = "Empty playlist", emptyBody = "Add songs from any song's ⋮ menu.",
        topActions = {
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Delete") }
        },
    )
    if (renaming) NameDialog("Rename playlist", pl.name, onDismiss = { renaming = false }) {
        graph.library.renamePlaylist(id, it); renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete “${pl.name}”?") },
        text = { Text("This removes it from all your devices.") },
        confirmButton = { TextButton(onClick = { deleting = false; graph.library.deletePlaylist(id); nav.back() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}
