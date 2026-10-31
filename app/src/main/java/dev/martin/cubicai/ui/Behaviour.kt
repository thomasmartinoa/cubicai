package dev.martin.cubicai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import dev.martin.cubicai.data.newId
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.data.Persona

/** Picks how the assistant behaves in this chat (and new ones), and edits custom behaviours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehaviourSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val selected = state.current.personaId
    var editing by remember { mutableStateOf<Persona?>(null) }
    var aboutMe by remember { mutableStateOf(s.aboutMe) }

    ModalBottomSheet(
        onDismissRequest = { vm.setAboutMe(aboutMe); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Same layout as the model picker: content on the 20dp edge, full-width rows, the chosen one on a tinted band.
        val edge = Modifier.padding(horizontal = 20.dp)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding(),
        ) {
            Text("Behaviour", style = MaterialTheme.typography.titleLarge, modifier = edge)
            Text(
                "Used in this chat and in new chats. Each chat remembers its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = edge,
            )
            Spacer(Modifier.height(12.dp))
            s.personas.forEach { p ->
                val on = p.id == selected
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                        .clickable { vm.selectPersona(p.id) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tone = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    Box(
                        Modifier.padding(end = 16.dp).size(18.dp)
                            .border(2.dp, tone, CutCornerShape(5.dp))
                            .padding(3.dp)
                            .background(if (on) tone else Color.Transparent, CutCornerShape(2.dp)),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        val summary = p.summary.ifBlank { p.instructions.lineSequence().firstOrNull().orEmpty() }
                        if (summary.isNotBlank()) Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    if (!p.builtIn) IconButton(onClick = { editing = p }) { Icon(Icons.Rounded.Edit, "Edit ${p.name}") }
                }
            }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { editing = Persona(id = "", name = "", instructions = "") }, modifier = edge.padding(top = 8.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("New behaviour")
            }

            if (s.isPaired) ChatCharacter(vm, edge)

            Spacer(Modifier.height(20.dp))
            Text("About you", style = MaterialTheme.typography.titleMedium, modifier = edge)
            Text(
                "Shared with every behaviour except None. For example your name, city, job or how you like answers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = edge,
            )
            OutlinedTextField(
                value = aboutMe,
                onValueChange = { aboutMe = it },
                placeholder = { Text("e.g. I live in Kochi and prefer metric units.", color = MaterialTheme.colorScheme.outline) },
                minLines = 3,
                maxLines = 8,
                modifier = edge.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }

    editing?.let { p ->
        PersonaEditor(
            vm = vm,
            persona = p,
            onDismiss = { editing = null },
            onSave = { vm.savePersona(it); editing = null },
            onDelete = if (p.id.isNotBlank()) ({ vm.deletePersona(p.id); editing = null }) else null,
        )
    }
}

/**
 * This chat's copy of the character's face and voice, which the model's pictures and voice messages start from.
 * It begins as the behaviour's own; changing it here leaves other chats alone until it's saved to the behaviour.
 */
