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
    val lang = settings.homeLang
    val home = rememberLoader(lang, settings.serverUrl, settings.token) { graph.api.home(lang) }
    val recent = remember(lib.history) { graph.library.recentSongs(16) }

    PullToRefreshBox(
        isRefreshing = home.refreshing,
        onRefresh = { home.reload(); graph.library.scheduleSync() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("నాద", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                    }
                    IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, "Settings") }
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
            item { MoodCard(onSubmit = { nav.mood(it) }) }

            if (recent.isNotEmpty()) item {
                SongCardShelf("Jump back in", recent, onPlay = { graph.player.play(recent, it) }, action = "History", onAction = nav::history)
            }

            when (val s = home.state) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item {
                    MessageBox("Couldn't load Home", s.message, action = if (s.auth) "Open Settings" else "Retry",
                        onAction = { if (s.auth) nav.settings() else home.reload() })
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
}

@Composable
private fun MoodCard(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brand.gradient).padding(18.dp)
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
