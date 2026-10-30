package dev.martin.cubicai.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Choose a model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (provider == Providers.OPENROUTER) FilterChip(freeOnly, { freeOnly = !freeOnly }, label = { Text("Free") })
                if (models.orEmpty().any { it.vision }) FilterChip(visionOnly, { visionOnly = !visionOnly }, label = { Text("Sees images") })
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onDismiss(); onOpenSettings() }) { Text("Open Settings") }
            }
            when {
                models == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                filtered.isEmpty() -> Text(
                    if (query.isNotBlank()) "No match. Tap ✓ to use \"${query.trim()}\" as the model id." else "No models.",
                    Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(filtered, key = { it.id }) { m ->
                        val tags = listOfNotNull(
                            "recommended".takeIf { m.recommended },
                            "free".takeIf { m.free },
                            "sees images".takeIf { m.vision },
                            m.contextTokens.takeIf { it > 0 }?.let { "${contextLabel(it)} context" },
                            m.sizeBytes.takeIf { it > 0 }?.let { "%.1f GB".format(it / 1e9) },
                        ).joinToString(", ")
                        ListItem(
                            headlineContent = { Text(m.id) },
                            supportingContent = if (tags.isNotEmpty()) ({ Text(tags) }) else null,
                            trailingContent = if (m.id == selected) ({ Icon(Icons.Rounded.Check, "Selected", Modifier.size(20.dp)) }) else null,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { choose(m.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun contextLabel(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%dM".format((tokens + 524_287) / 1_048_576)
    else -> "${tokens / 1024}K"
}
