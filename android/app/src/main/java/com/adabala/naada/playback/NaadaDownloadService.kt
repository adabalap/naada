@file:OptIn(UnstableApi::class)

package com.adabala.naada.playback

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.adabala.naada.NaadaApp
import com.adabala.naada.R

/** Foreground service that keeps offline downloads going in the background. */
class NaadaDownloadService : DownloadService(
    FOREGROUND_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel,
    0
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as NaadaApp).graph.playback.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(
            this, R.drawable.ic_stat_naada, null,
            if (downloads.size == 1) "Downloading 1 song" else "Downloading ${downloads.size} songs",
            downloads, notMetRequirements
        )

    companion object {
        const val CHANNEL_ID = "naada_downloads"
        private const val FOREGROUND_ID = 7001
    }
}
