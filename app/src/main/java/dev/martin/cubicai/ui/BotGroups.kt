package dev.martin.cubicai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.net.Bot
import dev.martin.cubicai.net.BotGroup

/** A group's Bots as overlapping cubes (up to three), at most [width] wide when given. */
@Composable
internal fun GroupCubes(members: List<Bot>, size: Dp, spinning: Boolean = false, width: Dp? = null) {
    val shown = members.take(3)
    val step = if (width != null && shown.size > 1) (width - size) / (shown.size - 1) else size * 0.55f
    Box(Modifier.size(width = size + step * (shown.size - 1).coerceAtLeast(0), height = size)) {
        shown.forEachIndexed { i, b ->
            Cube(size, Modifier.offset(x = step * i), spinning = spinning && i == 0, colors = cubeColors(b.color))
        }
    }
}

@Composable
internal fun GroupRow(group: BotGroup, bots: List<Bot>, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val members = group.members.mapNotNull { m -> bots.firstOrNull { it.id == m } }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // As wide as a Bot's cube, so the text lines up with the Bots above.
            Box(Modifier.width(32.dp)) { GroupCubes(members, 20.dp, spinning = group.running, width = 32.dp) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    group.running -> ShimmerText("Talking")
                    group.last != null -> Text(
                        (group.lastBot?.let { b -> members.firstOrNull { it.id == b }?.name?.let { "$it: " } } ?: "You: ") + group.last.replace('\n', ' '),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (group.lastError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text("Group chat", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    members.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options") }
                CubicMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        }
    }
    if (confirm) DeleteGroupDialog(group.name, onDismiss = { confirm = false }, onConfirm = { confirm = false; onDelete() })
}

@Composable
private fun DeleteGroupDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = { Text("The group's chat is deleted from your PC. The Bots, their own chats and memory stay.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Makes a group ([group] null) or changes one: its name and which 2 to 6 Bots are in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditSheet(vm: ChatViewModel, group: BotGroup?, onDismiss: () -> Unit, onSaved: (BotGroup) -> Unit) {
    val state by vm.bots.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(group?.name ?: "") }
    var members by remember { mutableStateOf(group?.members ?: emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (group == null) "New group" else "Edit group", style = MaterialTheme.typography.titleLarge)
            Text(
                "Put 2 to 6 Bots in one chat. The ones you @mention answer; otherwise the one whose job fits best. They can bring each other in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Name") },
                placeholder = { Text("e.g. Newsroom") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Bots (${members.size} of 2–6)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                state.bots.forEach { b ->
                    val picked = b.id in members
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .clickable {
                                members = if (picked) members - b.id else if (members.size < 6) members + b.id else members
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Cube(24.dp, colors = cubeColors(b.color))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(b.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        b.job.ifBlank { "No job yet" }.replace('\n', ' '),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (picked) Icon(Icons.Rounded.Check, "In the group", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group != null) TextButton(onClick = { confirmDelete = true }, shape = MaterialTheme.shapes.small) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.small) { Text("Cancel") }
                Button(
                    onClick = {
                        saving = true
                        error = null
                        val done: (Result<BotGroup>) -> Unit = { r -> saving = false; r.onSuccess(onSaved).onFailure { error = it.message } }
                        if (group == null) vm.bots.createGroup(name, members, done) else vm.bots.updateGroup(group.id, name, members, done)
                    },
                    enabled = !saving && name.isNotBlank() && members.size in 2..6,
                    shape = MaterialTheme.shapes.small,
                ) { Text(if (group == null) "Make group" else "Save") }
            }
        }
    }
    if (confirmDelete && group != null) DeleteGroupDialog(group.name, onDismiss = { confirmDelete = false }, onConfirm = {
        confirmDelete = false
        vm.bots.deleteGroup(group.id) { onDismiss() }
    })
}
