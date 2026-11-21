package com.adabala.naada.playback

import android.content.Context
import com.adabala.naada.data.NaadaJson
import com.adabala.naada.data.Song
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SavedQueue(val songs: List<Song>, val index: Int, val positionMs: Long)

/** The last queue and position, so Naada resumes exactly where you left off. */
class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue.json")

    fun save(songs: List<Song>, index: Int, positionMs: Long) {
        runCatching {
            val tmp = File(file.parentFile, "queue.json.tmp")
            tmp.writeText(NaadaJson.encodeToString(SavedQueue.serializer(), SavedQueue(songs, index, positionMs.coerceAtLeast(0))))
            tmp.renameTo(file)
        }
    }

    fun load(): SavedQueue? = runCatching {
        if (!file.exists()) null else NaadaJson.decodeFromString(SavedQueue.serializer(), file.readText())
    }.getOrNull()
}
