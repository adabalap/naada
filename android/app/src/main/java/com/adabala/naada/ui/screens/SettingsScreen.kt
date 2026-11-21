package com.adabala.naada.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.adabala.naada.BuildConfig
import com.adabala.naada.data.ApiException
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.components.Load
import com.adabala.naada.ui.components.LoadingBox
import com.adabala.naada.ui.components.MessageBox
import com.adabala.naada.ui.components.rememberLoader
import com.adabala.naada.ui.theme.Brand
import com.adabala.naada.ui.toast
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.state.collectAsState()
    var server by remember { mutableStateOf(settings.serverUrl) }
    var token by remember { mutableStateOf(settings.token) }
    var showToken by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var cacheTick by remember { mutableIntStateOf(0) }
    val streamMb = remember(cacheTick) { graph.playback.streamCacheBytes() / (1024 * 1024) }
    val dlMb = remember(cacheTick) { graph.playback.downloadBytes() / (1024 * 1024) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Section("Your server") {
            Text(
                "Naada streams through your own server. The access token is the NAADA_TOKEN set on the server (leave blank if none).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                token, { token = it }, label = { Text("Access token") }, singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show token")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !testing, onClick = {
                    scope.launch {
                        testing = true
                        graph.settings.setServer(server, token)
                        status = try {
                            val info = graph.api.info()
                            graph.library.scheduleSync()
                            "Connected · server v${info.serverVersion} · AI ${if (info.aiEnabled) "on" else "off"}"
                        } catch (e: ApiException) {
                            if (e.code == 401) "Token rejected — check NAADA_TOKEN on the server"
                            else if (e.code == 404) "Server is reachable but doesn't have API v1 yet — deploy the new app.py"
                            else "Server error ${e.code}"
                        } catch (e: Exception) {
                            "Can't reach ${server.trim()} — ${e.message ?: "network error"}"
                        }
                        testing = false
                    }
                }) { Text(if (testing) "Testing…" else "Save & test") }
                OutlinedButton(onClick = { scope.launch { if (graph.library.sync()) toast(context, "Library synced") else toast(context, "Sync failed") } }) {
                    Text("Sync library")
                }
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Section("Storage") {
            Text("Downloads: $dlMb MB · Streaming cache: $streamMb MB (max ${com.adabala.naada.playback.PlaybackGraph.STREAM_CACHE_BYTES / (1024 * 1024)} MB)",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    graph.playback.clearStreamCache()
                    cacheTick++
                }
                toast(context, "Streaming cache cleared")
            }) { Text("Clear streaming cache") }
        }

        Section("About") {
            Text("Naada · నాద ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyLarge)
            Text("Native player for your personal Naada server. Music by JioSaavn, lyrics from JioSaavn and LRCLIB, stories by Gemini.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
    }
}

// ── Trivia ──────────────────────────────────────────────────────────────────

@Composable
fun TriviaScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val recent = remember { graph.library.recentSongs(8) }
    val l = rememberLoader("trivia") { graph.api.trivia(recent) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Trivia", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        }
        when (val s = l.state) {
            is Load.Loading -> LoadingBox()
            is Load.Failed -> MessageBox("Couldn't load trivia", s.message, action = "Retry", onAction = l.reload)
            is Load.Ready -> {
                val cards = s.value.cards
                if (cards.isEmpty()) {
                    MessageBox(
                        "No trivia right now",
                        if (s.value.error == "ai_disabled") "Add a Gemini key on your server to unlock trivia."
                        else "Play a few more songs, or try again in a minute.",
                        icon = Icons.Rounded.Lightbulb, action = "Try again", onAction = l.reload
                    )
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(cards) { c -> TriviaCardView(c.question, c.answer, c.song) }
                    item {
                        Text("AI-generated from your recent listening — details may be imperfect.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TriviaCardView(question: String, answer: String, song: String) {
    var revealed by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { revealed = !revealed }.padding(18.dp)
    ) {
        if (song.isNotBlank()) Text(song, style = MaterialTheme.typography.labelMedium, color = Brand.Gold)
        Spacer(Modifier.height(4.dp))
        Text(question, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        if (revealed) Text(answer, style = MaterialTheme.typography.bodyLarge)
        else Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .15f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Tap to reveal", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
