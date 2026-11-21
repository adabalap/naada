package com.adabala.naada.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.adabala.naada.ui.theme.ThemeChoice
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
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(graph.library.backupJson().toByteArray()) }
        }.isSuccess
        toast(context, if (ok) "Library backed up" else "Couldn't write the backup")
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text == null) { toast(context, "Couldn't read that file"); return@launch }
            graph.library.restoreBackup(text)
                .onSuccess { toast(context, it) }
                .onFailure { toast(context, "Restore needs your server online and a Naada backup file") }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Section("Account") { AccountSection() }

        Section("Appearance") {
            Text("Theme", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ThemeChoice.entries) { t ->
                    ThemeCard(t, selected = settings.theme == t.id) { scope.launch { graph.settings.setTheme(t.id) } }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(ThemeChoice.of(settings.theme).blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Colour follows the music", style = MaterialTheme.typography.bodyLarge)
                    Text("Tint buttons and highlights with the playing song's album art",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.followArt, onCheckedChange = { on -> scope.launch { graph.settings.setFollowArt(on) } })
            }
        }

        Section("Your server") {
            Text(
                "Naada streams through your own server. Family & friends don't need the access token — they join with an invite. Only the owner enters NAADA_TOKEN here (blank if the server has none).",
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
            Spacer(Modifier.height(6.dp))
            Text(
                "Save & test stores these details and checks the server answers. Sync library sends changes made on this phone (even offline) and pulls the latest from the server — it also runs every time you open Naada.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Section("Backup & history") {
            Text(
                "Your library already lives on your server, so updates and new phones keep it. A backup file is an extra copy you control — keep it in Drive or send it to yourself.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("naada-library-${java.time.LocalDate.now()}.json") }) { Text("Back up to file") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore from file") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear listening history") }
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

    if (confirmClear) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear listening history?") },
        text = { Text("Removes recently played from this phone and your server. Likes and playlists stay.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false; graph.library.clearHistory(); toast(context, "History cleared") }) { Text("Clear") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ThemeCard(t: ThemeChoice, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(t.swatch[0])
        ) {
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(width = 44.dp, height = 10.dp)
                .clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(t.swatch.drop(1))))
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp).clip(CircleShape).background(t.swatch.last()))
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
                        else if (s.value.error == "daily_limit") "You've used today's AI allowance — more tomorrow!"
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
