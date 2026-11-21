package com.adabala.naada.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Emits a tab route when the user taps the tab they're already on. */
val LocalTabReselect = compositionLocalOf<SharedFlow<String>> { MutableSharedFlow() }

/** Jump to the top when [tab] is re-tapped in the bottom bar. */
@Composable
fun ScrollToTopOnReselect(tab: String, scrollToTop: suspend () -> Unit) {
    val events = LocalTabReselect.current
    LaunchedEffect(events, tab) {
        events.collect { if (it == tab) scrollToTop() }
    }
}

@Composable
private fun BoxScope.TopButton(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) { Icon(Icons.Rounded.KeyboardArrowUp, "Back to top", Modifier.size(28.dp)) }
    }
}

/** A floating ↑ that appears once you've scrolled a screen or two down. */
@Composable
fun BoxScope.ScrollToTopButton(state: LazyListState, tab: String? = null) {
    val scope = rememberCoroutineScope()
    val show by remember { derivedStateOf { state.firstVisibleItemIndex > 4 } }
    TopButton(show) { scope.launch { state.animateScrollToItem(0) } }
    if (tab != null) ScrollToTopOnReselect(tab) { state.animateScrollToItem(0) }
}

@Composable
fun BoxScope.ScrollToTopButton(state: LazyGridState, tab: String? = null) {
    val scope = rememberCoroutineScope()
    val show by remember { derivedStateOf { state.firstVisibleItemIndex > 8 } }
    TopButton(show) { scope.launch { state.animateScrollToItem(0) } }
    if (tab != null) ScrollToTopOnReselect(tab) { state.animateScrollToItem(0) }
}
