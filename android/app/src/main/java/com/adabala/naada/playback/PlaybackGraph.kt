@file:OptIn(UnstableApi::class)

package com.adabala.naada.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.adabala.naada.data.NaadaApi
import com.adabala.naada.data.NaadaJson
import com.adabala.naada.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Turns `naada://song/<id>` into a playable https URL by asking the server at
 * play time. URLs are remembered for a while so seeking doesn't re-resolve.
 */
class StreamResolver(private val api: NaadaApi) : ResolvingDataSource.Resolver {
    private data class Entry(val url: String, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun prime(song: Song) {
        if (song.url.startsWith("https://")) cache.putIfAbsent(song.id, Entry(song.url, System.currentTimeMillis()))
    }

    fun invalidate(id: String) { cache.remove(id) }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != NAADA_SCHEME) return dataSpec
        val id = uri.lastPathSegment ?: throw IOException("Bad song uri $uri")
        val hit = cache[id]
        val url = if (hit != null && System.currentTimeMillis() - hit.at < TTL_MS) {
            hit.url
        } else {
            val fresh = api.streamUrlBlocking(id).replace("http://", "https://")
            if (fresh.isEmpty()) throw IOException("No stream for $id")
            cache[id] = Entry(fresh, System.currentTimeMillis())
            fresh
        }
        return dataSpec.withUri(Uri.parse(url))
    }

    companion object { private const val TTL_MS = 30 * 60 * 1000L }
}

/**
 * Two caches, both keyed by song id:
 *  - downloads: kept until the user removes them (offline library)
 *  - streaming: least-recently-used, so replays and seeks don't hit the network
 * Playback reads downloads first, then the streaming cache, then the network.
 */
class PlaybackGraph(context: Context, api: NaadaApi) {
    private val app = context.applicationContext
    private val db = StandaloneDatabaseProvider(app)

    val resolver = StreamResolver(api)

    val downloadCache = SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), db)
    val streamCache = SimpleCache(File(app.cacheDir, "stream"), LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES), db)

    private val httpFactory: DataSource.Factory = OkHttpDataSource.Factory(api.http)
        .setUserAgent("NaadaAndroid/2")

    private val resolvingFactory: DataSource.Factory = ResolvingDataSource.Factory(httpFactory, resolver)

    /** What ExoPlayer reads from. */
    val playbackFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setCacheWriteDataSinkFactory(null)          // never write streams into downloads
        .setUpstreamDataSourceFactory(
            CacheDataSource.Factory()
                .setCache(streamCache)
                .setUpstreamDataSourceFactory(resolvingFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val downloadManager = DownloadManager(
        app, db, downloadCache, resolvingFactory, Executors.newFixedThreadPool(3)
    ).apply { maxParallelDownloads = 3 }

    private val _downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadInfo>> = _downloads.asStateFlow()

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = refreshDownloads()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                finalException?.let { Log.w("NaadaDownloads", "download ${download.request.id} failed", it) }
                refreshDownloads()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refreshDownloads()
        })
        refreshDownloads()
    }

    fun refreshDownloads() {
        val out = LinkedHashMap<String, DownloadInfo>()
        runCatching {
            downloadManager.downloadIndex.getDownloads().use { c ->
                while (c.moveToNext()) {
                    val d = c.download
                    val song = runCatching {
                        NaadaJson.decodeFromString(Song.serializer(), String(d.request.data, Charsets.UTF_8))
                    }.getOrNull() ?: continue
                    out[d.request.id] = DownloadInfo(song, d.state, d.percentDownloaded, d.updateTimeMs)
                }
            }
        }.onFailure { Log.w("NaadaDownloads", "index read failed", it) }
        _downloads.value = out
    }

    fun requestFor(song: Song): DownloadRequest =
        DownloadRequest.Builder(song.id, songUri(song.id))
            .setCustomCacheKey(song.id)
            .setData(NaadaJson.encodeToString(Song.serializer(), song.forStorage()).toByteArray(Charsets.UTF_8))
            .build()

    fun streamCacheBytes(): Long = streamCache.cacheSpace
    fun downloadBytes(): Long = downloadCache.cacheSpace

    fun clearStreamCache() {
        streamCache.keys.toList().forEach { streamCache.removeResource(it) }
    }

    companion object { const val STREAM_CACHE_BYTES = 600L * 1024 * 1024 }
}

data class DownloadInfo(val song: Song, val state: Int, val percent: Float, val updatedAt: Long) {
    val done get() = state == Download.STATE_COMPLETED
    val active get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val failed get() = state == Download.STATE_FAILED
}
