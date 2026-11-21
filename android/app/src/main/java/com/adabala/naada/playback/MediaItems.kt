package com.adabala.naada.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.adabala.naada.data.NaadaJson
import com.adabala.naada.data.Song

/** Songs play from `naada://song/<id>`; [StreamResolver] swaps in a fresh CDN URL. */
const val NAADA_SCHEME = "naada"
private const val EXTRA_SONG = "naada.song"

fun songUri(id: String): Uri = Uri.parse("$NAADA_SCHEME://song/$id")

fun Song.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString(EXTRA_SONG, NaadaJson.encodeToString(Song.serializer(), forStorage()))
    }
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setDisplayTitle(title)
        .setSubtitle(artist)
        .setArtworkUri(image.takeIf { it.isNotEmpty() }?.let { Uri.parse(it.replace("http://", "https://")) })
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(songUri(id))
        .setCustomCacheKey(id)
        .setMediaMetadata(meta)
        .build()
}

/** Items arriving from a controller lose their URI; restore it from the id. */
fun MediaItem.withPlaybackUri(): MediaItem =
    buildUpon().setUri(songUri(mediaId)).setCustomCacheKey(mediaId).build()

fun MediaItem.toSong(): Song? {
    val json = mediaMetadata.extras?.getString(EXTRA_SONG)
    if (json != null) {
        runCatching { return NaadaJson.decodeFromString(Song.serializer(), json) }
    }
    if (mediaId.isEmpty()) return null
    return Song(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        image = mediaMetadata.artworkUri?.toString().orEmpty(),
    )
}
