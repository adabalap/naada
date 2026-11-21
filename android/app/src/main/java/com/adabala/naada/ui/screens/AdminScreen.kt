package com.adabala.naada.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adabala.naada.data.Invite
import com.adabala.naada.data.UserInfo
import com.adabala.naada.data.friendlyAuthError
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.components.Load
import com.adabala.naada.ui.components.LoadingBox
import com.adabala.naada.ui.components.MessageBox
import com.adabala.naada.ui.components.rememberLoader
import com.adabala.naada.ui.toast
import kotlinx.coroutines.launch

private const val INSTALL_URL = "https://github.com/adabalap/naada/releases/latest"

private fun inviteMessage(code: String, server: String, forName: String?): String =
    if (forName != null) buildString {
        append("Hi $forName — here's a one-time Naada sign-in code: $code\n")
        append("Open Naada → tap your avatar → Add → \"I have an invite\", enter the code, your name and a new PIN.\n")
        append("Valid for 7 days.")
    } else buildString {
        append("🎵 You're invited to Naada — our family music player!\n\n")
        append("1. Install the app: $INSTALL_URL\n")
        append("2. Open Naada → tap the avatar on Home → Add → \"I have an invite\"\n")
        append("3. Invite code: $code\n\n")
        append("Pick your own PIN — your likes and playlists stay yours. Code valid for 7 days.")
        if (!server.contains("naada.adabala.com")) append("\nServer: $server (Settings → Your server)")
    }

private fun shareText(context: Context, text: String, whatsapp: Boolean) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    if (whatsapp) {
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { context.startActivity(Intent(i).setPackage(pkg)); return } catch (_: ActivityNotFoundException) {}
        }
    }
    context.startActivity(Intent.createChooser(i, "Send invite"))
}

/** Owner's view: invite people, see who's using Naada, help with forgotten PINs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.state.collectAsState()
    val l = rememberLoader("admin") { graph.api.adminOverview() }
    var shownCode by remember { mutableStateOf<Pair<String, String?>?>(null) }   // code, forName
    var removeFor by remember { mutableStateOf<UserInfo?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun newCode(user: UserInfo? = null) {
        busy = true
        scope.launch {
            runCatching { graph.api.createInvite(user?.id) }
                .onSuccess { shownCode = it.code to user?.name; l.reload() }
                .onFailure { toast(context, friendlyAuthError(it)) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Family & friends", style = MaterialTheme.typography.headlineSmall)
        }
        PullToRefreshBox(isRefreshing = l.refreshing, onRefresh = l.reload, modifier = Modifier.fillMaxSize()) {
            when (val s = l.state) {
                is Load.Loading -> LoadingBox()
                is Load.Failed -> MessageBox("Couldn't load people", s.message, action = "Retry", onAction = l.reload)
                is Load.Ready -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item {
                        Column(
                            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp)
                        ) {
                            Text("Invite someone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("They get their own library on your server. Codes work once and expire in 7 days.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(12.dp))
                            Button(enabled = !busy, onClick = { newCode() }) {
                                Icon(Icons.Rounded.PersonAdd, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Create invite code")
                            }
                        }
                    }
                    item {
                        Text("People · ${s.value.users.size}", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                    }
                    items(s.value.users) { u -> PersonRow(u, s.value.aiDailyLimit, onCode = { newCode(u) }, onRemove = { removeFor = u }) }
                    if (s.value.invites.isNotEmpty()) {
                        item {
                            Text("Unused codes", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
                        }
                        items(s.value.invites) { inv -> InviteRow(inv, s.value.users, onCancel = {
                            scope.launch { runCatching { graph.api.cancelInvite(inv.code) }; l.reload() }
                        }) }
                    }
                }
            }
        }
    }

    shownCode?.let { (code, forName) ->
        val msg = inviteMessage(code, settings.serverUrl, forName)
        AlertDialog(
            onDismissRequest = { shownCode = null },
            title = { Text(if (forName != null) "Sign-in code for $forName" else "Invite code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(code, fontSize = 34.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(if (forName != null) "Lets $forName set a new PIN. Works once, for 7 days."
                    else "Send it with install instructions — one tap below.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { shareText(context, msg, whatsapp = true) }, modifier = Modifier.fillMaxWidth()) { Text("Send on WhatsApp") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { shareText(context, msg, whatsapp = false) }, modifier = Modifier.fillMaxWidth()) { Text("Share another way") }
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Naada invite", msg))
                        toast(context, "Copied")
                    }) { Text("Copy message") }
                }
            },
            confirmButton = { TextButton(onClick = { shownCode = null }) { Text("Done") } },
        )
    }

    removeFor?.let { u ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text("Remove ${u.name}?") },
            text = { Text("They're signed out everywhere and can't use your server any more. You can invite them again later.") },
            confirmButton = {
                TextButton(onClick = {
                    removeFor = null
                    scope.launch {
                        runCatching { graph.api.removeUser(u.id) }
                            .onSuccess { toast(context, "${u.name} removed"); l.reload() }
                            .onFailure { toast(context, friendlyAuthError(it)) }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PersonRow(u: UserInfo, aiLimit: Int, onCode: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(u.name, u.color, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(u.name + if (u.isAdmin) "  · owner" else "", style = MaterialTheme.typography.titleSmall)
            val seen = if (u.lastSeen > 0) DateUtils.getRelativeTimeSpanString((u.lastSeen * 1000).toLong()).toString() else "never"
            Text(
                "${u.likes} liked · ${u.playlists} playlists · ${u.devices} device${if (u.devices == 1) "" else "s"} · seen $seen" +
                    if (!u.isAdmin) " · AI ${u.aiToday}/$aiLimit today" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!u.isAdmin) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Sign-in code (forgot PIN)") }, onClick = { menu = false; onCode() })
                DropdownMenuItem(text = { Text("Remove from server") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun InviteRow(inv: Invite, users: List<UserInfo>, onCancel: () -> Unit) {
    val forName = inv.userId?.let { id -> users.firstOrNull { it.id == id }?.name }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(inv.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                (if (forName != null) "Sign-in code for $forName · " else "Invite · ") + "expires " +
                    DateUtils.getRelativeTimeSpanString((inv.expiresAt * 1000).toLong()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel code") }
    }
}
