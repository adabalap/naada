package com.adabala.naada.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adabala.naada.data.AVATAR_COLORS
import com.adabala.naada.data.Profile
import com.adabala.naada.data.friendlyAuthError
import com.adabala.naada.ui.LocalGraph
import com.adabala.naada.ui.LocalNav
import com.adabala.naada.ui.toast
import kotlinx.coroutines.launch

fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFFE11D75))

@Composable
fun Avatar(name: String, color: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(parseColor(color)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** "Who's listening?" — pick, add or remove profiles on this phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by graph.profiles.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var removeFor by remember { mutableStateOf<Profile?>(null) }
    var claiming by remember { mutableStateOf(false) }
    val hasAdmin = state.profiles.any { it.isAdmin }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            if (state.profiles.isNotEmpty()) TextButton(onClick = { editing = !editing }) {
                Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, null)
                Spacer(Modifier.width(6.dp))
                Text(if (editing) "Done" else "Manage")
            }
        }
        Text("Who's listening?", style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
        Text(
            "Everyone gets their own likes, playlists and history.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(28.dp))

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            state.profiles.forEach { p ->
                val active = p.userId == state.active
                Column(
                    Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).clickable {
                        if (editing) removeFor = p
                        else {
                            graph.profiles.setActive(p.userId)
                            toast(context, "Hi ${p.name} 👋")
                            nav.back()
                        }
                    }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box {
                        Avatar(p.name, p.color, 84.dp,
                            if (active) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        if (editing) Box(
                            Modifier.align(Alignment.TopEnd).size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Close, "Remove", tint = Color.White, modifier = Modifier.size(18.dp)) }
                        else if (p.isAdmin) Box(
                            Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Shield, "Owner", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
            }
            Column(
                Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).clickable { nav.addProfile() }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Add, null, Modifier.size(36.dp)) }
                Spacer(Modifier.height(8.dp))
                Text("Add", style = MaterialTheme.typography.titleSmall)
            }
        }

        Spacer(Modifier.height(32.dp))
        if (state.profiles.size >= 2) Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ask who's listening when Naada opens", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = state.askOnLaunch, onCheckedChange = { graph.profiles.setAskOnLaunch(it) })
        }
        if (!hasAdmin) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { claiming = true }, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
                Icon(Icons.Rounded.Shield, null)
                Spacer(Modifier.width(8.dp))
                Text("I run this server — set up the owner profile")
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    removeFor?.let { p ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text("Remove ${p.name} from this phone?") },
            text = { Text("Their library stays safe on the server — they can sign in again any time with their name and PIN.") },
            confirmButton = {
                TextButton(onClick = {
                    removeFor = null
                    scope.launch { graph.api.logout(p.token) }
                    graph.profiles.remove(p.userId)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text("Cancel") } },
        )
    }
    if (claiming) OwnerSetupDialog(onDone = { claiming = false })
}

