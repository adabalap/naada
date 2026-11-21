package com.adabala.naada.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adabala.naada.data.Folder
import com.adabala.naada.data.UserPlaylist
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.toast

/** One row/tile in the Library list. */
sealed interface LibItem {
    data class F(val folder: Folder) : LibItem
    data class P(val playlist: UserPlaylist) : LibItem
}

private const val DRAG_LABEL = "naada-playlist"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryEntry(item: LibItem, grid: Boolean, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lib by graph.library.library.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var hovering by remember { mutableStateOf(false) }

    val title: String
    val subtitle: String
    val images: List<String>
    val open: () -> Unit
    var m = modifier
    when (item) {
        is LibItem.F -> {
            val f = item.folder
            title = f.name
            val n = f.playlistIds.count { id -> lib.playlists.any { it.id == id } }
            subtitle = listOfNotNull(
                if (n > 0) "$n playlist${if (n == 1) "" else "s"}" else null,
                if (f.songs.isNotEmpty()) "${f.songs.size} song${if (f.songs.size == 1) "" else "s"}" else null,
            ).joinToString(" · ").ifEmpty { "Empty folder — drop playlists here" }
            images = graph.library.folderImages(f)
            open = { nav.folder(f.id) }
            // Folders accept playlists dragged onto them.
            val target = remember(f.id) {
                object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        hovering = false
                        val clip = event.toAndroidDragEvent().clipData ?: return false
                        if (clip.description?.label != DRAG_LABEL || clip.itemCount == 0) return false
                        val plId = clip.getItemAt(0).text?.toString() ?: return false
                        graph.library.movePlaylistToFolder(plId, f.id)
                        val name = graph.library.playlist(plId)?.name ?: "Playlist"
                        toast(context, "Moved $name to ${f.name}")
                        return true
                    }
                    override fun onEntered(event: DragAndDropEvent) { hovering = true }
                    override fun onExited(event: DragAndDropEvent) { hovering = false }
                    override fun onEnded(event: DragAndDropEvent) { hovering = false }
                }
            }
            m = m.dragAndDropTarget(
                shouldStartDragAndDrop = { e -> e.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) },
                target = target,
            )
        }
        is LibItem.P -> {
            val p = item.playlist
            title = p.name
            subtitle = "${p.songs.size} songs"
            images = p.songs.map { it.image }
            open = { nav.userPlaylist(p.id) }
        }
    }

    val shape = RoundedCornerShape(14.dp)
    val highlight = if (hovering) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), shape) else Modifier
    // Playlists: tap opens, long-press starts a drag (drop it on a folder).
    val gestures = if (item is LibItem.P) Modifier.dragAndDropSource {
        detectTapGestures(
            onTap = { open() },
            onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                startTransfer(DragAndDropTransferData(ClipData.newPlainText(DRAG_LABEL, item.playlist.id)))
            },
        )
    } else Modifier.clickable(onClick = open)

    val cover: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { size ->
        Box {
            PlaylistMosaic(images, size = size, mixed = item is LibItem.F)
            if (item is LibItem.F) Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp).size(if (size > 80.dp) 32.dp else 20.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (size > 80.dp) 18.dp else 12.dp))
            }
        }
    }
    val menuButton: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                when (item) {
                    is LibItem.P -> {
                        DropdownMenuItem(text = { Text("Move to folder…") }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) },
                            onClick = { menu = false; moving = true })
                        if (graph.library.folderOf(item.playlist.id) != null) DropdownMenuItem(
                            text = { Text("Take out of folder") }, leadingIcon = { Icon(Icons.Rounded.FolderOff, null) },
                            onClick = { menu = false; graph.library.removePlaylistFromFolder(item.playlist.id) })
                    }
                    is LibItem.F -> DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                        onClick = { menu = false; open() })
                }
            }
        }
    }

    if (grid) {
        Column(m.clip(shape).then(highlight).then(gestures).padding(6.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) { cover(maxWidth) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item is LibItem.P) menuButton()
            }
        }
    } else {
        Row(
            m.fillMaxWidth().then(highlight).then(gestures).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            cover(56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (item is LibItem.F) FontWeight.Bold else null)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item is LibItem.P) menuButton()
        }
    }

    if (moving && item is LibItem.P) MoveToFolderDialog(
        title = "Move “${item.playlist.name}” to…",
        onDismiss = { moving = false },
    ) { folderId ->
        graph.library.movePlaylistToFolder(item.playlist.id, folderId)
        toast(context, "Moved")
        moving = false
    }
}

/** Pick a folder (or make one). */
@Composable
fun MoveToFolderDialog(title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val graph = LocalGraph.current
    val lib by graph.library.library.collectAsState()
    var naming by remember { mutableStateOf(lib.folders.isEmpty()) }
    if (naming) {
        NameDialog("New folder", "", onDismiss = { if (lib.folders.isEmpty()) onDismiss() else naming = false }) { name ->
            onPick(graph.library.createFolder(name))
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                item {
                    Row(Modifier.fillMaxWidth().clickable { naming = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CreateNewFolder, null)
                        Spacer(Modifier.width(12.dp))
                        Text("New folder", fontWeight = FontWeight.SemiBold)
                    }
                }
                items(lib.folders) { f ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlaylistMosaic(graph.library.folderImages(f), size = 40.dp, mixed = true)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Inside a folder: its playlists, then songs moved in on their own. */
@Composable
fun FolderScreen(id: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val lib by graph.library.library.collectAsState()
    val folder = lib.folders.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (folder == null) {
        Box(Modifier.fillMaxWidth().padding(top = 48.dp)) {
            com.adabala.naada.ui.components.MessageBox("Folder not found", "It may have been deleted on another device.",
                action = "Back", onAction = nav::back)
        }
        return
    }
    val playlists = folder.playlistIds.mapNotNull { pid -> lib.playlists.firstOrNull { it.id == pid } }
    val allSongs = graph.library.folderSongs(folder)

    CollectionScaffold(
        folder.name,
        listOfNotNull(
            if (playlists.isNotEmpty()) "${playlists.size} playlists" else null,
            "${allSongs.size} songs",
        ).joinToString(" · "),
        "", allSongs,
        mosaicImages = graph.library.folderImages(folder),
        emptyTitle = "Empty folder",
        emptyBody = "Long-press a playlist in Library and drop it here, or use ⋮ → Move to folder on any song.",
        emptyIcon = Icons.Rounded.Folder,
        folderId = id,
        topActions = {
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename folder") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Delete folder") }
        },
        beforeSongs = {
            if (playlists.isNotEmpty()) {
                Text("Playlists", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                playlists.forEach { p -> LibraryEntry(LibItem.P(p), grid = false) }
                Spacer(Modifier.height(8.dp))
                Text("All songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
            }
        },
    )

    if (renaming) NameDialog("Rename folder", folder.name, onDismiss = { renaming = false }) {
        graph.library.renameFolder(id, it); renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete folder “${folder.name}”?") },
        text = { Text("Only the folder is removed. Its playlists go back to your library, and songs you moved in go back to the playlists they came from.") },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                val kept = graph.library.deleteFolder(id)
                toast(context, if (kept > 0) "Folder deleted — $kept songs kept in a playlist called “${folder.name}”" else "Folder deleted — everything is back in place")
                nav.back()
            }) { Text("Delete folder") }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}
