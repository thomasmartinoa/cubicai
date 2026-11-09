package dev.martin.cubicai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.bots.OpenBot
import dev.martin.cubicai.net.BotRoutine
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Day bits as the PC has them: 0 = Sunday. Shown Monday first. */
private val DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
private val DAY_KEYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
private val DAY_LETTERS = listOf("S", "M", "T", "W", "T", "F", "S")
private val EVERY_HOURS = listOf(0.5, 1.0, 2.0, 3.0, 6.0, 12.0, 24.0)

/** A routine being made or changed. [kind]: daily, weekdays, days, every or once. */
private data class RoutineDraft(
    val id: String? = null,
    val name: String = "",
    val task: String = "",
    val kind: String = "daily",
    val hour: Int = 8,
    val minute: Int = 0,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val hours: Double = 6.0,
    val date: LocalDate = LocalDate.now().plusDays(1),
) {
    fun schedule(): JSONObject {
        val time = "%02d:%02d".format(hour, minute)
        return when (kind) {
            "every" -> JSONObject().put("kind", "every").put("hours", hours)
            "once" -> JSONObject().put("kind", "once").put("date", date.toString()).put("time", time)
            "weekdays" -> JSONObject().put("kind", "weekdays").put("time", time)
            "days" -> JSONObject().put("kind", "daily").put("time", time).put("days", JSONArray(days.sorted().map { DAY_KEYS[it] }))
            else -> JSONObject().put("kind", "daily").put("time", time)
        }
    }

    fun fields(): JSONObject = JSONObject().put("name", name.trim()).put("task", task.trim()).put("schedule", schedule())

    val ready get() = task.isNotBlank() && (kind != "days" || days.isNotEmpty())

    companion object {
        fun of(r: BotRoutine): RoutineDraft {
            val s = r.schedule
            val (h, m) = s.optString("time", "08:00").split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 8) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            val base = RoutineDraft(r.id, r.name, r.task, hour = h, minute = m)
            return when (s.optString("kind")) {
                "every" -> base.copy(kind = "every", hours = s.optDouble("hours", 6.0))
                "once" -> base.copy(kind = "once", date = runCatching { LocalDate.parse(s.optString("date")) }.getOrDefault(base.date))
                else -> {
                    val arr = s.optJSONArray("days") ?: JSONArray()
                    val days = (0 until arr.length()).mapNotNull { DAY_KEYS.indexOf(arr.optString(it)).takeIf { i -> i >= 0 } }.toSet()
                    when (days) {
                        (0..6).toSet(), emptySet<Int>() -> base.copy(kind = "daily")
                        setOf(1, 2, 3, 4, 5) -> base.copy(kind = "weekdays")
                        else -> base.copy(kind = "days", days = days)
                    }
                }
            }
        }
    }
}

/** "today 18:00", "tomorrow 08:00", "Mon 16 Nov 08:00". */
internal fun whenNext(epochSeconds: Long): String {
    val t = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        today -> "today $time"
        today.plusDays(1) -> "tomorrow $time"
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) + " $time"
    }
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
}

private fun hoursLabel(h: Double) = when {
    h < 1 -> "${(h * 60).toInt()} min"
    h == 1.0 -> "1 hour"
    else -> "${h.toInt()} hours"
}

/** Routines a Bot proposed, waiting for a yes: shown above the composer. */
@Composable
internal fun RoutineApprovals(vm: ChatViewModel, open: OpenBot) {
    val waiting = open.routines.filter { !it.approved }
    Column(Modifier.animateContentSize(tween(200))) {
        waiting.forEach { r ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "${open.bot.name} wants a routine",
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text("“${r.name}” ${r.`when`}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall)
                    Text(
                        r.task,
                        Modifier.padding(top = 2.dp, end = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.bots.deleteRoutine(r.id) }, shape = MaterialTheme.shapes.small) { Text("Decline") }
                        TextButton(onClick = { vm.bots.approveRoutine(r.id) }, shape = MaterialTheme.shapes.small) { Text("Approve") }
                    }
                }
            }
        }
    }
}

/** A message a routine sent, in the Bot's chat: which routine, and its task (folded). */
@Composable
internal fun RoutineRunRow(name: String, task: String) {
    var unfolded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { unfolded = !unfolded }.padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Routine · $name",
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            task,
            Modifier.padding(start = 24.dp, top = 2.dp).animateContentSize(tween(200)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (unfolded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The open Bot's routines: list, switch on/off, run now, make and change them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutinesSheet(vm: ChatViewModel, open: OpenBot, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<RoutineDraft?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AnimatedContent(
            targetState = editing,
            contentKey = { it == null },
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "routines",
        ) { draft ->
            if (draft == null) RoutineList(vm, open, onNew = { editing = RoutineDraft() }, onEdit = { editing = RoutineDraft.of(it) })
            else RoutineEditor(vm, open, draft, onChange = { editing = it }, onDone = { editing = null })
        }
    }
}