/** The owner's library already exists on the server; give it a name + PIN and a profile. */
@Composable
private fun OwnerSetupDialog(onDone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(graph.settings.state.value.token) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Owner profile") },
        text = {
            Column {
                Text("Your existing library becomes yours. Proves you're the owner with the server's NAADA_TOKEN (leave blank if your server has none).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(token, { token = it.trim() }, label = { Text("Server access token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                PinField(pin, { pin = it }, "Choose a PIN (4–8 digits)")
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && pin.length >= 4, onClick = {
                busy = true; error = null
                scope.launch {
                    graph.settings.setServer(graph.settings.state.value.serverUrl, token)
                    runCatching {
                        val me = graph.api.updateMe(name = name.trim(), asOwner = true)
                        if (!me.isAdmin) throw IllegalStateException("not_owner")
                        // First PIN needs no current PIN; if one exists, signing in proves it.
                        if (!me.hasPin) graph.api.updateMe(pin = pin, asOwner = true)
                        graph.api.login(me.name, pin)
                    }.onSuccess { auth ->
                        graph.profiles.upsert(Profile(auth.user.id, auth.user.name, auth.user.color, auth.token, auth.user.isAdmin))
                        toast(context, "Welcome, ${auth.user.name}")
                        onDone()
                    }.onFailure { e ->
                        error = when {
                            e is IllegalStateException -> "This phone isn't connected as the owner."
                            (e as? com.adabala.naada.data.ApiException)?.code == 401 ->
                                if (token.isBlank()) "Your server has a NAADA_TOKEN — paste it above. On the server: sudo systemctl cat naada"
                                else "That token doesn't match the server's NAADA_TOKEN."
                            else -> friendlyAuthError(e)
                        }
                    }
                    busy = false
                }
            }) { Text(if (busy) "Setting up…" else "Set up") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() }.take(8)) },
        label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

/** Join with an invite code, or sign in to an existing account. */
@Composable
fun AddProfileScreen() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val joining = mode == 0
    val ready = name.isNotBlank() && pin.length >= 4 && (!joining || (code.isNotBlank() && pin == pin2))

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Add a profile", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("I have an invite", "I have an account").forEachIndexed { i, label ->
                    SegmentedButton(selected = mode == i, onClick = { mode = i; error = null },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (joining) "Ask the person who runs your Naada server for an invite code. You'll pick a PIN to sign in on other phones later."
                else "Sign in with the name and PIN you chose when you joined.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            if (joining) {
                OutlinedTextField(code, { code = it.uppercase().take(9) }, label = { Text("Invite code") },
                    placeholder = { Text("K7Q2-PXMA") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PinField(pin, { pin = it }, if (joining) "Choose a PIN (4–8 digits)" else "PIN")
            if (joining) {
                Spacer(Modifier.height(8.dp))
                PinField(pin2, { pin2 = it }, "Repeat PIN")
                if (pin2.isNotEmpty() && pin != pin2) Text("PINs don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(20.dp))
            Button(enabled = ready && !busy, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching {
                        if (joining) graph.api.join(code.trim(), name.trim(), pin) else graph.api.login(name.trim(), pin)
                    }.onSuccess { a ->
                        graph.profiles.upsert(Profile(a.user.id, a.user.name, a.user.color, a.token, a.user.isAdmin))
                        toast(context, if (joining) "Welcome to Naada, ${a.user.name}!" else "Hi ${a.user.name} 👋")
                        nav.home()
                    }.onFailure { error = friendlyAuthError(it) }
                    busy = false
                }
            }) { Text(if (busy) "Please wait…" else if (joining) "Join" else "Sign in") }
        }
    }
}

/** Account controls for the active profile, shown in Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountSection() {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by graph.profiles.state.collectAsState()
    val p = state.current
    var changePin by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }

    if (p == null) {
        Text("No profile on this phone yet — you're using the owner's library through the server access token.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(onClick = nav::profiles) { Text("Set up profiles") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.name, p.color, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text(if (p.isAdmin) "Owner · admin" else "Member", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = nav::profiles) { Text("Switch") }
    }
    Spacer(Modifier.height(12.dp))
    Text("Colour", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AVATAR_COLORS.forEach { c ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(parseColor(c))
                    .then(if (c.equals(p.color, true)) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable {
                        scope.launch {
                            runCatching { graph.api.updateMe(color = c) }
                                .onSuccess { graph.profiles.updateInfo(it) }
                                .onFailure { toast(context, friendlyAuthError(it)) }
                        }
                    }
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { rename = true }) { Text("Rename") }
        OutlinedButton(onClick = { changePin = true }) { Text("Change PIN") }
    }
    if (p.isAdmin) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = nav::admin) {
            Icon(Icons.Rounded.Person, null)
            Spacer(Modifier.width(8.dp))
            Text("Family & friends")
        }
    }

    if (rename) NameDialog("Your name", p.name, onDismiss = { rename = false }) { n ->
        rename = false
        scope.launch {
            runCatching { graph.api.updateMe(name = n) }
                .onSuccess { graph.profiles.updateInfo(it); toast(context, "Saved") }
                .onFailure { toast(context, friendlyAuthError(it)) }
        }
    }
    if (changePin) ChangePinDialog(onDone = { changePin = false })
}

@Composable
private fun ChangePinDialog(onDone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Change PIN") },
        text = {
            Column {
                PinField(cur, { cur = it }, "Current PIN")
                Spacer(Modifier.height(8.dp))
                PinField(new1, { new1 = it }, "New PIN")
                Spacer(Modifier.height(8.dp))
                PinField(new2, { new2 = it }, "Repeat new PIN")
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = cur.length >= 4 && new1.length >= 4 && new1 == new2, onClick = {
                scope.launch {
                    runCatching { graph.api.updateMe(pin = new1, currentPin = cur) }
                        .onSuccess { toast(context, "PIN changed"); onDone() }
                        .onFailure { error = friendlyAuthError(it) }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
