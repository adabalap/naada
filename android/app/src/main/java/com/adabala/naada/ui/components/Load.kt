package com.adabala.naada.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.adabala.naada.data.ApiException

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String, val auth: Boolean = false) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val refreshing: Boolean, val reload: () -> Unit)

fun friendlyError(e: Throwable): Load.Failed = when {
    e is ApiException && e.code == 401 -> Load.Failed("Join with an invite code or sign in to use this server.", auth = true)
    e is ApiException -> Load.Failed(e.message ?: "Server error")
    else -> Load.Failed("Can't reach your Naada server. Check your connection or the server address in Settings.")
}

/** Load data for a screen, keeping the old value visible while refreshing. */
@Composable
fun <T> rememberLoader(vararg keys: Any?, block: suspend () -> T): Loader<T> {
    var tick by remember { mutableIntStateOf(0) }
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(*keys, tick) {
        if (state !is Load.Ready) state = Load.Loading else refreshing = true
        state = try {
            Load.Ready(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state is Load.Ready) state else friendlyError(e)
        }
        refreshing = false
    }
    return Loader(state, refreshing) { tick++ }
}
