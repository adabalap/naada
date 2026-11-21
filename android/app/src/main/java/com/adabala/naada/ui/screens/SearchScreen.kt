package com.adabala.naada.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.adabala.naada.data.Album
import com.adabala.naada.data.Artist
import com.adabala.naada.data.Song
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.components.CoverCard
import com.adabala.naada.ui.components.Load
import com.adabala.naada.ui.components.LoadingBox
import com.adabala.naada.ui.components.MessageBox
import com.adabala.naada.ui.components.SongActionsSheet
import com.adabala.naada.ui.components.SongRow
import com.adabala.naada.ui.components.friendlyError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember

private val TYPES = listOf("Songs", "Albums", "Artists")

@Composable
fun SearchScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val settings by graph.settings.state.collectAsState()
    val lib by graph.library.library.collectAsState()
    val downloads by graph.playback.downloads.collectAsState()
    val ui by graph.player.ui.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableIntStateOf(0) }
    var songs by remember { mutableStateOf<Load<List<Song>>?>(null) }
    var albums by remember { mutableStateOf<Load<List<Album>>?>(null) }
    var artists by remember { mutableStateOf<Load<List<Artist>>?>(null) }
    var actionFor by remember { mutableStateOf<Song?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query, type) {
        val q = query.trim()
        if (q.length < 2) { songs = null; albums = null; artists = null; return@LaunchedEffect }
        delay(350)   // debounce typing
        try {
            when (type) {
                0 -> { songs = Load.Loading; songs = Load.Ready(graph.api.searchSongs(q).songs) }
                1 -> { albums = Load.Loading; albums = Load.Ready(graph.api.searchAlbums(q).albums) }
                else -> { artists = Load.Loading; artists = Load.Ready(graph.api.searchArtists(q).artists) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val f = friendlyError(e)
            when (type) { 0 -> songs = f; 1 -> albums = f; else -> artists = f }
        }
    }

    fun commit() {
        keyboard?.hide()
        scope.launch { graph.settings.addRecentSearch(query) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Search", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Songs, albums, artists, movies") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Clear") } },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { commit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
            TYPES.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = type == i, onClick = { type = i },
                    shape = SegmentedButtonDefaults.itemShape(i, TYPES.size),
                ) { Text(label) }
            }
        }

        if (query.trim().length < 2) {
            RecentSearches(settings.recentSearches, onPick = { query = it }, onClear = {
                scope.launch { graph.settings.clearRecentSearches() }
            })
            return@Column
        }

        val state: Load<List<*>>? = when (type) { 0 -> songs; 1 -> albums; else -> artists }
        when (state) {
            null, is Load.Loading -> LoadingBox()
            is Load.Failed -> MessageBox("Search failed", state.message)
            is Load.Ready -> if (state.value.isEmpty()) {
                MessageBox("No results", "Try a different spelling, or the movie name.", icon = Icons.Rounded.SearchOff)
            } else when (type) {
                0 -> {
                    val list = (songs as Load.Ready).value
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        itemsIndexed(list) { i, s ->
                            SongRow(
                                s, onClick = { commit(); graph.player.play(list, i) }, onMore = { actionFor = s },
                                playing = ui.song?.id == s.id, liked = lib.likes.any { it.id == s.id },
                                downloaded = downloads[s.id]?.done == true,
                            )
                        }
                    }
                }
                1 -> Grid((albums as Load.Ready).value.map { Triple(it.title, it.artist.ifBlank { it.year }, it.image) }) { i ->
                    commit(); nav.album((albums as Load.Ready).value[i].id)
                }
                else -> Grid((artists as Load.Ready).value.map { Triple(it.name, it.role, it.image) }, circle = true) { i ->
                    commit(); nav.artist((artists as Load.Ready).value[i].id)
                }
            }
        }
    }

    actionFor?.let { s -> SongActionsSheet(s, onDismiss = { actionFor = null }) }
}

@Composable
private fun Grid(items: List<Triple<String, String, String>>, circle: Boolean = false, onOpen: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items.size) { i ->
            val (t, sub, img) = items[i]
            CoverCard(t, sub, img, { onOpen(i) }, width = 170.dp, circle = circle)
        }
    }
}

@Composable
private fun RecentSearches(recents: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (recents.isEmpty()) {
        MessageBox("Find your music", "Search JioSaavn's whole catalogue — Telugu, Hindi, Tamil and more.", icon = Icons.Rounded.Search)
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("Clear") }
    }
    recents.forEach { r ->
        Row(
            Modifier.fillMaxWidth().clickable { onPick(r) }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(r, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
