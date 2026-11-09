package dev.martin.cubicai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.net.McpServer
import org.json.JSONObject

/**
 * The accounts and apps Bots can use, all kept on the PC: the user's Google account (Gmail, Calendar,
 * Drive) and apps that offer tools over MCP, either on the web or run inside the Bots' computer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val state by vm.bots.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.bots.loadConnections() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Accounts and apps", style = MaterialTheme.typography.titleLarge)
            Text(
                "What your Bots can use besides the web. Everything here is kept on your PC; give each Bot what it needs in its settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.connectionsError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            GoogleSection(vm)
            HorizontalDivider()
            AppsSection(vm, state.apps)
        }
    }
}

@Composable
private fun GoogleSection(vm: ChatViewModel) {
    val state by vm.bots.state.collectAsStateWithLifecycle()
    val g = state.google
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var editClient by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    Text("Google", style = MaterialTheme.typography.titleMedium)
    when {
        g == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        g.connected -> {
            Text(
                "Connected as ${g.email ?: "your Google account"}. Bots you allow can read your mail, calendar and Drive; " +
                    "they ask you before sending, adding, changing or deleting anything.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { vm.bots.signOutGoogle() }, modifier = Modifier.offset(x = (-12).dp), shape = MaterialTheme.shapes.small) {
                Text("Sign out", color = MaterialTheme.colorScheme.error)
            }
        }
        else -> {
            Text(
                if (g.hasClient) "Your Google Cloud app is set. Sign in to connect your account."
                else "Bots reach Gmail, Calendar and Drive through your own Google Cloud app (a free, one-time setup on the PC, about 10 minutes). " +
                    "Make a \"Desktop app\" client there, then paste its ID and secret here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!g.hasClient || editClient) {
                OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    secret, { secret = it.trim() }, label = { Text("Client secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        saving = true
                        error = null
                        vm.bots.setGoogleClient(clientId, secret) { r ->
                            saving = false
                            r.onSuccess { editClient = false; clientId = ""; secret = "" }.onFailure { error = it.message }
                        }
                    },
                    enabled = clientId.endsWith(".apps.googleusercontent.com") && secret.isNotBlank() && !saving,
                    shape = MaterialTheme.shapes.small,
                ) { Text("Save") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            error = null
                            note = null
                            vm.bots.signInGoogle(
                                openUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                            ) { r -> r.onSuccess { note = it.note }.onFailure { error = it.message } }
                        },
                        enabled = !state.signingIn,
                        shape = MaterialTheme.shapes.small,
                    ) { Text(if (state.signingIn) "Waiting for Google…" else "Sign in with Google") }
                    if (state.signingIn) TextButton(onClick = { vm.bots.cancelSignIn() }, shape = MaterialTheme.shapes.small) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    if (!state.signingIn) TextButton(onClick = { editClient = true }, shape = MaterialTheme.shapes.small) { Text("Change app") }
                }
                Text(
                    "Google says the app isn't verified, since it's your own: tap Advanced → Go to …, and tick every box. " +
                        "You can also sign in on the PC: cubicai-gateway google-login <client JSON>.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
    error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppsSection(vm: ChatViewModel, apps: List<McpServer>?) {
    var adding by remember { mutableStateOf(false) }
    Text("Apps", style = MaterialTheme.typography.titleMedium)
    Text(
        "Apps that offer tools over MCP (the Model Context Protocol): one on the web by its address, or one you run inside the Bots' locked-down computer " +
            "with a command like npx -y … or uvx …. Tools an app doesn't mark as read-only ask you first.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
        apps == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        apps.isEmpty() && !adding -> Text("No apps yet.", style = MaterialTheme.typography.bodyMedium)
        else -> apps.forEach { AppRow(vm, it) }
    }
    if (adding) AddApp(vm, onDone = { adding = false })
    else OutlinedButton(onClick = { adding = true }, shape = MaterialTheme.shapes.small) { Text("Add an app") }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppRow(vm: ChatViewModel, app: McpServer) {
    var open by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable {
                open = !open
                if (open && tools == null) vm.bots.appTools(app.id) { r ->
                    tools = r.fold(
                        { (t, err) -> err ?: if (t.isEmpty()) "No tools." else t.joinToString(", ") { it.name + if (it.readOnly) "" else " (asks)" } },
                        { it.message ?: "Couldn't reach it." },
                    )
                }
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (app.kind == "sandbox") Icons.Rounded.Inventory2 else Icons.Rounded.Cloud, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(app.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    (if (app.kind == "sandbox") "In the Bots' computer · " + app.command else app.url) + if (app.secrets.isNotEmpty()) " · with " + app.secrets.joinToString() else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                )
            }
            Spacer(Modifier.size(12.dp))
            Box(Modifier.clickable { vm.bots.updateApp(app.id, JSONObject().put("enabled", !app.enabled)) }) { CutSwitch(app.enabled) }
        }
        if (open) Column(Modifier.padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tools?.let { "Tools: $it" } ?: "Asking it for its tools…", style = MaterialTheme.typography.bodySmall)
            Text("Ask me first", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("changes" to "Before changes", "always" to "Every time", "never" to "Never").forEach { (k, label) ->
                    FilterChip(
                        selected = app.ask == k, onClick = { vm.bots.updateApp(app.id, JSONObject().put("ask", k)) }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.offset(x = (-12).dp), shape = MaterialTheme.shapes.small) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${app.name}?") },
        text = { Text("Bots lose its tools. Any token you gave it is deleted from your PC.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.bots.deleteApp(app.id) }, shape = MaterialTheme.shapes.small) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, shape = MaterialTheme.shapes.small) { Text("Cancel") } },
        shape = MaterialTheme.shapes.large,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddApp(vm: ChatViewModel, onDone: () -> Unit) {
    var sandbox by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var where by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "On the web", true to "In the Bots' computer").forEach { (k, label) ->
                FilterChip(
                    selected = sandbox == k, onClick = { sandbox = k }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            where, { where = it },
            label = { Text(if (sandbox) "Command" else "Address") },
            placeholder = { Text(if (sandbox) "npx -y @modelcontextprotocol/server-…" else "https://example.com/mcp") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (sandbox) KeyboardType.Text else KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            secret, { secret = it },
            label = { Text(if (sandbox) "Settings (optional)" else "Token (optional)") },
            placeholder = { Text(if (sandbox) "API_KEY=… (one per line)" else "Sent as Authorization: Bearer …") },
            supportingText = {
                Text(
                    if (sandbox) "Kept on your PC. Any Bot with the computer could read these while the app runs, so prefer apps on the web for important keys."
                    else "Kept on your PC and never shown again.",
                )
            },
            singleLine = !sandbox,
            minLines = if (sandbox) 2 else 1,
            visualTransformation = if (sandbox) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone, modifier = Modifier.offset(x = (-12).dp), shape = MaterialTheme.shapes.small) { Text(if (result != null) "Done" else "Cancel") }
            Spacer(Modifier.weight(1f))
            if (result == null) Button(
                onClick = {
                    busy = true
                    error = null
                    val f = JSONObject().put("name", name.trim()).put("kind", if (sandbox) "sandbox" else "remote")
                    if (sandbox) {
                        f.put("command", where.trim())
                        val env = JSONObject()
                        secret.lines().map { it.trim() }.filter { "=" in it }.forEach { l -> env.put(l.substringBefore('=').trim(), l.substringAfter('=').trim()) }
                        f.put("env", env)
                    } else {
                        f.put("url", where.trim())
                        if (secret.isNotBlank()) f.put("headers", JSONObject().put("Authorization", if (secret.trim().contains(' ')) secret.trim() else "Bearer " + secret.trim()))
                    }
                    vm.bots.addApp(f) { r ->
                        busy = false
                        r.onSuccess { (_, tools, err) ->
                            result = if (err != null) "Added, but it didn't answer: $err" else "Added, with ${tools.size} tools: " + tools.joinToString(", ") { it.name }
                        }.onFailure { error = it.message }
                    }
                },
                enabled = name.isNotBlank() && where.isNotBlank() && !busy,
                shape = MaterialTheme.shapes.small,
            ) { if (busy) Cube(18.dp, spinning = true) else Text("Add") }
        }
    }
}
