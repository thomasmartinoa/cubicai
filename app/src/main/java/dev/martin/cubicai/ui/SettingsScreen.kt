package dev.martin.cubicai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.data.Provider
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var confirmDelete by remember { mutableStateOf(false) }
    var showBehaviour by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Pairing and connection-test results arrive as notices; show them here too.
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LocalPcSection(s, vm)

            SectionTitle("Cloud API keys")
            Text(
                "Keys stay on this phone and are only sent to the provider you're chatting with.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Providers.all.filter { it != Providers.LOCAL }.forEach { p ->
                if (p == Providers.CUSTOM) OutlinedTextField(
                    value = s.customBaseUrl,
                    onValueChange = { v -> vm.updateSettings { it.copy(customBaseUrl = v.trim()) }; vm.clearModelCache() },
                    label = { Text("Custom base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyField(p, s.key(p)) { v ->
                    vm.updateSettings { it.copy(apiKeys = it.apiKeys + (p.id to v.trim())) }
                    vm.clearModelCache()
                }
            }

            SectionTitle("Behaviour")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("New chats use ${s.persona.name}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "None sends no instructions, so the model behaves as it was trained.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { showBehaviour = true }) { Text("Change") }
            }

            SectionTitle("Privacy")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Privacy screen", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Blank in the recent-apps view and blocks screenshots and screen recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.privacyScreen, onCheckedChange = { v -> vm.updateSettings { it.copy(privacyScreen = v) } })
            }

            SectionTitle("Data")
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { confirmDelete = true }) { Text("Delete all chats") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete all chats?") },
        text = { Text("This removes every conversation and attachment from this phone.") },
        confirmButton = { TextButton(onClick = { vm.deleteAllChats(); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (showBehaviour) BehaviourSheet(vm, onDismiss = { showBehaviour = false })
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun KeyField(provider: Provider, value: String, onChange: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var visible by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("${provider.name} API key") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (provider.keyUrl.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { runCatching { uriHandler.openUri(provider.keyUrl) } }) {
                Text("Get a key → ${domainOf(provider.keyUrl)}")
            }
        }
    }
}

@Composable
private fun LocalPcSection(s: Settings, vm: ChatViewModel) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var confirmUnpair by remember { mutableStateOf(false) }

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { vm.pair(it) } }
            .addOnFailureListener { vm.showNotice("Couldn't open the scanner. Paste the pairing link instead.") }
    }

    SectionTitle("Local PC (Ollama)")
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (s.isPaired) Icons.Rounded.Lock else Icons.Rounded.Computer, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (s.isPaired) "Paired with ${s.localUrl.removePrefix("https://")}" else "Not paired",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (s.isPaired) Text(
                if (s.localRemoteUrl.isNotBlank()) "Away from home: through Tailscale (${s.localRemoteUrl.removePrefix("https://")})"
                else "Away from home: off. Turn on remote access in CubicAI Server, then re-pair.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Runs models on your PC. Traffic is encrypted and locked to your PC's certificate, " +
                    "and only this phone has the token, so other devices on the network can't read or use it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.isPaired) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.small, onClick = vm::testLocalConnection) { Text("Test connection") }
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = ::scan) { Text("Re-pair") }
                }
                TextButton(onClick = { confirmUnpair = true }) { Text("Unpair") }
            } else {
                Text(
                    "On your PC open CubicAI Server → Pair",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(shape = MaterialTheme.shapes.small, onClick = ::scan) {
                    Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan pairing QR code")
                }
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("…or paste pairing link") },
                    placeholder = { Text("cubicai://pair?…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (link.isNotBlank()) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { if (vm.pair(link)) link = "" }) { Text("Pair") }
            }
        }
    }

    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text("Unpair from your PC?") },
        text = { Text("You'll need to scan the QR code again to use local models.") },
        confirmButton = { TextButton(onClick = { vm.unpair(); confirmUnpair = false }) { Text("Unpair") } },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
    )
}
