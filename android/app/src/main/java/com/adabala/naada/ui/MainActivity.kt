package com.adabala.naada.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import com.adabala.naada.AppGraph
import com.adabala.naada.NaadaApp
import com.adabala.naada.ui.components.rememberArtColors
import com.adabala.naada.ui.theme.NaadaTheme

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }

fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

/** Every place the app can go. Ids are URL-encoded so Saavn's odd characters survive. */
class Nav(private val nc: NavHostController) {
    private fun go(route: String) = nc.navigate(route) { launchSingleTop = true }
    fun album(id: String) = go("album/${Uri.encode(id)}")
    fun artist(id: String) = go("artist/${Uri.encode(id)}")
    fun playlist(id: String) = go("playlist/${Uri.encode(id)}")
    fun userPlaylist(id: String) = go("userpl/${Uri.encode(id)}")
    fun charts(lang: String) = go("charts/$lang")
    fun mood(prompt: String) = go("mood/${Uri.encode(prompt)}")
    fun liked() = go("liked")
    fun history() = go("history")
    fun downloads() = go("downloads")
    fun settings() = go("settings")
    fun trivia() = go("trivia")
    fun back() { nc.popBackStack() }
}

class MainActivity : ComponentActivity() {

    private val openPlayer = mutableStateOf(false)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as NaadaApp).graph
        graph.player.connect()
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            CompositionLocalProvider(LocalGraph provides graph) {
                ThemedRoot(graph)
            }
        }
    }

    @Composable
    private fun ThemedRoot(graph: AppGraph) {
        val ui by graph.player.ui.collectAsState()
        val art = rememberArtColors(ui.song?.image)
        NaadaTheme(accent = art?.vibrant) {
            NaadaRoot(openPlayer = openPlayer.value, onPlayerOpened = { openPlayer.value = false })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayer.value = true
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "naada.open_player"
    }
}