@Composable
private fun RoutineList(vm: ChatViewModel, open: OpenBot, onNew: () -> Unit, onEdit: (BotRoutine) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text("Routines", style = MaterialTheme.typography.titleLarge)
        Text(
            "${open.bot.name} does these on your PC at set times, even with your phone off, and you get a notification with what it found. " +
                "It can also propose routines when you ask for something regular; they run once you approve them.",
            Modifier.padding(top = 4.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open.routines.isEmpty()) Text(
            "No routines yet. For example: “Every weekday at 08:00, give me the top news on my topics.”",
            Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        open.routines.forEach { r -> RoutineItem(vm, r, open.running, onEdit = { onEdit(r) }) }
        OutlinedButton(onClick = onNew, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 12.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New routine")
        }
    }
}

@Composable
private fun RoutineItem(vm: ChatViewModel, r: BotRoutine, running: Boolean, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val on = r.enabled && r.approved
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(MaterialTheme.shapes.small)
            .clickable(enabled = r.approved) { vm.bots.updateRoutine(r.id, JSONObject().put("enabled", !r.enabled)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    !r.approved -> "${r.`when`.replaceFirstChar { it.uppercase() }} · waiting for your approval"
                    !r.enabled -> "${r.`when`.replaceFirstChar { it.uppercase() }} · off"
                    r.nextRun != null -> "${r.`when`.replaceFirstChar { it.uppercase() }} · next ${whenNext(r.nextRun)}"
                    else -> r.`when`.replaceFirstChar { it.uppercase() }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!r.approved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(r.task, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (r.approved) CutSwitch(on)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options") }
            CubicMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!r.approved) DropdownMenuItem(
                    text = { Text("Approve") },
                    leadingIcon = { Icon(Icons.Rounded.Schedule, null) },
                    onClick = { menu = false; vm.bots.approveRoutine(r.id) },
                )
                DropdownMenuItem(
                    text = { Text("Run now") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                    enabled = r.approved && !running,
                    onClick = { menu = false; vm.bots.runRoutine(r.id) },
                )
                DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(
                    text = { Text(if (r.approved) "Delete" else "Decline", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; if (r.approved) confirm = true else vm.bots.deleteRoutine(r.id) },
                )
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete “${r.name}”?") },
        text = { Text("It won't run again. What it already did stays in the chat.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.bots.deleteRoutine(r.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineEditor(vm: ChatViewModel, open: OpenBot, draft: RoutineDraft, onChange: (RoutineDraft) -> Unit, onDone: () -> Unit) {
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickTime by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pulled in by its inner padding, so the arrow sits on the sheet's edge.
            IconButton(onClick = onDone, Modifier.offset(x = (-12).dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(if (draft.id == null) "New routine" else "Edit routine", Modifier.offset(x = (-4).dp), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = draft.task,
            onValueChange = { onChange(draft.copy(task = it)) },
            label = { Text("What ${open.bot.name} does each time") },
            placeholder = { Text("Give me the top 5 AI and Android stories since yesterday, one line each with a link.") },
            minLines = 3,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it.take(60))) },
            label = { Text("Name (optional)") },
            placeholder = { Text("Morning news") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text("When", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("daily" to "Every day", "weekdays" to "Weekdays", "days" to "Some days", "every" to "Every few hours", "once" to "Once").forEach { (k, label) ->
                RoutineChip(label, draft.kind == k) { onChange(draft.copy(kind = k)) }
            }
        }
        if (draft.kind == "days") FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_ORDER.forEach { d ->
                RoutineChip(DAY_LETTERS[d], d in draft.days) { onChange(draft.copy(days = if (d in draft.days) draft.days - d else draft.days + d)) }
            }
        }
        if (draft.kind == "every") FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EVERY_HOURS.forEach { h -> RoutineChip(hoursLabel(h), draft.hours == h) { onChange(draft.copy(hours = h)) } }
        }
        if (draft.kind == "once") Row(Modifier.padding(top = 4.dp).offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(draft.copy(date = draft.date.minusDays(1))) }, enabled = draft.date > LocalDate.now()) {
                Icon(Icons.Rounded.ChevronLeft, "Day before")
            }
            Text(dayLabel(draft.date), Modifier.width(120.dp), style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { onChange(draft.copy(date = draft.date.plusDays(1))) }) { Icon(Icons.Rounded.ChevronRight, "Day after") }
        }
        if (draft.kind != "every") OutlinedButton(onClick = { pickTime = true }, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("At %02d:%02d".format(draft.hour, draft.minute))
        }
        error?.let { Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    saving = true
                    error = null
                    val done: (Result<Unit>) -> Unit = { r -> saving = false; r.onSuccess { onDone() }.onFailure { error = it.message } }
                    if (draft.id == null) vm.bots.addRoutine(draft.fields(), done) else vm.bots.updateRoutine(draft.id, draft.fields(), done)
                },
                enabled = draft.ready && !saving,
                shape = MaterialTheme.shapes.small,
            ) { Text(if (draft.id == null) "Add routine" else "Save") }
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(draft.hour, draft.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Time") },
            text = { TimeInput(state) },
            confirmButton = { TextButton(onClick = { pickTime = false; onChange(draft.copy(hour = state.hour, minute = state.minute)) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RoutineChip(text: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text) },
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
)
