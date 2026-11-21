@file:OptIn(UnstableApi::class)

package com.adabala.naada.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.adabala.naada.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUi(
    val connected: Boolean = false,
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val error: String? = null,
)

/** Sleep timer: either a clock time, or "when this song ends". */
data class SleepTimer(val endsAtMs: Long? = null, val endOfTrack: Boolean = false)

/**
 * The UI's handle on playback. Talks to [PlaybackService] through a
 * MediaController, so the notification, lock screen and app always agree on
 * what is playing — there is exactly one queue, and it lives in the service.
 */
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val graph: PlaybackGraph,
) {
    private var controller: MediaController? = null
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimer?>(null)
    val sleep: StateFlow<SleepTimer?> = _sleep.asStateFlow()
    private var sleepJob: Job? = null
    private var ticker: Job? = null

    fun connect() {
        if (controller != null) return
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull()
                ?: return@launch
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _ui.update { it.copy(error = "Couldn't play this song") }
                }
            })
            refresh()
            startTicker()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _ui.update {
                        it.copy(positionMs = c.currentPosition, bufferedMs = c.bufferedPosition,
                            durationMs = c.duration.coerceAtLeast(0))
                    }
                }
                delay(500)
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).toSong() }
        _ui.update {
            it.copy(
                connected = true,
                song = c.currentMediaItem?.toSong(),
                isPlaying = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition,
                durationMs = c.duration.coerceAtLeast(0),
                bufferedMs = c.bufferedPosition,
                queue = queue,
                index = c.currentMediaItemIndex,
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                speed = c.playbackParameters.speed,
                error = if (c.playerError != null) it.error else null,
            )
        }
    }

    // ── Commands ───────────────────────────────────────────────────────────
    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        songs.forEach { graph.resolver.prime(it) }
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(0, songs.lastIndex)
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(songs.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        graph.resolver.prime(song)
        if (c.mediaItemCount == 0) return play(listOf(song))
        c.addMediaItem((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), song.toMediaItem())
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(songs)
        songs.forEach { graph.resolver.prime(it) }
        c.addMediaItems(songs.map { it.toMediaItem() })
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.play()
        }
    }

    fun next() { controller?.seekToNextMediaItem() }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 4000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _ui.update { it.copy(positionMs = ms) }
    }

    fun skipTo(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun removeAt(index: Int) { controller?.removeMediaItem(index) }
    fun move(from: Int, to: Int) { controller?.moveMediaItem(from, to) }

    fun clearUpcoming() {
        val c = controller ?: return
        val start = c.currentMediaItemIndex + 1
        if (start < c.mediaItemCount) c.removeMediaItems(start, c.mediaItemCount)
    }

    /** Close the player: stop, clear the queue, drop the notification. Returns what to undo. */
    fun dismiss(): SavedQueue? {
        val c = controller ?: return null
        val songs = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).toSong() }
        val saved = if (songs.isEmpty()) null else SavedQueue(songs, c.currentMediaItemIndex, c.currentPosition)
        setSleepTimer(null)
        c.stop()
        c.clearMediaItems()
        return saved
    }

    /** Bring back a dismissed queue, paused where it was. */
    fun restore(saved: SavedQueue) {
        val c = controller ?: return
        saved.songs.forEach { graph.resolver.prime(it) }
        c.setMediaItems(saved.songs.map { it.toMediaItem() }, saved.index.coerceIn(0, saved.songs.lastIndex), saved.positionMs)
        c.prepare()
    }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(speed: Float) { controller?.playbackParameters = PlaybackParameters(speed) }

    fun clearError() = _ui.update { it.copy(error = null) }

    // ── Sleep timer ────────────────────────────────────────────────────────
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _sleep.value = null; return }
        if (minutes == 0) {
            _sleep.value = SleepTimer(endOfTrack = true)
            sleepJob = scope.launch {
                val startId = controller?.currentMediaItem?.mediaId
                while (isActive) {
                    val c = controller ?: break
                    val remaining = c.duration - c.currentPosition
                    if (c.currentMediaItem?.mediaId != startId || (c.duration > 0 && remaining < 600)) {
                        c.pause(); break
                    }
                    delay(300)
                }
                _sleep.value = null
            }
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _sleep.value = SleepTimer(endsAtMs = end)
        sleepJob = scope.launch {
            // Fade out over the last 20 seconds instead of cutting off.
            while (isActive && System.currentTimeMillis() < end - 20_000) delay(1000)
            val c = controller
            if (c != null) {
                val startVol = c.volume
                for (i in 20 downTo 1) { c.volume = startVol * i / 20f; delay(1000) }
                c.pause()
                c.volume = startVol
            }
            _sleep.value = null
        }
    }

    // ── Downloads ──────────────────────────────────────────────────────────
    fun download(songs: List<Song>) {
        songs.forEach { s ->
            DownloadService.sendAddDownload(context, NaadaDownloadService::class.java, graph.requestFor(s), false)
        }
    }

    fun removeDownload(id: String) {
        DownloadService.sendRemoveDownload(context, NaadaDownloadService::class.java, id, false)
    }
}
