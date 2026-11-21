package com.adabala.naada.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

enum class SyncState { Idle, Syncing, Offline, Error }

/**
 * Liked songs, playlists and history.
 *
 * Offline-first: every change applies to the local copy immediately and is
 * queued as a [PendingOp]. [sync] flushes the queue to the server, then pulls
 * the server's copy. The server is the source of truth once it answers.
 */
class LibraryRepository(
    context: Context,
    private val api: NaadaApi,
    private val scope: CoroutineScope,
    initialProfile: String?,
) {
    private val dir = context.filesDir
    private var profileKey: String? = initialProfile
    private var file = fileFor(initialProfile)

    /** No profile = the owner/legacy library this phone had before profiles. */
    private fun fileFor(profile: String?) = File(dir, if (profile == null) "library.json" else "library-$profile.json")
    private val mutex = Mutex()
    private var syncJob: Job? = null

    private val _local = MutableStateFlow(load())
    val library: StateFlow<LibraryData> get() = _data
    private val _data = MutableStateFlow(_local.value.data)

    private val _sync = MutableStateFlow(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _sync.asStateFlow()

    private fun load(): LocalLibrary = runCatching {
        if (file.exists()) NaadaJson.decodeFromString(LocalLibrary.serializer(), file.readText()) else LocalLibrary()
    }.getOrElse { LocalLibrary() }

    private fun persist(l: LocalLibrary) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(NaadaJson.encodeToString(LocalLibrary.serializer(), l))
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "persist failed", it) }
    }

    private fun mutate(op: PendingOp?, change: (LibraryData) -> LibraryData) {
        _local.update { cur ->
            val next = LocalLibrary(change(cur.data), if (op != null) cur.pending + op else cur.pending)
            persist(next)
            next
        }
        _data.value = _local.value.data
        scheduleSync(800)
    }

    /** Show another person's library. Their cached copy appears instantly, then syncs. */
    fun switchTo(profile: String?) {
        if (profile == profileKey) return
        syncJob?.cancel()
        profileKey = profile
        file = fileFor(profile)
        _local.value = load()
        _data.value = _local.value.data
        _sync.value = SyncState.Idle
        scheduleSync()
    }

    // ── Queries ────────────────────────────────────────────────────────────
    fun isLiked(id: String): Boolean = library.value.likes.any { it.id == id }
    fun playlist(id: String): UserPlaylist? = library.value.playlists.firstOrNull { it.id == id }

    // ── Likes ──────────────────────────────────────────────────────────────
    fun toggleLike(song: Song): Boolean {
        val liked = !isLiked(song.id)
        if (liked) {
            mutate(PendingOp("like", song.id, song = song.forStorage())) { d ->
                d.copy(likes = listOf(song.forStorage()) + d.likes.filter { it.id != song.id })
            }
        } else {
            mutate(PendingOp("unlike", song.id)) { d -> d.copy(likes = d.likes.filter { it.id != song.id }) }
        }
        return liked
    }

    // ── Playlists ──────────────────────────────────────────────────────────
    fun createPlaylist(name: String, songs: List<Song> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        val pl = UserPlaylist(id, name.trim(), now(), songs.map { it.forStorage() })
        mutate(PendingOp("pl_upsert", id, name = pl.name, songs = pl.songs)) { d ->
            d.copy(playlists = listOf(pl) + d.playlists)
        }
        return id
    }

    private fun updatePlaylist(id: String, f: (UserPlaylist) -> UserPlaylist) {
        val cur = playlist(id) ?: return
        val next = f(cur).copy(updatedAt = now())
        mutate(PendingOp("pl_upsert", id, name = next.name, songs = next.songs)) { d ->
            d.copy(playlists = listOf(next) + d.playlists.filter { it.id != id })
        }
    }

    fun renamePlaylist(id: String, name: String) = updatePlaylist(id) { it.copy(name = name.trim()) }

    fun addToPlaylist(id: String, songs: List<Song>) = updatePlaylist(id) { p ->
        val have = p.songs.map { it.id }.toSet()
        p.copy(songs = p.songs + songs.filter { it.id !in have }.map { it.forStorage() })
    }

    fun removeFromPlaylist(id: String, songId: String) =
        updatePlaylist(id) { p -> p.copy(songs = p.songs.filter { it.id != songId }) }

    fun deletePlaylist(id: String) {
        mutate(PendingOp("pl_delete", id)) { d ->
            d.copy(playlists = d.playlists.filter { it.id != id })
        }
        if (library.value.folders.any { id in it.playlistIds }) editFolders { fs ->
            fs.map { f -> f.copy(playlistIds = f.playlistIds - id) }
        }
    }

    // ── Folders ────────────────────────────────────────────────────────────
    fun folder(id: String): Folder? = library.value.folders.firstOrNull { it.id == id }
    fun folderOf(playlistId: String): Folder? = library.value.folders.firstOrNull { playlistId in it.playlistIds }

    private fun editFolders(f: (List<Folder>) -> List<Folder>) =
        mutate(PendingOp("folders")) { d -> d.copy(folders = f(d.folders)) }

    private fun touch(f: Folder) = f.copy(updatedAt = now())

    fun createFolder(name: String, playlistIds: List<String> = emptyList(), songs: List<FolderSong> = emptyList()): String {
        val id = "f-" + UUID.randomUUID().toString().take(12)
        editFolders { fs ->
            val moved = fs.map { it.copy(playlistIds = it.playlistIds - playlistIds.toSet()) }
            listOf(Folder(id, name.trim(), playlistIds, songs, now())) + moved
        }
        return id
    }

    fun renameFolder(id: String, name: String) = editFolders { fs -> fs.map { if (it.id == id) touch(it.copy(name = name.trim())) else it } }

    /** A playlist lives in at most one folder. */
    fun movePlaylistToFolder(playlistId: String, folderId: String) = editFolders { fs ->
        fs.map { f ->
            when (f.id) {
                folderId -> touch(f.copy(playlistIds = (f.playlistIds - playlistId) + playlistId))
                else -> if (playlistId in f.playlistIds) touch(f.copy(playlistIds = f.playlistIds - playlistId)) else f
            }
        }
    }

    fun removePlaylistFromFolder(playlistId: String) = editFolders { fs ->
        fs.map { if (playlistId in it.playlistIds) touch(it.copy(playlistIds = it.playlistIds - playlistId)) else it }
    }

    /** Move a song into a folder. If it came from one of your playlists, it leaves that playlist. */
    fun addSongToFolder(folderId: String, song: Song, fromPlaylist: String?) {
        val from = fromPlaylist?.takeIf { playlist(it) != null }
        if (from != null) removeFromPlaylist(from, song.id)
        editFolders { fs ->
            fs.map { f ->
                if (f.id != folderId || f.songs.any { it.song.id == song.id }) f
                else touch(f.copy(songs = f.songs + FolderSong(song.forStorage(), from)))
            }
        }
    }

    /** Take a song out of a folder and put it back where it came from (if anywhere). */
    fun removeSongFromFolder(folderId: String, songId: String) {
        val f = folder(folderId) ?: return
        val fs = f.songs.firstOrNull { it.song.id == songId } ?: return
        fs.from?.let { if (playlist(it) != null) addToPlaylist(it, listOf(fs.song)) }
        editFolders { list -> list.map { if (it.id == folderId) touch(it.copy(songs = it.songs.filter { s -> s.song.id != songId })) else it } }
    }

    /**
     * Delete only the folder: its playlists return to the main list, and each
     * moved song returns to the playlist it came from. Songs with nowhere to go
     * are kept in a new playlist with the folder's name — nothing is lost.
     */
    fun deleteFolder(folderId: String): Int {
        val f = folder(folderId) ?: return 0
        val orphans = mutableListOf<Song>()
        f.songs.groupBy { it.from }.forEach { (from, items) ->
            if (from != null && playlist(from) != null) addToPlaylist(from, items.map { it.song })
            else orphans += items.map { it.song }
        }
        if (orphans.isNotEmpty()) createPlaylist(f.name, orphans)
        editFolders { list -> list.filter { it.id != folderId } }
        return orphans.size
    }

    /** Turn a playlist into a folder (its songs become the folder's songs). */
    fun convertPlaylistToFolder(playlistId: String): String? {
        val p = playlist(playlistId) ?: return null
        val id = createFolder(p.name, songs = p.songs.map { FolderSong(it, null) })
        mutate(PendingOp("pl_delete", playlistId)) { d -> d.copy(playlists = d.playlists.filter { it.id != playlistId }) }
        return id
    }

    /** Every song a folder can play: its playlists' songs, then its own. */
    fun folderSongs(f: Folder): List<Song> {
        val pls = library.value.playlists
        return (f.playlistIds.mapNotNull { id -> pls.firstOrNull { it.id == id } }.flatMap { it.songs } + f.songs.map { it.song })
            .distinctBy { it.id }
    }

    /** Cover art for a folder: one image per album inside, mixed. */
    fun folderImages(f: Folder): List<String> {
        val pls = library.value.playlists
        val fromPlaylists = f.playlistIds.mapNotNull { id -> pls.firstOrNull { it.id == id }?.songs?.firstOrNull()?.image }
        return (fromPlaylists + f.songs.map { it.song.image }).filter { it.isNotBlank() }.distinct()
    }

    // ── History ────────────────────────────────────────────────────────────
    fun recordPlay(song: Song) {
        val item = HistoryItem(song.forStorage(), now())
        mutate(PendingOp("history", items = listOf(item))) { d ->
            d.copy(history = (listOf(item) + d.history).take(300))
        }
    }

    fun clearHistory() {
        mutate(PendingOp("history_clear")) { d -> d.copy(history = emptyList()) }
    }

    /** Export: everything in the library, as written to a backup file. */
    fun backupJson(): String = NaadaJson.encodeToString(LibraryData.serializer(), library.value.copy(unchanged = false))

    /** Restore a backup file: merge into the server, then pull the result. */
    suspend fun restoreBackup(json: String): Result<String> = runCatching {
        val data = NaadaJson.decodeFromString(LibraryData.serializer(), json)
        api.importLibrary(data)
        sync()
        "Restored ${data.likes.size} liked songs and ${data.playlists.size} playlists"
    }

    fun recentSongs(limit: Int = 20): List<Song> =
        library.value.history.map { it.song }.distinctBy { it.id }.take(limit)

    // ── Sync ───────────────────────────────────────────────────────────────
    fun scheduleSync(delayMs: Long = 0) {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            sync()
        }
    }

    suspend fun sync(): Boolean = mutex.withLock {
        _sync.value = SyncState.Syncing
        try {
            flushPending()
            val since = _local.value.data.rev.takeIf { it > 0 && _local.value.pending.isEmpty() }
            val remote = api.library(since)
            if (!remote.unchanged) {
                _local.update { cur ->
                    // Changes made during the download stay queued in `pending`
                    // and are pushed (then re-pulled) by the sync they scheduled.
                    val next = cur.copy(data = remote.copy(unchanged = false))
                    persist(next)
                    next
                }
                _data.value = _local.value.data
            }
            _sync.value = SyncState.Idle
            true
        } catch (e: ApiException) {
            Log.w(TAG, "sync api error", e)
            _sync.value = SyncState.Error
            false
        } catch (e: Exception) {
            Log.w(TAG, "sync offline", e)
            _sync.value = SyncState.Offline
            false
        }
    }

    private suspend fun flushPending() {
        while (true) {
            val op = _local.value.pending.firstOrNull() ?: return
            when (op.kind) {
                "like" -> op.song?.let { api.like(it) }
                "unlike" -> api.unlike(op.id)
                "pl_upsert" -> api.upsertPlaylist(op.id, op.name ?: "Playlist", op.songs ?: emptyList())
                "pl_delete" -> api.deletePlaylist(op.id)
                "history_clear" -> api.clearHistory()
                "folders" -> {
                    // Folders are sent whole; one upload covers every queued change.
                    api.putFolders(_local.value.data.folders)
                    _local.update { cur ->
                        val next = cur.copy(pending = cur.pending.filter { it.kind != "folders" }); persist(next); next
                    }
                    continue
                }
                "history" -> {
                    // Batch consecutive history ops into one upload.
                    val batch = _local.value.pending.takeWhile { it.kind == "history" }
                    api.addHistory(batch.flatMap { it.items ?: emptyList() })
                    _local.update { cur ->
                        val next = cur.copy(pending = cur.pending.drop(batch.size)); persist(next); next
                    }
                    continue
                }
            }
            _local.update { cur -> val next = cur.copy(pending = cur.pending.drop(1)); persist(next); next }
        }
    }

    private fun now() = System.currentTimeMillis() / 1000.0

    companion object { private const val TAG = "NaadaLibrary" }
}
