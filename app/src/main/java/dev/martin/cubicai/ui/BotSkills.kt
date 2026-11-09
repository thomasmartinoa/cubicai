package dev.martin.cubicai.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.bots.OpenBot
import dev.martin.cubicai.net.Skill

/**
 * The skills library: saved how-tos every Bot can use, called with /name in a message. Bots propose
 * new ones (and changes); those wait here, and in the proposing Bot's chat, for a yes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val state by vm.bots.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Skill?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.bots.loadSkills() }
    val all = state.skills
    val waiting = all.filter { !it.approved }
    val ready = all.filter { it.approved }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Skills", style = MaterialTheme.typography.titleLarge)
            Text(
                "How-tos all your Bots can use. Start a message with /\u2060name to have a Bot follow one. Bots propose skills after they've worked something out, or when you teach them a task in their browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (waiting.isNotEmpty()) {
                Text("Waiting for you", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                waiting.forEach { SkillProposal(vm, it, all) }
            }
            if (ready.isEmpty() && waiting.isEmpty()) Text(
                "No skills yet.",
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ready.isNotEmpty()) {
                if (waiting.isNotEmpty()) HorizontalDivider()
                ready.forEach { sk ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { editing = sk }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Rounded.AutoStories, null, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("/" + sk.name, style = MaterialTheme.typography.titleSmall)
                            if (sk.about.isNotBlank()) Text(sk.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { adding = true }, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New skill")
            }
        }
    }
    if (adding) SkillEditor(vm, null, onDismiss = { adding = false })
    editing?.let { sk -> SkillEditor(vm, sk, onDismiss = { editing = null }) }
}

/** A skill a Bot proposed: what it says (and, for a change, which skill), Approve or Decline. */
@Composable
private fun SkillProposal(vm: ChatViewModel, sk: Skill, all: List<Skill>, padded: Boolean = false) {
    var unfolded by remember { mutableStateOf(false) }
    val changes = sk.replaces?.let { r -> all.firstOrNull { it.id == r } }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().then(if (padded) Modifier.padding(horizontal = 16.dp, vertical = 4.dp) else Modifier),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp).animateContentSize(tween(200))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.School, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "${sk.byName.ifBlank { "A Bot" }} " + (if (changes != null) "wants to change a skill" else "wants to save a skill"),
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text("/" + sk.name + if (sk.about.isNotBlank()) " · " + sk.about else "", Modifier.padding(top = 6.dp, end = 8.dp), style = MaterialTheme.typography.titleSmall)
            Text(
                sk.steps,
                Modifier.padding(top = 4.dp, end = 8.dp).clip(MaterialTheme.shapes.extraSmall).clickable { unfolded = !unfolded },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (unfolded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.bots.deleteSkill(sk.id) }, shape = MaterialTheme.shapes.small) { Text("Decline") }
                TextButton(onClick = { vm.bots.approveSkill(sk.id) }, shape = MaterialTheme.shapes.small) { Text("Approve") }
            }
        }
    }
}

/** Skills the open Bot (or a Bot of the open group) proposed, above the composer. */
@Composable
internal fun SkillApprovals(vm: ChatViewModel, open: OpenBot) {
    val state by vm.bots.state.collectAsStateWithLifecycle()
    val who = if (open.isGroup) open.group?.members.orEmpty().toSet() else setOf(open.bot.id)
    val waiting = state.skills.filter { !it.approved && it.by in who }
    Column(Modifier.animateContentSize(tween(200))) {
        waiting.forEach { SkillProposal(vm, it, state.skills, padded = true) }
    }
}

/** Writes a new skill ([skill] null), or changes or deletes one. */
@Composable
private fun SkillEditor(vm: ChatViewModel, skill: Skill?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(skill?.name ?: "") }
    var about by remember { mutableStateOf(skill?.about ?: "") }
    var steps by remember { mutableStateOf(skill?.steps ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (skill == null) "New skill" else "/" + skill.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    placeholder = { Text("check-bill") },
                    prefix = { Text("/") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it.take(200).replace("\n", " ") },
                    label = { Text("What it's for") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = steps,
                    onValueChange = { steps = it.take(8000) },
                    label = { Text("Steps") },
                    placeholder = { Text("1. Open …\n2. …\n3. Check that …") },
                    minLines = 5,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (skill != null) TextButton(onClick = { confirmDelete = true }, shape = MaterialTheme.shapes.small) {
                    Text("Delete skill", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saving = true
                    error = null
                    val done: (Result<Unit>) -> Unit = { r -> saving = false; r.onSuccess { onDismiss() }.onFailure { error = it.message } }
                    if (skill == null) vm.bots.addSkill(name, about, steps, done) else vm.bots.updateSkill(skill.id, name, about, steps, done)
                },
                enabled = !saving && name.isNotBlank() && steps.isNotBlank(),
                shape = MaterialTheme.shapes.small,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.small) { Text("Cancel") } },
    )
    if (confirmDelete && skill != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete /${skill.name}?") },
        text = { Text("No Bot can use it after this.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; vm.bots.deleteSkill(skill.id) { onDismiss() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
