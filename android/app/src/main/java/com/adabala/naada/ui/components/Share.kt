package com.adabala.naada.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adabala.naada.data.Song
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.screens.PlaylistMosaic
import com.adabala.naada.ui.toast
import kotlinx.coroutines.launch

private const val LIST_LIMIT = 20

fun shareMessage(name: String, songs: List<Song>, url: String?): String = buildString {
    append("🎧 ").append(name).append(" — ").append(songs.size).append(if (songs.size == 1) " song" else " songs")
    if (url != null) {
        append("\n\nListen in Spotify, YouTube Music, JioSaavn or Apple Music:\n").append(url)
    }
    append("\n\n")
    songs.take(LIST_LIMIT).forEachIndexed { i, s ->
        append(i + 1).append(". ").append(s.title)
        if (s.artist.isNotBlank()) append(" — ").append(s.artist.substringBefore(","))
        append('\n')
    }
    if (songs.size > LIST_LIMIT) append("…and ${songs.size - LIST_LIMIT} more\n")
    append("\nShared from Naada · నాద")
}

private fun send(context: Context, text: String, pkg: String? = null): Boolean {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (pkg != null) setPackage(pkg)
    }
    return try {
        context.startActivity(if (pkg == null) Intent.createChooser(intent, "Share playlist") else intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * Share a list of songs. A link creates a public page on your server where
 * friends can open each song in the music app they already use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(name: String, songs: List<Song>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun withLink(block: (String?) -> Unit) {
        link?.let { block(it); return }
        busy = true
        scope.launch {
            val url = runCatching { graph.api.share(name, songs).url }.getOrNull()
            busy = false
            link = url
            if (url == null) toast(context, "Couldn't create a link — sharing the song list instead")
            block(url)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistMosaic(songs.take(4).map { it.image })
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${songs.size} songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Text(
                "Friends get a page with every song and buttons to play it in Spotify, YouTube Music, JioSaavn or Apple Music. Naada users open it straight in the app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            ShareOption(Icons.AutoMirrored.Rounded.Chat, "WhatsApp", Color(0xFF25D366)) {
                withLink { url ->
                    val text = shareMessage(name, songs, url)
                    if (!send(context, text, "com.whatsapp") && !send(context, text, "com.whatsapp.w4b")) send(context, text)
                    onDismiss()
                }
            }
            ShareOption(Icons.Rounded.IosShare, "Share link…", MaterialTheme.colorScheme.primary) {
                withLink { url -> send(context, shareMessage(name, songs, url)); onDismiss() }
            }
            ShareOption(Icons.Rounded.ContentCopy, "Copy link", MaterialTheme.colorScheme.secondary) {
                withLink { url ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(name, url ?: shareMessage(name, songs, null)))
                    toast(context, if (url != null) "Link copied" else "Song list copied")
                    onDismiss()
                }
            }
            ShareOption(Icons.AutoMirrored.Rounded.Notes, "Share as a song list (no link)", MaterialTheme.colorScheme.tertiary) {
                send(context, shareMessage(name, songs, null)); onDismiss()
            }
        }
    }
}

@Composable
private fun ShareOption(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint)
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
