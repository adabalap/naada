package com.adabala.naada.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.components.CoverCard
import com.adabala.naada.ui.components.Load
import com.adabala.naada.ui.components.LoadingBox
import com.adabala.naada.ui.components.MessageBox
import com.adabala.naada.ui.components.ScrollToTopButton
import com.adabala.naada.ui.components.rememberLoader

private data class Cover(val title: String, val subtitle: String, val image: String, val open: () -> Unit)

/** Full-page grid behind a shelf's "See all". */
@Composable
private fun CoverGridPage(title: String, subtitle: String, state: Load<List<Cover>>, retry: () -> Unit) {
    val nav = LocalNav.current
    val grid = rememberLazyGridState()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (state) {
            is Load.Loading -> LoadingBox()
            is Load.Failed -> MessageBox("Couldn't load this", state.message, action = "Retry", onAction = retry)
            is Load.Ready -> if (state.value.isEmpty()) MessageBox("Nothing here right now", "Try another language from your profile menu.")
            else Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp), state = grid,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.value.size) { i ->
                        val c = state.value[i]
                        CoverCard(c.title, c.subtitle, c.image, c.open, width = 170.dp)
                    }
                }
                ScrollToTopButton(grid)
            }
        }
    }
}

private fun langName(lang: String) = LANGS.firstOrNull { it.first == lang }?.second ?: lang

@Composable
fun NewReleasesScreen(lang: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val l = rememberLoader(lang) {
        graph.api.newReleases(lang).albums.filter { it.id.isNotBlank() }.distinctBy { it.id }
            .map { a -> Cover(a.title, listOf(a.artist, a.year).filter { it.isNotBlank() }.joinToString(" · "), a.image) { nav.album(a.id) } }
    }
    CoverGridPage("New releases", langName(lang), l.state, l.reload)
}

@Composable
fun FeaturedPlaylistsScreen(lang: String) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val l = rememberLoader(lang) {
        graph.api.featured(lang).playlists.filter { it.id.isNotBlank() }.distinctBy { it.id }
            .map { p -> Cover(p.title, if (p.count.isNotBlank()) "${p.count} songs" else "", p.image) { nav.playlist(p.id) } }
    }
    CoverGridPage("Curated playlists", langName(lang), l.state, l.reload)
}

