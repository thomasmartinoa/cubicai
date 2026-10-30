package dev.martin.cubicai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
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
        // One rhythm throughout: 24dp before a section title, 12dp from the title to its card, 16dp inside cards.
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocalPcSection(s, state.testingConnection, vm)

            SectionTitle("Cloud API keys")
            SettingsCard {
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
            }

            SectionTitle("Behaviour")
            SettingsCard {
                SettingRow("New chats use ${s.persona.name}", "None sends no instructions, so the model behaves as it was trained.") {
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { showBehaviour = true }) { Text("Change") }
                }
            }

            SectionTitle("Appearance")
            SettingsCard {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                    options.forEachIndexed { i, (id, label) ->
                        SegmentedButton(
                            selected = s.theme == id,
                            onClick = { vm.updateSettings { it.copy(theme = id) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size, MaterialTheme.shapes.small),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            label = { Text(label) },
                        )
                    }
                }
            }

            SectionTitle("Privacy")
            SettingsCard {
                // The whole row is the switch: a bigger target, and TalkBack reads the title with the state.
                SettingRow(
                    "Privacy screen",
                    "Blank in the recent-apps view and blocks screenshots and screen recording.",
                    Modifier.clip(MaterialTheme.shapes.small).toggleable(s.privacyScreen, role = Role.Switch) { v ->
                        vm.updateSettings { it.copy(privacyScreen = v) }
                    },
                ) { CutSwitch(s.privacyScreen) }
            }

            SectionTitle("Data")
            SettingsCard {
                SettingRow("Delete all chats", "Removes every conversation and attachment from this phone.") {
                    OutlinedButton(
                        shape = MaterialTheme.shapes.small,
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    ) { Text("Delete") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete all chats?") },
        text = { Text("This removes every conversation and attachment from this phone. It can't be undone.") },
        confirmButton = {
            TextButton(onClick = { vm.deleteAllChats(); confirmDelete = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (showBehaviour) BehaviourSheet(vm, onDismiss = { showBehaviour = false })
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
}

/** The container every settings section uses, so all sections share one shape, tone and inset. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A setting's name and explanation, with its control on the right. */
@Composable
private fun SettingRow(title: String, description: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        control()
    }
}

/**
 * On/off toggle in the app's bevelled style; Material's switch is pill-shaped and can't take another shape.
 * Only draws the state: the row around it handles taps.
 */
@Composable
private fun CutSwitch(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val thumbX by animateDpAsState(if (checked) 20.dp else 0.dp, tween(160), label = "thumb")
    val track by animateColorAsState(if (checked) colors.primary else colors.surfaceContainerHighest, tween(160), label = "track")
    val edge by animateColorAsState(if (checked) colors.primary else colors.outline, tween(160), label = "edge")
    val thumb by animateColorAsState(if (checked) colors.onPrimary else colors.onSurfaceVariant, tween(160), label = "thumbColor")
    Box(
        Modifier.padding(vertical = 8.dp).size(52.dp, 32.dp).clip(CutCornerShape(8.dp)).background(track)
            .border(2.dp, edge, CutCornerShape(8.dp)).padding(6.dp),
    ) {
        Box(Modifier.offset(x = thumbX).size(20.dp).background(thumb, CutCornerShape(4.dp)))
    }
}

@Composable
private fun KeyField(provider: Provider, value: String, onChange: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var visible by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("${provider.shortName} API key") },
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
        // A compact link rather than a full-height button, so each key and its link read as one group.
        if (provider.keyUrl.isNotEmpty()) Text(
            "Get a key → ${domainOf(provider.keyUrl)}",
            // Starts where the field's text does, like Material's supporting text.
            Modifier.padding(start = 12.dp, top = 2.dp).clip(MaterialTheme.shapes.extraSmall)
                .clickable { runCatching { uriHandler.openUri(provider.keyUrl) } }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalPcSection(s: Settings, testing: Boolean, vm: ChatViewModel) {
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
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                else "Away from home: off. Turn on remote access in CubicAI Server, then tap Test connection at home.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Runs models on your PC. Traffic is encrypted and locked to your PC's certificate, " +
                    "and only this phone has the token, so other devices on the network can't read or use it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.isPaired) {
                // Wraps onto a second line with large text instead of squeezing a button to one letter per line.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.small, onClick = vm::testLocalConnection, enabled = !testing) {
                        Text(if (testing) "Testing…" else "Test connection")
                    }
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = ::scan) { Text("Re-pair") }
                    TextButton(
                        onClick = { confirmUnpair = true },
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Unpair") }
                }
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
        confirmButton = { TextButton(onClick = { vm.unpair(); confirmUnpair = false }) { Text("Unpair", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
    )
}
