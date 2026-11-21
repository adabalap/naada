package com.adabala.naada.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** A person signed in on this phone. The token is theirs alone. */
@Serializable
data class Profile(
    val userId: String,
    val name: String,
    val color: String = "#E11D75",
    val token: String,
    val isAdmin: Boolean = false,
)

@Serializable
data class ProfileState(
    val active: String? = null,
    val profiles: List<Profile> = emptyList(),
    val askOnLaunch: Boolean = false,
) {
    val current: Profile? get() = profiles.firstOrNull { it.userId == active }
}

/** Profiles live in a small private file (tokens never leave the device). */
class ProfileStore(context: Context) {
    private val file = File(context.filesDir, "profiles.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private fun load(): ProfileState = runCatching {
        if (file.exists()) NaadaJson.decodeFromString(ProfileState.serializer(), file.readText()) else ProfileState()
    }.getOrElse { ProfileState() }

    private fun save(s: ProfileState) {
        runCatching {
            val tmp = File(file.parentFile, "profiles.json.tmp")
            tmp.writeText(NaadaJson.encodeToString(ProfileState.serializer(), s))
            tmp.renameTo(file)
        }
    }

    private fun edit(f: (ProfileState) -> ProfileState) = _state.update { val n = f(it); save(n); n }

    fun upsert(p: Profile, makeActive: Boolean = true) = edit { s ->
        val list = s.profiles.filter { it.userId != p.userId } + p
        s.copy(profiles = list, active = if (makeActive) p.userId else s.active)
    }

    fun setActive(userId: String?) = edit { it.copy(active = userId) }

    fun remove(userId: String) = edit { s ->
        val list = s.profiles.filter { it.userId != userId }
        s.copy(profiles = list, active = if (s.active == userId) list.firstOrNull()?.userId else s.active)
    }

    fun updateInfo(user: UserInfo) = edit { s ->
        s.copy(profiles = s.profiles.map {
            if (it.userId == user.id) it.copy(name = user.name, color = user.color, isAdmin = user.isAdmin) else it
        })
    }

    fun setAskOnLaunch(on: Boolean) = edit { it.copy(askOnLaunch = on) }
}

@Serializable
data class UserInfo(
    val id: String = "",
    val name: String = "",
    val color: String = "#E11D75",
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("has_pin") val hasPin: Boolean = false,
    @SerialName("last_seen") val lastSeen: Double = 0.0,
    val likes: Int = 0,
    val playlists: Int = 0,
    val devices: Int = 0,
    @SerialName("ai_today") val aiToday: Int = 0,
)

@Serializable data class AuthResponse(val token: String = "", val user: UserInfo = UserInfo())

@Serializable
data class Invite(
    val code: String = "",
    @SerialName("expires_at") val expiresAt: Double = 0.0,
    @SerialName("user_id") val userId: String? = null,
)

@Serializable
data class AdminOverview(
    val users: List<UserInfo> = emptyList(),
    val invites: List<Invite> = emptyList(),
    @SerialName("ai_daily_limit") val aiDailyLimit: Int = 0,
)

@Serializable
data class InfoWithUser(
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("ai_enabled") val aiEnabled: Boolean = false,
    @SerialName("token_required") val tokenRequired: Boolean = false,
    val accounts: Boolean = false,
    val user: UserInfo? = null,
)

val AVATAR_COLORS = listOf("#E11D75", "#7B3FA0", "#0891B2", "#F59E0B", "#16A34A", "#EA580C", "#2563EB", "#DB2777")

/** Turns server error keys into words people understand. */
fun friendlyAuthError(e: Throwable): String = when ((e as? ApiException)?.message) {
    "invalid_code" -> "That code doesn't look right — it's 8 letters/numbers like K7Q2-PXMA."
    "invalid_or_expired_code" -> "That code has expired or was already used. Ask for a new one."
    "pin_must_be_4_to_8_digits" -> "Your PIN must be 4 to 8 digits."
    "name_required" -> "Please enter your name."
    "name_taken" -> "Someone already uses that name. Try adding an initial."
    "wrong_name_or_pin" -> "Name or PIN didn't match."
    "locked" -> "Too many tries — locked for 15 minutes. The owner can send you a sign-in code."
    "wrong_current_pin" -> "Your current PIN is wrong."
    "admin_only" -> "Only the server owner can do that."
    "sign_in_required" -> "On this server, enter the server access token in Settings first."
    null -> "Can't reach your Naada server. Check your connection."
    else -> e.message ?: "Something went wrong."
}
