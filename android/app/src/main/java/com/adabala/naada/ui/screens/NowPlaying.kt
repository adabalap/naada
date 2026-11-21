package com.adabala.naada.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BedtimeOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.adabala.naada.ui.theme.LocalNaadaStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.adabala.naada.data.Song
import com.adabala.naada.playback.PlayerUi
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.components.Artwork
import com.adabala.naada.ui.components.Load
import com.adabala.naada.ui.components.SongActionsSheet
import com.adabala.naada.ui.components.formatTime
import com.adabala.naada.ui.components.rememberArtColors
import com.adabala.naada.ui.components.rememberLoader
import com.adabala.naada.ui.theme.Brand
import com.adabala.naada.ui.toast
import kotlin.math.abs

private enum class Panel { Art, Lyrics, Story, Queue }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val ui by graph.player.ui.collectAsState()
    val lib by graph.library.library.collectAsState()
    val downloads by graph.playback.downloads.collectAsState()
    val sleep by graph.player.sleep.collectAsState()
    val song = ui.song ?: return
    val art = rememberArtColors(song.image)
    val liked = lib.likes.any { it.id == song.id }
    val dl = downloads[song.id]
    var panel by remember { mutableStateOf(Panel.Art) }
    var showActions by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }

    val bgTop = art?.dark ?: Brand.Night
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { translationY = dragY.coerceAtLeast(0f) }
                .background(Brush.verticalGradient(listOf(bgTop, Color(0xFF0B0710))))
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
                // Top bar — drag it down to close.
                Row(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (dragY > 220f) onClose(); dragY = 0f },
                            onDragCancel = { dragY = 0f },
                        ) { _, delta -> dragY += delta }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = Color.White, modifier = Modifier.size(32.dp)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOW PLAYING", color = Color.White.copy(alpha = .6f), fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(song.album.ifBlank { song.artist }, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { showActions = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(panel, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) }, label = "panel") { p ->
                        when (p) {
                            Panel.Art -> ArtPanel(song, ui)
                            Panel.Lyrics -> LyricsPanel(song, ui.positionMs)
                            Panel.Story -> StoryPanel(song)
                            Panel.Queue -> QueuePanel(ui)
                        }
                    }
                }

                // Title row
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1, modifier = Modifier.basicMarquee())
                        Text(song.artist, color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val heartScale by animateFloatAsState(if (liked) 1.15f else 1f, spring(dampingRatio = .35f), label = "heart")
                    val haptics = LocalHapticFeedback.current
                    IconButton(onClick = { graph.library.toggleLike(song); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like",
                            tint = if (liked) Brand.Rose else Color.White, modifier = Modifier.size(28.dp).scale(heartScale))
                    }
                }

                SeekBar(ui, onSeek = { graph.player.seekTo(it) })

                // Transport
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = graph.player::toggleShuffle) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (ui.shuffle) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .7f))
                    }
                    IconButton(onClick = graph.player::previous, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Box(
                        Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable(onClick = graph.player::togglePlay),
                        contentAlignment = Alignment.Center
                    ) {
                        if (ui.buffering && !ui.isPlaying) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                        else Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = Color.Black, modifier = Modifier.size(42.dp))
                    }
                    IconButton(onClick = graph.player::next, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = graph.player::cycleRepeat) {
                        Icon(
                            if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                            tint = if (ui.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .7f)
                        )
                    }
                }

                // Panels + tools
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PanelButton(Icons.Rounded.Lyrics, "Lyrics", panel == Panel.Lyrics) { panel = if (panel == Panel.Lyrics) Panel.Art else Panel.Lyrics }
                    PanelButton(Icons.Rounded.AutoAwesome, "Story", panel == Panel.Story) { panel = if (panel == Panel.Story) Panel.Art else Panel.Story }
                    PanelButton(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", panel == Panel.Queue) { panel = if (panel == Panel.Queue) Panel.Art else Panel.Queue }
                    PanelButton(if (sleep != null) Icons.Rounded.Bedtime else Icons.Rounded.BedtimeOff, sleepLabel(sleep), sleep != null) { showSleep = true }
                    PanelButton(if (dl?.done == true) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                        when { dl?.done == true -> "Saved"; dl?.active == true -> "${dl.percent.toInt().coerceAtLeast(0)}%"; else -> "Save" },
                        dl?.done == true) {
                        if (dl == null || dl.failed) { graph.player.download(listOf(song)); toast(context, "Downloading for offline") }
                    }
                }
            }
        }
    }

    if (showActions) SongActionsSheet(song, onDismiss = { showActions = false })
    if (showSleep) SleepDialog(onPick = { graph.player.setSleepTimer(it); showSleep = false }, onDismiss = { showSleep = false })
}

