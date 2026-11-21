@file:OptIn(UnstableApi::class)

package com.adabala.naada.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.adabala.naada.NaadaApp
import com.adabala.naada.R
import com.adabala.naada.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * The native foreground service that keeps music alive with the screen off,
 * owns the notification / lock-screen / Bluetooth controls, and survives the
 * UI being swiped away.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val app get() = application as NaadaApp
    private val likeCommand = SessionCommand(CMD_LIKE, Bundle.EMPTY)
    private val retried = HashSet<String>()
    private var lastRecordedId: String? = null
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        val graph = app.graph

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.playback.playbackFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)      // pause when headphones unplug
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(PlayerEvents())

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_naada)
            }
        )

        restoreQueue()

        // Liking from the app updates the heart in the notification too.
        scope.launch {
            graph.library.library.distinctUntilChangedBy { lib -> lib.likes.map { it.id } }
                .collect { updateLikeButton() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused ends the service; while playing, music continues.
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        saveQueue()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ── Queue persistence ──────────────────────────────────────────────────
    private fun saveQueue() {
        if (!::player.isInitialized) return
        val songs = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toSong() }
        app.graph.queueStore.save(songs, player.currentMediaItemIndex, player.currentPosition)
    }

    private fun restoreQueue() {
        val saved = app.graph.queueStore.load() ?: return
        if (saved.songs.isEmpty()) return
        player.setMediaItems(
            saved.songs.map { it.toMediaItem() },
            saved.index.coerceIn(0, saved.songs.lastIndex),
            saved.positionMs
        )
        lastRecordedId = saved.songs.getOrNull(saved.index)?.id
    }

    // ── Like button in the notification ────────────────────────────────────
    private fun updateLikeButton() {
        val id = player.currentMediaItem?.mediaId ?: return
        val liked = app.graph.library.isLiked(id)
        val button = CommandButton.Builder()
            .setDisplayName(if (liked) "Unlike" else "Like")
            .setIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            .setSessionCommand(likeCommand)
            .build()
        session?.setCustomLayout(ImmutableList.of(button))
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(likeCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            updateLikeButton()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_LIKE) {
                player.currentMediaItem?.toSong()?.let { app.graph.library.toggleLike(it) }
                updateLikeButton()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withPlaybackUri() }.toMutableList())

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = app.graph.queueStore.load()
            val result = if (saved == null || saved.songs.isEmpty()) {
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
            } else {
                MediaSession.MediaItemsWithStartPosition(
                    saved.songs.map { it.toMediaItem() }, saved.index, saved.positionMs
                )
            }
            return Futures.immediateFuture(result)
        }
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateLikeButton()
            saveQueue()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                val item = player.currentMediaItem ?: return
                if (item.mediaId != lastRecordedId) {
                    lastRecordedId = item.mediaId
                    item.toSong()?.let { app.graph.library.recordPlay(it) }
                }
            } else {
                saveQueue()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                player.currentMediaItem?.mediaId?.let { retried.remove(it) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            app.graph.playback.resolver.invalidate(id)
            if (retried.add(id)) {
                // Most failures are an expired or rejected CDN URL: resolve again once.
                player.prepare()
                player.play()
            } else if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    companion object {
        const val CMD_LIKE = "naada.LIKE"
    }
}