@Composable
private fun ChatCharacter(vm: ChatViewModel, edge: Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val chat = state.current
    val persona = state.settings.personas.firstOrNull { it.id == chat.personaId }
    val custom = persona?.takeIf { !it.builtIn }
    val pickFace = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.setChatCharacterFile(face = true, uri = it) } }
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.setChatCharacterFile(face = false, uri = it) } }
    Spacer(Modifier.height(20.dp))
    Text("Character in this chat", style = MaterialTheme.typography.titleMedium, modifier = edge)
    Text(
        "When the model sends a picture of itself it starts from this face, and its voice messages copy this voice.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = edge,
    )
    CharacterFile(
        face = true,
        path = chat.facePath ?: persona?.facePath,
        caption = when {
            chat.facePath != null -> "This chat's own face"
            persona?.facePath != null -> "From ${persona.name}"
            else -> "None yet. The first picture of the character becomes its face."
        },
        modifier = edge.padding(top = 12.dp),
    ) {
        TextButton(onClick = { pickFace.launch("image/*") }, shape = MaterialTheme.shapes.small) { Text(if (chat.facePath ?: persona?.facePath == null) "Add" else "Change") }
        if (chat.facePath != null) {
            if (custom != null) TextButton(onClick = { vm.saveCharacterToBehaviour(face = true) }, shape = MaterialTheme.shapes.small) { Text("Save to ${custom.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { vm.setChatCharacterFile(face = true, uri = null) }, shape = MaterialTheme.shapes.small) { Text("Remove") }
        }
    }
    CharacterFile(
        face = false,
        path = chat.voicePath ?: persona?.voicePath,
        caption = when {
            chat.voicePath != null -> "This chat's own voice"
            persona?.voicePath != null -> "From ${persona.name}"
            else -> "Built-in voice, a woman's or a man's to fit the character. Add a 5-10 s recording to copy a voice."
        },
        modifier = edge.padding(top = 12.dp),
    ) {
        TextButton(onClick = { pickVoice.launch("audio/*") }, shape = MaterialTheme.shapes.small) { Text(if (chat.voicePath ?: persona?.voicePath == null) "Add" else "Change") }
        if (chat.voicePath != null) {
            if (custom != null) TextButton(onClick = { vm.saveCharacterToBehaviour(face = false) }, shape = MaterialTheme.shapes.small) { Text("Save to ${custom.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { vm.setChatCharacterFile(face = false, uri = null) }, shape = MaterialTheme.shapes.small) { Text("Remove") }
        }
    }
}

/** A face picture or voice sample: a small square (the face itself, or a sound mark), what it is, and its actions. */
@Composable
private fun CharacterFile(face: Boolean, path: String?, caption: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // A hairline edge, so the empty square reads as a slot on the sheet and in a dialog alike.
        val frame = Modifier.size(56.dp).clip(MaterialTheme.shapes.small)
        if (face && path != null) LocalImage(path, frame, maxSize = 168)
        else Box(
            frame.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (face) Icons.Rounded.Face else Icons.Rounded.GraphicEq,
                null,
                tint = if (path != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(if (face) "Face" else "Voice", style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Text buttons pulled in by their 12dp inner padding, so the first label lines up with the text above.
            Row(Modifier.offset(x = (-12).dp)) { actions() }
        }
    }
}

@Composable
private fun PersonaEditor(vm: ChatViewModel, persona: Persona, onDismiss: () -> Unit, onSave: (Persona) -> Unit, onDelete: (() -> Unit)?) {
    // A new behaviour gets its id now, so a face or voice picked before saving can be named after it.
    val id = remember { persona.id.ifBlank { newId() } }
    var name by remember { mutableStateOf(persona.name) }
    var instructions by remember { mutableStateOf(persona.instructions) }
    var looks by remember { mutableStateOf(persona.looks) }
    var face by remember { mutableStateOf(persona.facePath) }
    var voice by remember { mutableStateOf(persona.voicePath) }
    var onItsOwn by remember { mutableStateOf(persona.sendsOnItsOwn) }
    val scope = rememberCoroutineScope()
    fun pick(isFace: Boolean) = { uri: android.net.Uri? ->
        if (uri != null) scope.launch {
            val path = vm.importCharacterFile(uri, "persona-$id-" + if (isFace) "face" else "voice") ?: return@launch
            // A file picked a moment ago in this editor, and not saved, is replaced rather than left behind.
            if (isFace) { if (face != persona.facePath) vm.discardCharacterFile(face); face = path }
            else { if (voice != persona.voicePath) vm.discardCharacterFile(voice); voice = path }
        }
        Unit
    }
    val pickFace = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), pick(true))
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), pick(false))
    fun cancel() {
        if (face != persona.facePath) vm.discardCharacterFile(face)
        if (voice != persona.voicePath) vm.discardCharacterFile(voice)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::cancel,
        title = { Text(if (persona.id.isBlank()) "New behaviour" else "Edit behaviour") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    instructions,
                    { instructions = it },
                    label = { Text("Instructions") },
                    placeholder = { Text("Reply like a calm pirate. Keep answers under 100 words.") },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    looks,
                    { looks = it },
                    label = { Text("Looks (optional)") },
                    placeholder = { Text("For a character: age, hair, eyes, build, usual clothes. Pictures of it follow this.") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                CharacterFile(face = true, path = face, caption = if (face != null) "Pictures of the character start from it" else "None. The first picture of the character becomes it.") {
                    TextButton(onClick = { pickFace.launch("image/*") }, shape = MaterialTheme.shapes.small) { Text(if (face == null) "Add" else "Change") }
                    if (face != null) TextButton(onClick = { if (face != persona.facePath) vm.discardCharacterFile(face); face = null }, shape = MaterialTheme.shapes.small) { Text("Remove") }
                }
                CharacterFile(face = false, path = voice, caption = voice?.let { "Voice messages copy this voice" } ?: "Built-in voice") {
                    TextButton(onClick = { pickVoice.launch("audio/*") }, shape = MaterialTheme.shapes.small) { Text(if (voice == null) "Add" else "Change") }
                    if (voice != null) TextButton(onClick = { if (voice != persona.voicePath) vm.discardCharacterFile(voice); voice = null }, shape = MaterialTheme.shapes.small) { Text("Remove") }
                }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onItsOwn = !onItsOwn }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Sends on its own", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "May send pictures and voice messages without being asked, when the moment calls for it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    CutSwitch(onItsOwn)
                }
                // Pulled in by its 12dp inner padding so the label lines up with the fields' edge.
                onDelete?.let {
                    TextButton(onClick = it, shape = MaterialTheme.shapes.small, modifier = Modifier.offset(x = (-12).dp)) {
                        Text("Delete behaviour", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The behaviour's earlier face or voice, now replaced or removed, isn't kept.
                    if (persona.facePath != face) vm.discardCharacterFile(persona.facePath)
                    if (persona.voicePath != voice) vm.discardCharacterFile(persona.voicePath)
                    onSave(persona.copy(
                        id = id, name = name.trim(), instructions = instructions.trim(), looks = looks.trim(),
                        facePath = face, voicePath = voice, sendsOnItsOwn = onItsOwn,
                    ))
                },
                enabled = name.isNotBlank() && instructions.isNotBlank(),
                shape = MaterialTheme.shapes.small,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = ::cancel, shape = MaterialTheme.shapes.small) { Text("Cancel") } },
    )
}