private fun sleepLabel(t: com.adabala.naada.playback.SleepTimer?): String = when {
    t == null -> "Sleep"
    t.endOfTrack -> "End"
    t.endsAtMs != null -> "${((t.endsAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1}m"
    else -> "Sleep"
}

@Composable
private fun PanelButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .75f)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = tint)
        Text(label, color = tint, fontSize = 11.sp)
    }
}

@Composable
private fun ArtPanel(song: Song, ui: PlayerUi) {
    val graph = LocalGraph.current
    var dragX by remember { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(if (ui.isPlaying) 1f else .88f, spring(dampingRatio = .6f), label = "artScale")
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight) - 8.dp
        Artwork(
            song.image,
            Modifier.size(side)
                .graphicsLayer { translationX = dragX; rotationZ = dragX / 60f; alpha = 1f - abs(dragX) / 1400f }
                .scale(scale)
                .shadow(24.dp, RoundedCornerShape(20.dp))
                .pointerInput(song.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                dragX < -180f -> graph.player.next()
                                dragX > 180f -> graph.player.previous()
                            }
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                    ) { _, d -> dragX += d }
                },
            shape = RoundedCornerShape(20.dp),
        )
    }
}

@Composable
private fun SeekBar(ui: PlayerUi, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = ui.durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else (ui.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { onSeek((dragValue * dur).toLong()); dragging = false },
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = .25f),
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime((value * dur).toLong()), color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (ui.durationMs > 0) formatTime(ui.durationMs) else "--:--", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
        }
    }
}

// ── Lyrics ──────────────────────────────────────────────────────────────────

private data class LrcLine(val timeMs: Long, val text: String)

private val LRC = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

private fun parseLrc(lrc: String): List<LrcLine> = lrc.lines().flatMap { line ->
    val stamps = LRC.findAll(line).toList()
    if (stamps.isEmpty()) return@flatMap emptyList()
    val text = line.substring(stamps.last().range.last + 1).trim()
    stamps.map { m ->
        val frac = m.groupValues[3]
        val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
        LrcLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms, text)
    }
}.sortedBy { it.timeMs }

