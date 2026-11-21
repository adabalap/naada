package com.adabala.naada.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val NaadaJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** Same shape as norm() in app.py. `url` is a hint only; playback always resolves fresh. */
@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("album_id") val albumId: String = "",
    val image: String = "",
    val duration: Int = 0,
    val url: String = "",
    val language: String = "",
    val year: String = "",
    @SerialName("has_lyrics") val hasLyrics: Boolean = false,
    @SerialName("lyrics_id") val lyricsId: String = "",
    val label: String = "",
    val explicit: Boolean = false,
) {
    /** Library copies never carry the expiring stream URL. */
    fun forStorage(): Song = copy(url = "")
}

@Serializable
data class Album(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val image: String = "",
    val year: String = "",
    val language: String = "",
    val songs: List<Song> = emptyList(),
)

@Serializable
data class Artist(
    val id: String = "",
    val name: String = "",
    val image: String = "",
    val role: String = "",
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
)

@Serializable
data class PlaylistRef(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    val count: String = "",
    val songs: List<Song> = emptyList(),
)

@Serializable
data class HomeShelves(
    val trending: List<Song> = emptyList(),
    @SerialName("new_albums") val newAlbums: List<Album> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
)

@Serializable data class SongList(val songs: List<Song> = emptyList(), val title: String = "")
@Serializable data class AlbumList(val albums: List<Album> = emptyList())
@Serializable data class PlaylistList(val playlists: List<PlaylistRef> = emptyList())
@Serializable data class ArtistList(val artists: List<Artist> = emptyList())

@Serializable
data class Lyrics(
    val lyrics: String = "",
    val synced: String = "",
    val source: String = "",
    val copyright: String = "",
)

@Serializable data class AiText(val text: String = "", val error: String = "")
@Serializable data class TriviaCard(val question: String = "", val answer: String = "", val song: String = "")
@Serializable data class TriviaResponse(val cards: List<TriviaCard> = emptyList(), val error: String = "")

@Serializable
data class MoodResponse(
    val title: String = "",
    val description: String = "",
    val songs: List<Song> = emptyList(),
    val error: String = "",
)

@Serializable
data class StreamResponse(val id: String = "", val url: String = "")

@Serializable
data class ServerInfo(
    val app: String = "",
    @SerialName("api_version") val apiVersion: Int = 0,
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("ai_enabled") val aiEnabled: Boolean = false,
)

// ── Library ────────────────────────────────────────────────────────────────

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    @SerialName("updated_at") val updatedAt: Double = 0.0,
    val songs: List<Song> = emptyList(),
)

@Serializable
data class HistoryItem(
    val song: Song,
    @SerialName("played_at") val playedAt: Double,
)

/** A song placed in a folder; `from` is the playlist it was moved out of. */
@Serializable
data class FolderSong(val song: Song, val from: String? = null)

/** Groups playlists (by id) and loose songs. Deleting it puts everything back. */
@Serializable
data class Folder(
    val id: String,
    val name: String,
    @SerialName("playlist_ids") val playlistIds: List<String> = emptyList(),
    val songs: List<FolderSong> = emptyList(),
    @SerialName("updated_at") val updatedAt: Double = 0.0,
)

@Serializable
data class LibraryData(
    val rev: Long = 0,
    val likes: List<Song> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val unchanged: Boolean = false,
)

@Serializable
data class SharedPlaylist(
    val id: String = "",
    val name: String = "",
    val songs: List<Song> = emptyList(),
)

@Serializable data class ShareResponse(val id: String = "", val url: String = "")

@Serializable data class RevResponse(val rev: Long = 0, val id: String = "")

/** A change made while offline (or not yet confirmed by the server). */
@Serializable
data class PendingOp(
    val kind: String,                 // like | unlike | pl_upsert | pl_delete | history
    val id: String = "",
    val song: Song? = null,
    val name: String? = null,
    val songs: List<Song>? = null,
    val items: List<HistoryItem>? = null,
)

@Serializable
data class LocalLibrary(
    val data: LibraryData = LibraryData(),
    val pending: List<PendingOp> = emptyList(),
)
