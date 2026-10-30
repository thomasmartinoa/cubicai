package dev.martin.cubicai.ui

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
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
        ) {
            Text("Behaviour", style = MaterialTheme.typography.titleLarge)
            Text(
                "Used in this chat and in new chats. Each chat remembers its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            s.personas.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { vm.selectPersona(p.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val on = p.id == selected
                    val tone = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    Box(
                        Modifier.padding(15.dp).size(18.dp)
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
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { editing = Persona(id = "", name = "", instructions = "") }, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("New behaviour")
            }

            Spacer(Modifier.height(20.dp))
            Text("About you", style = MaterialTheme.typography.titleMedium)
            Text(
                "Shared with every behaviour except None. For example your name, city, job or how you like answers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = aboutMe,
                onValueChange = { aboutMe = it },
                placeholder = { Text("I live in Kochi and prefer metric units.") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }

    editing?.let { p ->
        PersonaEditor(
            persona = p,
            onDismiss = { editing = null },
            onSave = { vm.savePersona(it); editing = null },
            onDelete = if (p.id.isNotBlank()) ({ vm.deletePersona(p.id); editing = null }) else null,
        )
    }
}

@Composable
private fun PersonaEditor(persona: Persona, onDismiss: () -> Unit, onSave: (Persona) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(persona.name) }
    var instructions by remember { mutableStateOf(persona.instructions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (persona.id.isBlank()) "New behaviour" else "Edit behaviour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                onDelete?.let { TextButton(onClick = it) { Text("Delete behaviour", color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(persona.copy(name = name.trim(), instructions = instructions.trim())) },
                enabled = name.isNotBlank() && instructions.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
