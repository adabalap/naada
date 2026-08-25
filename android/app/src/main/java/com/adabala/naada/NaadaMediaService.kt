package com.adabala.naada

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray

/**
 * The audio engine. This is what makes background playback actually work.
 *
 * WHY IT IS SHAPED LIKE THIS
 *
 * The previous version held a MediaSession but played nothing. The sound
 * came from an audio element inside the WebView, and this service only
 * forwarded transport commands back into JavaScript. That could never work:
 * a WebView belongs to the Activity, so when the screen goes off Android
 * freezes its media and JS timers, and the "ended" handler that starts the
 * next track never runs. A healthy foreground service does not help if the
 * service is not the thing producing sound. Hence two or three songs, then
 * silence.
 *
 * ExoPlayer now runs inside this foreground service and owns the queue. The
 * OS sees a genuine media-playing service, and playback continues with the
 * screen off until the queue ends or the user stops it. The WebView is
 * purely UI: it hands over a playlist and receives progress callbacks, and
 * it can be frozen without interrupting anything.
 *
 * media3 also renders the notification from each MediaItem's metadata,
 * including artworkUri, which removes the hand-rolled artwork fetching that
 * was failing silently before.
 */
class NaadaMediaService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service get() = this@NaadaMediaService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder
    }

    override fun onCreate() {
        super.onCreate()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true                                // handle audio focus
            )
            // A real wake lock held by a foreground service, unlike the web
            // Screen Wake Lock which is released as soon as the page hides.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)      // pause on headphone unplug
            .build()

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(TAG, "isPlaying=$isPlaying")
                notifyWeb("window.__onNativeState && window.__onNativeState($isPlaying)")
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val idx = exo.currentMediaItemIndex
                Log.i(TAG, "track -> [$idx] ${item?.mediaMetadata?.title}")
                // Best effort. The WebView may be frozen, and that is fine:
                // playback does not depend on this arriving.
                notifyWeb("window.__onNativeTrack && window.__onNativeTrack($idx)")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    Log.i(TAG, "queue exhausted")
                    notifyWeb("window.__onNativeEnded && window.__onNativeEnded()")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "player error: ${error.errorCodeName}", error)
                // Skip a dead track rather than stalling the whole queue.
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                }
            }
        })

        player = exo

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(openApp)
            .build()

        Log.i(TAG, "NaadaMediaService ready - ExoPlayer owns playback")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    /**
     * Replace the queue and start playing.
     *
     * tracksJson: [{url,title,artist,album,art,id}, ...] with already
     * resolved stream URLs. The whole list is handed over so the service can
     * advance on its own and never needs to call back into JavaScript to
     * continue. That is exactly what lets playback survive a frozen WebView.
     */
    fun setQueue(tracksJson: String, startIndex: Int, positionMs: Long) {
        val exo = player ?: return
        val items = mutableListOf<MediaItem>()
        try {
            val arr = JSONArray(tracksJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                if (url.isBlank()) continue
                val art = o.optString("art")
                items.add(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaId(o.optString("id", url))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(o.optString("title"))
                                .setArtist(o.optString("artist"))
                                .setAlbumTitle(o.optString("album", "Naada"))
                                .setArtworkUri(if (art.isBlank()) null else Uri.parse(art))
                                .build()
                        )
                        .build()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "bad queue json: ${e.message}")
            return
        }

        if (items.isEmpty()) {
            Log.w(TAG, "empty queue ignored")
            return
        }

        Log.i(TAG, "queue: ${items.size} tracks, starting at $startIndex")
        exo.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), positionMs)
        exo.prepare()
        exo.play()
    }

    fun playPause(play: Boolean) { player?.let { if (play) it.play() else it.pause() } }
    fun next() { player?.seekToNextMediaItem() }
    fun previous() { player?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { player?.seekTo(ms) }
    fun stopPlayback() { player?.stop() }

    /** Position/duration/index, polled by the UI while it is visible. */
    fun positionJson(): String {
        val p = player ?: return "{}"
        val dur = if (p.duration == C.TIME_UNSET) 0L else p.duration
        return "{\"pos\":${p.currentPosition},\"dur\":$dur," +
               "\"idx\":${p.currentMediaItemIndex},\"playing\":${p.isPlaying}}"
    }

    private fun notifyWeb(js: String) {
        val act = (application as? NaadaApplication)?.mainActivity ?: return
        act.runOnUiThread {
            runCatching { act.webView.evaluateJavascript(js, null) }
        }
    }

    /**
     * Keep playing when the task is swiped away. MediaSessionService stops
     * by default, which would surprise anyone using this as a music app -
     * the notification is the way back in.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NaadaMediaService"
    }
}
