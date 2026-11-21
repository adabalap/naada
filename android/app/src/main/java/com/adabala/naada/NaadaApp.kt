package com.adabala.naada

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.adabala.naada.data.LibraryRepository
import com.adabala.naada.data.NaadaApi
import com.adabala.naada.data.ProfileStore
import com.adabala.naada.data.SettingsStore
import com.adabala.naada.playback.NaadaDownloadService
import com.adabala.naada.playback.PlaybackGraph
import com.adabala.naada.playback.PlayerController
import com.adabala.naada.playback.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Everything long-lived, created once per process. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app, scope)
    val profiles = ProfileStore(app)
    val api = NaadaApi(settings, profiles)
    val library = LibraryRepository(app, api, scope, profiles.state.value.active)
    val playback = PlaybackGraph(app, api)
    val queueStore = QueueStore(app)
    val player = PlayerController(app, scope, playback)
}

class NaadaApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        createChannels()
        // Each profile has its own library; switch when the active profile changes.
        graph.scope.launch {
            graph.profiles.state.map { it.active }.distinctUntilChanged().collect { graph.library.switchTo(it) }
        }
        // Sync the library whenever the app comes to the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.library.scheduleSync()
        })
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    NaadaDownloadService.CHANNEL_ID, getString(R.string.downloads_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("art")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
