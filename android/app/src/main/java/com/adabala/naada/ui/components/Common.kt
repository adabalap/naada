package com.adabala.naada.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.adabala.naada.data.Album
import com.adabala.naada.data.Artist
import com.adabala.naada.data.PlaylistRef
import com.adabala.naada.data.Song
import com.adabala.naada.ui.theme.Brand
import com.adabala.naada.ui.theme.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun https(url: String) = url.replace("http://", "https://")

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatDuration(sec: Int) = formatTime(sec * 1000L)

@Composable
fun Artwork(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    val m = (if (size != null) modifier.size(size) else modifier).clip(shape)
    Box(m.background(Brush.linearGradient(listOf(Brand.Purple, Brand.Rose.copy(alpha = .7f))))) {
        Icon(
            Icons.Rounded.MusicNote, null,
            tint = Color.White.copy(alpha = .5f),
            modifier = Modifier.align(Alignment.Center).size(28.dp)
        )
        if (url.isNotEmpty()) {
            AsyncImage(
                model = https(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Colours sampled from album art, for backgrounds that follow the music. */
data class ArtColors(val vibrant: Color, val dark: Color, val muted: Color)

@Composable
fun rememberArtColors(url: String?): ArtColors? {
    val context = LocalContext.current
    var colors by remember(url) { mutableStateOf<ArtColors?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrEmpty()) return@LaunchedEffect
        colors = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(https(url)).allowHardware(false).size(160).build()
                val res = context.imageLoader.execute(req) as? SuccessResult ?: return@runCatching null
                val p = Palette.from(res.drawable.toBitmap()).generate()
                val fallback = 0xFF4A1A6B.toInt()
                ArtColors(
                    vibrant = Color(p.getVibrantColor(p.getLightVibrantColor(p.getDominantColor(fallback)))),
                    dark = Color(p.getDarkMutedColor(p.getDarkVibrantColor(0xFF120C1A.toInt()))),
                    muted = Color(p.getMutedColor(p.getDominantColor(fallback))),
                )
            }.getOrNull()
        }
    }
    return colors
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = SectionTitle, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    liked: Boolean = false,
    downloaded: Boolean = false,
    index: Int? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                "$index", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.width(28.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Box {
            Artwork(song.image, size = 52.dp, shape = RoundedCornerShape(8.dp))
            if (playing) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .45f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.GraphicEq, null, tint = Color.White) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(Icons.Rounded.DownloadDone, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                if (liked) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(13.dp), tint = Brand.Rose)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CoverCard(
    title: String,
    subtitle: String,
    image: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp,
    circle: Boolean = false,
) {
    Column(
        modifier.width(width).clip(RoundedCornerShape(12.dp)).combinedClickableSimple(onClick).padding(4.dp)
    ) {
        Artwork(
            image, Modifier.fillMaxWidth().height(width - 8.dp),
            shape = if (circle) CircleShape else RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            textAlign = if (circle) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (circle) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableSimple(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

@Composable
fun AlbumShelf(title: String, albums: List<Album>, onOpen: (Album) -> Unit) {
    if (albums.isEmpty()) return
    SectionHeader(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(albums) { a -> CoverCard(a.title, a.artist.ifBlank { a.year }, a.image, { onOpen(a) }) }
    }
}

@Composable
fun PlaylistShelf(title: String, playlists: List<PlaylistRef>, onOpen: (PlaylistRef) -> Unit) {
    if (playlists.isEmpty()) return
    SectionHeader(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(playlists) { p -> CoverCard(p.title, "", p.image, { onOpen(p) }) }
    }
}

@Composable
fun ArtistShelf(title: String, artists: List<Artist>, onOpen: (Artist) -> Unit) {
    if (artists.isEmpty()) return
    SectionHeader(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(artists) { a -> CoverCard(a.name, a.role, a.image, { onOpen(a) }, width = 120.dp, circle = true) }
    }
}

@Composable
fun SongCardShelf(title: String, songs: List<Song>, onPlay: (Int) -> Unit, action: String? = null, onAction: (() -> Unit)? = null) {
    if (songs.isEmpty()) return
    SectionHeader(title, action = action, onAction = onAction)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(songs.size) { i -> val s = songs[i]; CoverCard(s.title, s.artist, s.image, { onPlay(i) }) }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageBox(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.CloudOff,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}
