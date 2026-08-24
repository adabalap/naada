package com.adabala.naada

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.*
import java.net.URL

/**
 * NaadaMediaService — the single most important class in the whole app.
 *
 * This is a native Android MediaBrowserService (foreground service). Because
 * it is a NATIVE foreground service with foregroundServiceType=mediaPlayback:
 *
 *  1. Android shows Naada's own app icon in the media notification — not
 *     Chrome's. This is what was impossible from the PWA.
 *  2. The OS classifies the process as "actively playing media" and does not
 *     kill it during battery optimisation, even with the screen off.
 *  3. Headphone buttons, Bluetooth controls, and the lock screen all talk
 *     to this service directly — not through the browser.
 *
 * The service does NOT play audio itself (that's still the <audio> element
 * in the WebView, because JioSaavn streams are CDN URLs we can't intercept
 * easily). Instead, it holds the MediaSession and forwards transport
 * commands to the WebView via the JavaScript bridge on MainActivity.
 */
class NaadaMediaService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var stateBuilder: PlaybackStateCompat.Builder
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    inner class LocalBinder : Binder() {
        val service get() = this@NaadaMediaService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)     // MediaBrowserServiceCompat binding
        } else {
            binder                   // local binding from MainActivity
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        // Android 14 (targetSdk 34) rejects startForeground() for a media
        // service unless the type is supplied, with
        // MissingForegroundServiceTypeException. ServiceCompat handles the
        // older-API branching for us.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(null, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        Log.i(TAG, "NaadaMediaService started — notification badge is Naada's")
    }

    private fun setupMediaSession() {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        mediaSession = MediaSessionCompat(this, "NaadaSession").apply {
            setSessionActivity(pendingIntent)
            isActive = true

            // Transport callbacks forwarded to the WebView
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()     { sendToWebView("togglePlay(); S.wantPlaying=true;") }
                override fun onPause()    { sendToWebView("togglePlay(); S.wantPlaying=false;") }
                override fun onSkipToNext()     { sendToWebView("playNext()") }
                override fun onSkipToPrevious() { sendToWebView("playPrev()") }
                override fun onSeekTo(pos: Long) {
                    sendToWebView("audio.currentTime=${pos / 1000.0}")
                }
            })
        }

        sessionToken = mediaSession.sessionToken

        stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
        mediaSession.setPlaybackState(stateBuilder.build())
    }

    /** Called by the JavaScript bridge when the track changes */
    fun updateMetadata(title: String, artist: String, album: String, artUrl: String) {
        serviceScope.launch {
            val bitmap = loadArtwork(artUrl)
            withContext(Dispatchers.Main) {
                val meta = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .apply { bitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) } }
                    .build()
                mediaSession.setMetadata(meta)
                updateNotification(bitmap, title, artist,
                    mediaSession.controller.playbackState?.state
                        == PlaybackStateCompat.STATE_PLAYING)
            }
        }
    }

    /** Called by the JavaScript bridge when play/pause state changes */
    fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else           PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            stateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f).build())
        val meta = mediaSession.controller.metadata
        val bitmap = meta?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
        updateNotification(bitmap,
            meta?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Naada",
            meta?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "",
            isPlaying)
    }

    private fun updateNotification(art: Bitmap?, title: String, artist: String, playing: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(art, playing, title, artist))
    }

    private fun buildNotification(
        art: Bitmap?, playing: Boolean,
        title: String = "Naada · నాద", artist: String = ""
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)     // ← Naada's icon here
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(pi)
            .setLargeIcon(art ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(R.drawable.ic_prev, "Previous",
                mediaPendingIntent(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                mediaPendingIntent(PlaybackStateCompat.ACTION_PLAY_PAUSE))
            .addAction(R.drawable.ic_next, "Next",
                mediaPendingIntent(PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .build()
    }

    private fun mediaPendingIntent(action: Long): PendingIntent {
        return PendingIntent.getService(this, action.toInt(),
            Intent(this, NaadaMediaService::class.java).apply { putExtra(EXTRA_ACTION, action) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Transport commands arriving from the notification's pending intents.
        val action = intent?.getLongExtra(EXTRA_ACTION, -1L) ?: -1L
        val tc = mediaSession.controller?.transportControls
        if (tc != null) {
            when (action) {
                PlaybackStateCompat.ACTION_PLAY_PAUSE -> {
                    val isPlaying = mediaSession.controller?.playbackState?.state ==
                            PlaybackStateCompat.STATE_PLAYING
                    if (isPlaying) tc.pause() else tc.play()
                }
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT     -> tc.skipToNext()
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS -> tc.skipToPrevious()
                else -> { /* no action extra: just a service start */ }
            }
        }
        return START_STICKY
    }

    private fun sendToWebView(js: String) {
        // MainActivity is singleTask, so there is at most one. It may be null
        // (activity destroyed while playback continues) and its webView is
        // lateinit, so reading it before onCreate finishes would throw
        // UninitializedPropertyAccessException — hence the runCatching.
        val act = (application as? NaadaApplication)?.mainActivity ?: return
        act.runOnUiThread {
            runCatching { act.webView.evaluateJavascript(js, null) }
                .onFailure { Log.w(TAG, "WebView not ready for: $js") }
        }
    }

    private suspend fun loadArtwork(url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            withContext(Dispatchers.IO) {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load artwork: ${e.message}")
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Naada Playback",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Music playback controls"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    // MediaBrowserServiceCompat required overrides — we don't browse local
    // media, so these return empty/null
    override fun onGetRoot(clientPackageName: String, clientUid: Int,
                           rootHints: Bundle?): BrowserRoot {
        return BrowserRoot("naada_root", null)
    }

    override fun onLoadChildren(parentId: String,
                                result: MediaBrowserServiceCompat.Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NaadaMediaService"
        private const val CHANNEL_ID = "naada_playback"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_ACTION = "action"
    }
}