@Composable
private fun LyricsPanel(song: Song, positionMs: Long) {
    val graph = LocalGraph.current
    val l = rememberLoader(song.id) { graph.api.lyrics(song) }
    when (val s = l.state) {
        is Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        is Load.Failed -> CenterNote("Lyrics unavailable right now")
        is Load.Ready -> {
            val synced = remember(s.value.synced) { parseLrc(s.value.synced) }
            when {
                synced.isNotEmpty() -> SyncedLyrics(synced, positionMs) { graph.player.seekTo(it) }
                s.value.lyrics.isNotBlank() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                    Text(s.value.lyrics, color = Color.White, fontSize = 20.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                    if (s.value.copyright.isNotBlank()) Text(s.value.copyright, color = Color.White.copy(alpha = .5f), fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
                }
                else -> CenterNote("No lyrics found for this song")
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LrcLine>, positionMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val current = remember(lines, positionMs) {
        lines.indexOfLast { it.timeMs <= positionMs + 300 }.coerceAtLeast(0)
    }
    LaunchedEffect(current) { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(lines) { i, line ->
            val active = i == current
            val alpha by animateFloatAsState(if (active) 1f else if (i < current) .35f else .55f, label = "lyricAlpha")
            Text(
                line.text.ifBlank { "♪" },
                color = Color.White.copy(alpha = alpha),
                fontSize = if (active) 26.sp else 22.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSeek(line.timeMs) }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = .7f), textAlign = TextAlign.Center)
    }
}

// ── Story (AI) ──────────────────────────────────────────────────────────────

@Composable
private fun StoryPanel(song: Song) {
    val graph = LocalGraph.current
    val l = rememberLoader(song.id) { graph.api.anecdote(song) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .08f)).padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Brand.Gold)
                Spacer(Modifier.width(8.dp))
                Text("Behind the song", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            when (val s = l.state) {
                is Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Gold)
                is Load.Failed -> Text(s.message, color = Color.White.copy(alpha = .8f))
                is Load.Ready -> {
                    val msg = when (s.value.error) {
                        "" -> s.value.text
                        "ai_disabled" -> "AI stories need a Gemini key on your server."
                        "cooldown" -> "The AI is resting for a minute — try again shortly."
                        "daily_limit" -> "You've enjoyed today's AI stories — more tomorrow!"
                        else -> "No story for this one yet."
                    }
                    Text(msg, color = Color.White, fontSize = 18.sp, lineHeight = 28.sp)
                    if (s.value.error.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("AI-generated — details may be imperfect.", color = Color.White.copy(alpha = .45f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ── Queue ───────────────────────────────────────────────────────────────────

@Composable
private fun QueuePanel(ui: PlayerUi) {
    val graph = LocalGraph.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ui.index.coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next · ${(ui.queue.size - ui.index - 1).coerceAtLeast(0)}", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = graph.player::clearUpcoming) { Text("Clear", color = Color.White.copy(alpha = .8f)) }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(ui.queue) { i, s ->
                val current = i == ui.index
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (current) Color.White.copy(alpha = .12f) else Color.Transparent)
                        .clickable { graph.player.skipTo(i) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Artwork(s.image, size = 44.dp, shape = RoundedCornerShape(6.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = if (current) MaterialTheme.colorScheme.primary else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(s.artist, color = Color.White.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    }
                    if (!current) {
                        if (i > ui.index + 1) IconButton(onClick = { graph.player.move(i, ui.index + 1) }) {
                            Icon(Icons.Rounded.KeyboardArrowUp, "Play next", tint = Color.White.copy(alpha = .7f))
                        }
                        IconButton(onClick = { graph.player.removeAt(i) }) {
                            Icon(Icons.Rounded.Close, "Remove", tint = Color.White.copy(alpha = .7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepDialog(onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", 0 to "End of this song")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text("Music fades out gently when the timer ends.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                options.forEach { (m, label) ->
                    Text(label, Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Mini player ─────────────────────────────────────────────────────────────

/**
 * The capsule above the tabs. Tap or swipe up → full player. Swipe left/right
 * → next/previous. Swipe down or tap ✕ → close the player (with undo).
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val haptics = LocalHapticFeedback.current
    val ui by graph.player.ui.collectAsState()
    val song = ui.song ?: return
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val progress = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(LocalNaadaStyle.current.corner),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .graphicsLayer {
                translationX = dragX
                translationY = dragY.coerceAtLeast(0f)
                alpha = 1f - (dragY.coerceAtLeast(0f) / 300f).coerceIn(0f, .7f)
            }
            .pointerInput(song.id) {
                detectDragGestures(
                    onDragEnd = {
                        val horizontal = abs(dragX) > abs(dragY)
                        when {
                            horizontal && dragX < -150f -> { graph.player.next(); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            horizontal && dragX > 150f -> { graph.player.previous(); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            !horizontal && dragY > 120f -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onDismiss() }
                            !horizontal && dragY < -80f -> onOpen()
                        }
                        dragX = 0f; dragY = 0f
                    },
                    onDragCancel = { dragX = 0f; dragY = 0f },
                ) { change, d ->
                    change.consume()
                    if (abs(dragX) >= abs(dragY)) dragX += d.x
                    if (abs(dragY) >= abs(dragX)) dragY += d.y
                }
            }
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(song.image, size = 44.dp, shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = graph.player::previous, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                IconButton(onClick = graph.player::togglePlay, modifier = Modifier.size(44.dp)) {
                    if (ui.buffering && !ui.isPlaying) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = graph.player::next, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.SkipNext, "Next") }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, "Close player", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}
