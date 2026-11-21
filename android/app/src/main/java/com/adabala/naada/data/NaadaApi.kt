package com.adabala.naada.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** Thin client for the server's /api/v1 contract. */
class NaadaApi(private val settings: SettingsStore, private val profiles: ProfileStore) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val base = settings.state.value.serverUrl.trimEnd('/')
        val b = ("$base/api/v1/$path").toHttpUrlOrNull()?.newBuilder()
            ?: throw ApiException(0, "Invalid server address: $base")
        query.forEach { (k, v) -> if (v.isNotEmpty()) b.addQueryParameter(k, v) }
        return b.build()
    }

    /** The active profile's personal token, else the server access token (owner). */
    private fun activeToken(): String = profiles.state.value.current?.token ?: settings.state.value.token

    private fun request(url: HttpUrl, method: String = "GET", body: RequestBody? = null, tokenOverride: String? = null): Request {
        val token = tokenOverride ?: activeToken()
        return Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "NaadaAndroid/2")
            .apply { if (token.isNotEmpty()) header("X-Naada-Token", token) }
            .method(method, body)
            .build()
    }

    /** Blocking call; used directly by the playback resolver thread. */
    fun <T> callBlocking(req: Request, ser: KSerializer<T>): T {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // Server errors carry a machine-readable key: {"error": "name_taken"}
                val key = runCatching {
                    NaadaJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                }.getOrNull()
                throw ApiException(r.code, key ?: if (r.code == 401) "sign_in_required" else "Server error ${r.code}")
            }
            return NaadaJson.decodeFromString(ser, text)
        }
    }

    private suspend fun <T> get(path: String, ser: KSerializer<T>, q: Map<String, String> = emptyMap()): T =
        withContext(Dispatchers.IO) { callBlocking(request(url(path, q)), ser) }

    private suspend fun <T> send(path: String, method: String, body: String, ser: KSerializer<T>, token: String? = null): T =
        withContext(Dispatchers.IO) {
            callBlocking(request(url(path), method, body.toRequestBody(jsonType), token), ser)
        }

    private inline fun <reified T> ser(): KSerializer<T> = serializer()

    // ── Catalogue ──────────────────────────────────────────────────────────
    suspend fun info(): ServerInfo = get("info", ser())
    suspend fun home(lang: String): HomeShelves = get("home/$lang", ser())
    suspend fun charts(lang: String): SongList = get("charts/$lang", ser())
    suspend fun trending(lang: String): SongList = get("trending/$lang", ser())
    suspend fun searchSongs(q: String): SongList = get("search", ser(), mapOf("q" to q))
    suspend fun searchAlbums(q: String): AlbumList = get("search/albums", ser(), mapOf("q" to q))
    suspend fun searchArtists(q: String): ArtistList = get("search/artists", ser(), mapOf("q" to q))
    suspend fun album(id: String): Album = get("album/$id", ser())
    suspend fun artist(id: String): Artist = get("artist/$id", ser())
    suspend fun playlist(id: String): PlaylistRef = get("playlist/$id", ser())
    suspend fun radio(songId: String, lang: String): SongList =
        get("radio", ser(), mapOf("song_id" to songId, "lang" to lang))

    suspend fun lyrics(s: Song): Lyrics = get(
        "lyrics/${s.lyricsId.ifEmpty { s.id }}", ser(),
        mapOf("title" to s.title, "artist" to s.artist.substringBefore(","),
            "album" to s.album, "duration" to if (s.duration > 0) s.duration.toString() else "")
    )

    // ── AI ─────────────────────────────────────────────────────────────────
    suspend fun anecdote(s: Song): AiText = get(
        "ai/anecdote", ser(), mapOf("title" to s.title, "artist" to s.artist, "album" to s.album)
    )

    suspend fun trivia(songs: List<Song>): TriviaResponse {
        val body = NaadaJson.encodeToString(TriviaBody.serializer(), TriviaBody(songs.take(8).map { it.forStorage() }))
        return send("ai/trivia", "POST", body, ser())
    }

    suspend fun mood(prompt: String, lang: String): MoodResponse {
        val body = buildJsonObject { put("prompt", prompt); put("lang", lang) }
        return send("ai/mood", "POST", body.toString(), ser())
    }

    // ── Playback ───────────────────────────────────────────────────────────
    fun streamUrlBlocking(songId: String): String =
        callBlocking(request(url("stream/$songId")), StreamResponse.serializer()).url

    // ── Library ────────────────────────────────────────────────────────────
    suspend fun library(sinceRev: Long?): LibraryData =
        get("library", ser(), if (sinceRev != null) mapOf("since_rev" to sinceRev.toString()) else emptyMap())

    suspend fun like(song: Song): RevResponse =
        send("library/likes/${song.id}", "PUT", NaadaJson.encodeToString(Song.serializer(), song.forStorage()), ser())

    suspend fun unlike(id: String): RevResponse = send("library/likes/$id", "DELETE", "{}", ser())

    suspend fun upsertPlaylist(id: String, name: String, songs: List<Song>): RevResponse {
        val body = NaadaJson.encodeToString(
            PlaylistBody.serializer(), PlaylistBody(id, name, songs.map { it.forStorage() })
        )
        return try {
            send("library/playlists/$id", "PUT", body, ser())
        } catch (e: ApiException) {
            if (e.code == 404) send("library/playlists", "POST", body, ser()) else throw e
        }
    }

    suspend fun deletePlaylist(id: String): RevResponse = send("library/playlists/$id", "DELETE", "{}", ser())

    suspend fun addHistory(items: List<HistoryItem>): RevResponse {
        val body = NaadaJson.encodeToString(HistoryBody.serializer(), HistoryBody(items))
        return send("library/history", "POST", body, ser())
    }

    suspend fun clearHistory(): RevResponse = send("library/history", "DELETE", "{}", ser())

    /** Merge a backup file into the server library (nothing is deleted). */
    suspend fun importLibrary(data: LibraryData): RevResponse {
        val body = NaadaJson.encodeToString(LibraryData.serializer(), data)
        return send("library/import", "POST", body, ser())
    }

    // ── Sharing ────────────────────────────────────────────────────────────
    suspend fun share(name: String, songs: List<Song>): ShareResponse {
        val body = NaadaJson.encodeToString(ShareBody.serializer(), ShareBody(name, songs.map { it.forStorage() }))
        return send("share", "POST", body, ser())
    }

    suspend fun sharedPlaylist(id: String): SharedPlaylist = get("share/$id", ser())

    // ── Accounts ───────────────────────────────────────────────────────────
    private val device get() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    suspend fun serverInfo(): InfoWithUser = get("info", ser())

    suspend fun join(code: String, name: String, pin: String): AuthResponse {
        val body = buildJsonObject { put("code", code); put("name", name); put("pin", pin); put("device", device) }
        return send("auth/join", "POST", body.toString(), ser(), token = "")
    }

    suspend fun login(name: String, pin: String): AuthResponse {
        val body = buildJsonObject { put("name", name); put("pin", pin); put("device", device) }
        return send("auth/login", "POST", body.toString(), ser(), token = "")
    }

    suspend fun logout(token: String) {
        runCatching { send("auth/logout", "POST", "{}", JsonObject.serializer(), token = token) }
    }

    suspend fun me(): UserInfo = get("me", ser())

    /** Uses the server access token — how the owner claims their account on a fresh phone. */
    suspend fun updateMe(name: String? = null, pin: String? = null, currentPin: String? = null, color: String? = null, asOwner: Boolean = false): UserInfo {
        val body = buildJsonObject {
            name?.let { put("name", it) }; pin?.let { put("pin", it) }
            currentPin?.let { put("current_pin", it) }; color?.let { put("color", it) }
        }
        return send("me", "PUT", body.toString(), ser(), token = if (asOwner) settings.state.value.token else null)
    }

    suspend fun createInvite(userId: String? = null): Invite {
        val body = buildJsonObject { userId?.let { put("user_id", it) } }
        return send("admin/invites", "POST", body.toString(), ser())
    }

    suspend fun adminOverview(): AdminOverview = get("admin/users", ser())
    suspend fun removeUser(id: String) { send("admin/users/$id", "DELETE", "{}", JsonObject.serializer()) }
    suspend fun cancelInvite(code: String) { send("admin/invites/$code", "DELETE", "{}", JsonObject.serializer()) }

    @Serializable private data class ShareBody(val name: String, val songs: List<Song>)
    @Serializable private data class TriviaBody(val songs: List<Song>)
    @Serializable private data class PlaylistBody(val id: String, val name: String, val songs: List<Song>)
    @Serializable private data class HistoryBody(val items: List<HistoryItem>)
}
