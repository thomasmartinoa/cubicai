package dev.martin.cubicai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.net.ModelInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(vm: ChatViewModel, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = state.settings
    var providerId by remember { mutableStateOf(settings.providerId) }
    val provider = Providers.get(providerId)
    var models by remember(providerId) { mutableStateOf<List<ModelInfo>?>(null) }
    var error by remember(providerId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    var visionOnly by remember { mutableStateOf(false) }

    LaunchedEffect(providerId) {
        runCatching { vm.listModels(provider) }
            .onSuccess { models = it }
            .onFailure { e ->
                error = e.message
                models = provider.suggestedModels.map { ModelInfo(it, vision = false, free = it.endsWith(":free")) }
            }
    }

    val selected = if (providerId == settings.providerId) settings.model(provider) else null
    val filtered = models.orEmpty().filter { m ->
        (query.isBlank() || m.id.contains(query.trim(), ignoreCase = true)) &&
            (!freeOnly || m.free) && (!visionOnly || m.vision)
    }

    fun choose(id: String) {
        vm.selectModel(provider, id)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Everything sits on the sheet's 20dp edge; the provider tabs scroll edge to edge but start on it too.
        val edge = Modifier.padding(horizontal = 20.dp)
        Column(Modifier.padding(bottom = 16.dp)) {
            Text("Choose a model", style = MaterialTheme.typography.titleLarge, modifier = edge.padding(bottom = 12.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Providers.all.forEach { p ->
                    FilterChip(
                        selected = p.id == providerId,
                        onClick = { providerId = p.id; query = "" },
                        label = { Text(p.shortName, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search, or type any model id") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = { choose(query.trim()) }) { Icon(Icons.Rounded.Check, "Use this id") }
                },
                singleLine = true,
                modifier = edge.fillMaxWidth().padding(top = 12.dp),
            )
            Row(edge.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (provider == Providers.OPENROUTER) FilterChip(freeOnly, { freeOnly = !freeOnly }, label = { Text("Free") })
                if (models.orEmpty().any { it.vision }) FilterChip(visionOnly, { visionOnly = !visionOnly }, label = { Text("Sees images") })
            }
            error?.let {
                Column(edge) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onDismiss(); onOpenSettings() }, modifier = Modifier.offset(x = (-12).dp)) { Text("Open Settings") }
                }
            }
            when {
                models == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Cube(28.dp, spinning = true)
                }
                filtered.isEmpty() -> Text(
                    if (query.isNotBlank()) "No match. Tap ✓ to use \"${query.trim()}\" as the model id." else "No models.",
                    edge.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(filtered, key = { it.id }) { m ->
                        // "hf.co/Owner/Long-Model-Name:Q4" reads better as the name, with the owner below it.
                        val owner = m.id.substringBeforeLast('/', "")
                        // The quantisation tag goes with the details too, so a long name doesn't break mid-word.
                        val name = displayModelName(m.id)
                        val quant = shortModelName(m.id).removePrefix(name).removePrefix(":")
                        val tags = listOfNotNull(
                            owner.takeIf { it.isNotEmpty() },
                            quant.takeIf { it.isNotEmpty() },
                            "recommended".takeIf { m.recommended },
                            when (provider.mediaKind(m.id)) {
                                "image" -> "makes\u00A0pictures"
                                "video" -> "makes\u00A0videos"
                                else -> null
                            },
                            "team\u00A0of\u00A0agents".takeIf { provider.isMultiAgent(m.id) },
                            "free".takeIf { m.free },
                            "sees\u00A0images".takeIf { m.vision },
                            m.contextTokens.takeIf { it > 0 && provider.mediaKind(m.id) == null }?.let { "${contextLabel(it)}\u00A0context" },
                            m.sizeBytes.takeIf { it > 0 }?.let { "%.1f\u00A0GB".format(it / 1e9) },
                        ).joinToString(", ")
                        val isSelected = m.id == selected || (providerId == settings.providerId && when (provider.mediaKind(m.id)) {
                            "image" -> state.imageMode && m.id == settings.imageModel(provider)
                            "video" -> state.videoMode && m.id == settings.videoModel(provider)
                            else -> false
                        })
                        // Full-width rows with text on the 20dp edge; the chosen one gets a tinted band, not just a tick.
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                                .clickable { choose(m.id) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    // A zero-width space after each "-" lets a long id wrap between its parts.
                                    name.replace("-", "-\u200B"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (tags.isNotEmpty()) Text(
                                    tags,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isSelected) Icon(
                                Icons.Rounded.Check,
                                "Selected",
                                Modifier.padding(start = 12.dp).size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

fun contextLabel(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%dM".format((tokens + 524_287) / 1_048_576)
    else -> "${tokens / 1024}K"
}
