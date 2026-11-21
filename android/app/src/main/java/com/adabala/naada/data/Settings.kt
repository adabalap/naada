package com.adabala.naada.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adabala.naada.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val Context.dataStore by preferencesDataStore(name = "naada_settings")

data class AppSettings(
    val serverUrl: String = BuildConfig.DEFAULT_SERVER,
    val token: String = "",
    val homeLang: String = "telugu",
    val recentSearches: List<String> = emptyList(),
)

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val server = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val lang = stringPreferencesKey("home_lang")
        val recents = stringPreferencesKey("recent_searches")
    }

    private val listSer = ListSerializer(String.serializer())

    private val flow = context.dataStore.data.map { p ->
        AppSettings(
            serverUrl = p[K.server]?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER,
            token = p[K.token] ?: "",
            homeLang = p[K.lang] ?: "telugu",
            recentSearches = p[K.recents]?.let {
                runCatching { NaadaJson.decodeFromString(listSer, it) }.getOrNull()
            } ?: emptyList(),
        )
    }

    /** Read synchronously once at startup so the first network call has a URL. */
    val state: StateFlow<AppSettings> = flow.stateIn(
        scope, SharingStarted.Eagerly, runBlocking { flow.first() }
    )

    suspend fun setServer(url: String, token: String) = context.dataStore.edit {
        it[K.server] = url.trim().trimEnd('/')
        it[K.token] = token.trim()
    }

    suspend fun setHomeLang(lang: String) = context.dataStore.edit { it[K.lang] = lang }

    suspend fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val list = (listOf(t) + state.value.recentSearches.filter { !it.equals(t, true) }).take(10)
        context.dataStore.edit { it[K.recents] = NaadaJson.encodeToString(listSer, list) }
    }

    suspend fun clearRecentSearches() = context.dataStore.edit { it.remove(K.recents) }
}
