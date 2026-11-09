package dev.martin.cubicai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Drafts
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import dev.martin.cubicai.net.BotGroup
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import android.net.Uri
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.bots.LiveTurn
import dev.martin.cubicai.bots.OpenBot
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.net.Bot
import dev.martin.cubicai.net.BotCall
import dev.martin.cubicai.net.BotMessage
import org.json.JSONObject

/** The colours a Bot's cube can have: the app's amber and cobalt first. */
val BOT_COLORS = listOf("#f29e38", "#2f6bff", "#14b8a6", "#4caf50", "#e5484d", "#e0569b", "#8b5cf6", "#64748b")

private data class BotIdea(val name: String, val job: String, val color: String)

/** Starting points, like Grok Bot's "give each Bot a role"; every field can be changed before saving. */
private val IDEAS = listOf(
    BotIdea(
        "Researcher",
        "Dig into the questions I give you. Search the web, read the best sources (not just snippets), and give me a short, clear answer with links. " +
            "For anything bigger, write a report to research/<topic>.md in the workspace and tell me what's in it.",
        "#f29e38",
    ),
    BotIdea(
        "News scout",
        "Keep up with the topics I tell you about. When I ask for news, search for what's new since last time, skip what I've already seen, " +
            "and give me a digest of the five most important items with one line each and a link. Remember my topics and what you've reported.",
        "#2f6bff",
    ),
    BotIdea(
        "Writer",
        "Help me write: drafts, edits, emails, posts. Keep longer pieces as files in writing/ in the workspace and update them when I ask. " +
            "Learn my style and preferences and remember them.",
        "#14b8a6",
    ),
    BotIdea(
        "Coder",
        "Write and run code for me on your computer: scripts, data crunching, file conversions, small tools. Keep projects in code/ in the workspace, " +
            "run what you write to check it works, fix it until it does, and tell me how to use it.",
        "#4caf50",
    ),
    BotIdea(
        "Planner",
        "Turn my goals into plans and checklists. Keep them in plans/ in the workspace, update them as things get done, " +
            "and remind me what's next when I ask. Remember my priorities and deadlines.",
        "#8b5cf6",
    ),
)

private fun providerName(id: String) = Providers.all.firstOrNull { it.id == id }?.shortName ?: id

// --- The list ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenBot: (String) -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.bots.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BotDraft?>(null) }
    var connections by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf(false) }
    // A group being made (null id: new) or changed.
    var groupEdit by remember { mutableStateOf<Pair<Boolean, BotGroup?>?>(null) }
    LaunchedEffect(Unit) { vm.bots.watchList() }
    if (connections) ConnectionsSheet(vm, onDismiss = { connections = false })
    if (skills) SkillsSheet(vm, onDismiss = { skills = false })
    groupEdit?.let { (_, g) ->
        GroupEditSheet(vm, g, onDismiss = { groupEdit = null }, onSaved = { saved -> groupEdit = null; if (g == null) onOpenBot(saved.thread) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Bots") },
                actions = {
                    if (vm.bots.paired) IconButton(onClick = { skills = true }) {
                        val waiting = state.skills.count { !it.approved }
                        BadgedBox(badge = { if (waiting > 0) Badge { Text("$waiting") } }) { Icon(Icons.Rounded.AutoStories, "Skills") }
                    }
                    if (vm.bots.paired) IconButton(onClick = { connections = true }) { Icon(Icons.Rounded.Extension, "Accounts and apps") }
                    if (vm.bots.paired) IconButton(onClick = { editing = BotDraft.blank(vm) }) { Icon(Icons.Rounded.Add, "New Bot") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !vm.bots.paired -> item {
                    Notice(
                        "Bots live on your PC, so they keep working when you close the app. Pair with your PC to make one.",
                        "Open Settings",
                        onOpenSettings,
                    )
                }
                !state.loaded && state.error == null -> item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { Cube(32.dp, spinning = true) }
                }
                !state.loaded -> item { Notice(state.error.orEmpty(), "Try again") { vm.bots.refresh() } }
                else -> {
                    if (state.bots.isEmpty()) item { Intro() }
                    items(state.bots, key = { it.id }) { bot ->
                        BotRow(
                            bot,
                            onOpen = { onOpenBot(bot.id) },
                            onEdit = { editing = BotDraft.of(bot) },
                            onDelete = { vm.bots.delete(bot.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (state.groups.isNotEmpty()) item(key = "groups") {
                        Text(
                            "Group chats",
                            Modifier.padding(top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.groups, key = { "group:" + it.id }) { g ->
                        GroupRow(
                            g, state.bots,
                            onOpen = { onOpenBot(g.thread) },
                            onEdit = { groupEdit = true to g },
                            onDelete = { vm.bots.deleteGroup(g.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (state.bots.size >= 2) item(key = "new-group") {
                        OutlinedButton(
                            onClick = { groupEdit = true to null },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(top = if (state.groups.isEmpty()) 8.dp else 0.dp),
                        ) {
                            Icon(Icons.Rounded.Groups, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("New group chat")
                        }
                    }
                    item(key = "ideas") {
                        Text(
                            if (state.bots.isEmpty()) "Start from an idea" else "More ideas",
                            Modifier.padding(top = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(IDEAS.filter { idea -> state.bots.none { it.name == idea.name } }, key = { "idea:" + it.name }) { idea ->
                        IdeaRow(idea) { editing = BotDraft.blank(vm).copy(name = idea.name, job = idea.job, color = idea.color) }
                    }
                    item(key = "new") {
                        OutlinedButton(
                            onClick = { editing = BotDraft.blank(vm) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("New Bot")
                        }
                    }
                    if (state.error != null) item(key = "error") {
                        Text(state.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    editing?.let { draft ->
        BotEditSheet(vm, draft, onOpenSettings = onOpenSettings, onDismiss = { editing = null }, onSaved = { bot -> editing = null; if (draft.id == null) onOpenBot(bot.id) })
    }
}

@Composable
private fun Intro() {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cube(48.dp)
        Text("Give each Bot a role", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            "Bots live on your PC and keep working when you close the app. Each has its own job, chat and memory; " +
                "they search the web, use a sandboxed computer (terminal and browser), keep files in a shared workspace and can work on a schedule.",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cube(40.dp)
        Text(
            text,
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
    }
}

@Composable
private fun BotRow(bot: Bot, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cube(32.dp, spinning = bot.running, colors = cubeColors(bot.color))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(bot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    bot.running -> ShimmerText("Working")
                    bot.last != null -> Text(
                        (if (bot.lastFromUser) "You: " else "") + bot.last.replace('\n', ' '),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (bot.lastError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text(
                        bot.job.ifBlank { "No job yet" }.replace('\n', ' '),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row {
                    Text(
                        "${displayModelName(bot.model)} · ${providerName(bot.provider)}" +
                            (if (bot.routines > 0) " · ${bot.routines} routine${if (bot.routines == 1) "" else "s"}" else ""),
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bot.pending > 0) Text(
                        " · ${bot.pending} to approve",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options") }
                CubicMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        }
    }
    if (confirm) DeleteBotDialog(bot.name, onDismiss = { confirm = false }, onConfirm = { confirm = false; onDelete() })
}

@Composable
private fun DeleteBotDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = { Text("Its chat and memory are deleted from your PC. Files it saved in the workspace stay.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IdeaRow(idea: BotIdea, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Cube(24.dp, Modifier.padding(top = 2.dp), colors = cubeColors(idea.color))
            Column(Modifier.padding(start = 14.dp)) {
                Text(idea.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    idea.job,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- Making and changing a Bot --------------------------------------------------------------------

/** A Bot being made ([id] null) or changed, as the sheet holds it. */
data class BotDraft(
    val id: String?,
    val name: String,
    val job: String,
    val color: String,
    val provider: String,
    val model: String,
    /** Null until loaded (new Bots start with none). */
    val memory: String?,
    val computer: Boolean = true,
    val google: Boolean = false,
    val phone: Boolean = true,
    val mcp: List<String> = emptyList(),
) {
    companion object {
        /** An existing Bot; its memory (null here) is fetched when the sheet opens. */
        fun of(bot: Bot) = BotDraft(bot.id, bot.name, bot.job, bot.color, bot.provider, bot.model, null, bot.computer, bot.google, bot.phone, bot.mcp)

        /** A new Bot on the chat's model when that can be one, else the PC's. */
        fun blank(vm: ChatViewModel): BotDraft {
            val s = vm.state.value.settings
            val p = s.provider
            val model = s.model(p)
            val (provider, m) = if (model.isNotBlank() && !p.isMultiAgent(model) && (p != Providers.LOCAL || s.isPaired)) p.id to model
            else Providers.LOCAL.id to s.model(Providers.LOCAL)
            return BotDraft(null, "", "", BOT_COLORS.first(), provider, m, "")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BotEditSheet(vm: ChatViewModel, start: BotDraft, onOpenSettings: () -> Unit, onDismiss: () -> Unit, onSaved: (Bot) -> Unit) {
    var draft by remember { mutableStateOf(start) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // An existing Bot's memory is read fresh from the PC (the Bot changes it as it works) and sent back
    // only when changed here, so saving the name never overwrites what it learnt meanwhile.
    var savedMemory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(start.id) {
        val id = start.id ?: return@LaunchedEffect
        vm.bots.memoryOf(id) { r ->
            r.onSuccess { m -> savedMemory = m; if (draft.memory == null) draft = draft.copy(memory = m) }
                .onFailure { error = it.message }
        }
    }
    val canSave = draft.name.isNotBlank() && draft.model.isNotBlank() && !saving
    val bots by vm.bots.state.collectAsStateWithLifecycle()
    var connections by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.bots.loadConnections() }

    fun save() {
        saving = true
        error = null
        val fields = JSONObject()
            .put("name", draft.name.trim())
            .put("job", draft.job.trim())
            .put("color", draft.color)
            .put("provider", draft.provider)
            .put("model", draft.model)
            .put("computer", draft.computer)
            .put("google", draft.google)
            .put("phone", draft.phone)
            .put("mcp", org.json.JSONArray(draft.mcp))
        draft.memory?.let { if (draft.id != null && savedMemory != null && it != savedMemory) fields.put("memory", it) }
        val done: (Result<Bot>) -> Unit = { r ->
            saving = false
            r.onSuccess(onSaved).onFailure { error = it.message ?: "Couldn't save the Bot." }
        }
        if (draft.id == null) vm.bots.create(fields, done) else vm.bots.update(draft.id!!, fields, done)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cube(28.dp, colors = cubeColors(draft.color))
                Text(if (draft.id == null) "New Bot" else "Edit ${start.name}", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it.take(40)) },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.job,
                onValueChange = { draft = draft.copy(job = it.take(8000)) },
                label = { Text("Its job") },
                placeholder = { Text("What it does, how, and what it should always keep in mind") },
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BOT_COLORS.forEach { c ->
                    val selected = c.equals(draft.color, ignoreCase = true)
                    Box(
                        Modifier.size(34.dp)
                            .clip(MaterialTheme.shapes.small)
                            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, MaterialTheme.shapes.small)
                            .clickable { draft = draft.copy(color = c) }
                            .padding(5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Cube(24.dp, colors = cubeColors(c))
                    }
                }
            }
            Text("Model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { picking = true },
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(displayModelName(draft.model).ifBlank { "Pick a model" }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        providerName(draft.provider) + if (draft.provider == Providers.LOCAL.id) " · runs on your PC's GPU" else " · uses your key, kept on your PC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingRow(
                "Computer",
                "A terminal and a browser in a locked-down box on your PC (public websites and the workspace only). It asks you before sending a form.",
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { draft = draft.copy(computer = !draft.computer) }.padding(vertical = 4.dp),
            ) { CutSwitch(draft.computer) }
            val google = bots.google
            SettingRow(
                "Google",
                if (google?.connected == true) "Gmail, Calendar and Drive of ${google.email ?: "your account"}. It reads freely and asks before sending or changing anything."
                else "Not connected. Tap to set it up.",
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable {
                    if (google?.connected == true) draft = draft.copy(google = !draft.google) else connections = true
                }.padding(vertical = 4.dp),
            ) { CutSwitch(draft.google && google?.connected == true) }
            SettingRow(
                "This phone",
                "Reminders that ring here, notifications, copying text and opening links. It can ask where the phone is while the app is open.",
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { draft = draft.copy(phone = !draft.phone) }.padding(vertical = 4.dp),
            ) { CutSwitch(draft.phone) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Apps", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { connections = true }, modifier = Modifier.offset(x = 12.dp), shape = MaterialTheme.shapes.small) { Text("Manage") }
            }
            val apps = bots.apps.orEmpty()
            if (apps.isEmpty()) Text(
                "Tools from other apps (MCP). Add one under Manage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            apps.forEach { app ->
                val on = app.id in draft.mcp
                SettingRow(
                    app.name,
                    if (app.kind == "sandbox") "Runs in the Bots' computer" + if (!app.enabled) " · switched off" else "" else Uri.parse(app.url).host.orEmpty() + if (!app.enabled) " · switched off" else "",
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable {
                        draft = draft.copy(mcp = if (on) draft.mcp - app.id else draft.mcp + app.id)
                    }.padding(vertical = 4.dp),
                ) { CutSwitch(on) }
            }
            if (draft.id != null) {
                OutlinedTextField(
                    value = draft.memory.orEmpty(),
                    onValueChange = { draft = draft.copy(memory = it.take(6000)) },
                    enabled = draft.memory != null,
                    label = { Text("What it remembers") },
                    placeholder = { Text(if (draft.memory == null) "Loading…" else "Nothing yet") },
                    supportingText = { Text("It keeps this up to date itself; change or clear anything here.") },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Pulled in by its inner padding, so "Delete" starts on the sheet's edge.
                if (draft.id != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.offset(x = (-12).dp)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::save, enabled = canSave, shape = MaterialTheme.shapes.small) {
                    if (saving) Cube(18.dp, spinning = true) else Text(if (draft.id == null) "Make Bot" else "Save")
                }
            }
        }
    }
    if (picking) ModelPickerSheet(
        vm,
        onOpenSettings = { onDismiss(); onOpenSettings() },
        onDismiss = { picking = false },
        title = "Model for ${draft.name.ifBlank { "this Bot" }}",
        current = draft.provider to draft.model,
        onPick = { p, m -> draft = draft.copy(provider = p.id, model = m) },
    )
    if (connections) ConnectionsSheet(vm, onDismiss = { connections = false })
    if (confirmDelete) DeleteBotDialog(start.name, onDismiss = { confirmDelete = false }, onConfirm = {
        confirmDelete = false
        draft.id?.let { id -> vm.bots.delete(id) { r -> r.onSuccess { onDismiss() }.onFailure { error = it.message } } }
    })
}

// --- A Bot's chat ---------------------------------------------------------------------------------

/** What the chat shows, oldest first: messages, the Bot's steps and replies, and its turn under way. */
private sealed interface BotRowItem {
    val key: String

    data class User(val m: BotMessage) : BotRowItem { override val key get() = m.id }
    data class RoutineRun(val m: BotMessage) : BotRowItem { override val key get() = m.id }
    data class Handoff(val m: BotMessage) : BotRowItem { override val key get() = m.id }
    data class Taught(val m: BotMessage) : BotRowItem { override val key get() = m.id }
    /** Who answers; [bot] is the speaker in a group. */
    data class Head(override val key: String, val spinning: Boolean, val took: Long, val bot: String? = null) : BotRowItem
    data class Step(val call: BotCall, val result: BotMessage?, override val key: String, val live: Boolean = false) : BotRowItem
    data class Reply(val m: BotMessage, val sources: List<String>) : BotRowItem { override val key get() = m.id }
    data class Failure(val m: BotMessage) : BotRowItem { override val key get() = m.id }
    data class Live(val turn: LiveTurn, val withHead: Boolean) : BotRowItem { override val key get() = "live" }
}

private fun rowsOf(o: OpenBot): List<BotRowItem> {
    val results = o.messages.filter { it.role == "tool" }.associateBy { it.call }
    val rows = mutableListOf<BotRowItem>()
    var headed = false
    var head = -1
    var sources = mutableListOf<String>()
    var speaker: String? = null
    for (m in o.messages) {
        when (m.role) {
            "user" -> {
                rows += when {
                    m.routine != null -> BotRowItem.RoutineRun(m)
                    m.from != null -> BotRowItem.Handoff(m)
                    m.taught.isNotEmpty() -> BotRowItem.Taught(m)
                    else -> BotRowItem.User(m)
                }
                headed = false
                sources = mutableListOf()
            }
            "assistant" -> {
                // In a group each Bot's turn gets its own header.
                if (o.isGroup && m.bot != speaker) {
                    headed = false
                    sources = mutableListOf() // another Bot's sources aren't this one's
                }
                if (!headed && (m.text.isNotBlank() || m.calls.isNotEmpty() || m.error != null || m.stopped || m.reasoning.isNotBlank())) {
                    head = rows.size
                    rows += BotRowItem.Head("head:" + m.id, spinning = false, took = 0, bot = m.bot)
                    headed = true
                    speaker = m.bot
                }
                // The header shows the time over the whole answer: every turn, tool-calling ones too.
                if (headed) (rows[head] as BotRowItem.Head).let { rows[head] = it.copy(took = it.took + m.took) }
                if (m.error != null) rows += BotRowItem.Failure(m)
                else if (m.text.isNotBlank() || m.stopped || (m.reasoning.isNotBlank() && m.calls.isEmpty())) rows += BotRowItem.Reply(m, if (m.calls.isEmpty()) sources.distinct() else emptyList())
                m.calls.forEach { c ->
                    val r = results[c.id]
                    // A call without its result while the Bot works is the one under way.
                    rows += BotRowItem.Step(c, r, "step:" + c.id + ":" + m.id, live = r == null && o.running)
                    r?.citations?.let { sources += it }
                }
            }
        }
    }
    if (o.running || o.live != null) {
        val lastUser = rows.indexOfLast { it is BotRowItem.User || it is BotRowItem.RoutineRun || it is BotRowItem.Handoff || it is BotRowItem.Taught }
        val headIndex = rows.indexOfLast { it is BotRowItem.Head }
        val liveBot = o.live?.bot
        // In a group, the header above is someone else's once another Bot starts.
        val answering = headIndex > lastUser && (!o.isGroup || liveBot == null || (rows[headIndex] as BotRowItem.Head).bot == liveBot)
        if (answering) rows[headIndex] = (rows[headIndex] as BotRowItem.Head).copy(spinning = true, took = 0)
        // The tool under way already shows as its stored step.
        val stored = o.messages.flatMap { m -> m.calls.map { it.id } }.toSet()
        val turn = o.live ?: LiveTurn()
        val stepUnderWay = turn.tool?.id in stored
        val live = if (stepUnderWay) turn.copy(tool = null) else turn
        // While that step works (or waits for the user's yes) there's nothing more to show.
        if (!(stepUnderWay && live.text.isEmpty() && live.reasoning.isEmpty() && answering)) rows += BotRowItem.Live(live, withHead = !answering)
    }
    return rows
}

/** What a tool step says: done, or under way. */
private fun stepLabel(c: BotCall, done: Boolean): Pair<ImageVector, String> {
    val a = runCatching { JSONObject(c.args) }.getOrDefault(JSONObject())
    fun s(k: String) = a.optString(k).trim()
    return when (c.name) {
        "web_search" -> Icons.Rounded.Search to (if (done) "Searched the web for “${s("query")}”" else "Searching the web for “${s("query")}”")
        "read_page" -> Icons.Rounded.Language to (if (done) "Read ${domainOf(s("url"))}" else "Reading ${domainOf(s("url"))}")
        "list_files" -> Icons.Rounded.Folder to (if (done) "Looked in ${s("path").ifBlank { "the workspace" }}" else "Looking in ${s("path").ifBlank { "the workspace" }}")
        "read_file" -> Icons.Rounded.Description to (if (done) "Read ${s("path")}" else "Reading ${s("path")}")
        "write_file" -> Icons.Rounded.EditNote to (if (done) "Saved ${s("path")}" else "Saving ${s("path")}")
        "remember" -> Icons.Rounded.Bookmark to (if (done) "Remembered: ${s("note")}" else "Remembering")
        "forget" -> Icons.Rounded.BookmarkRemove to (if (done) "Forgot notes about “${s("text")}”" else "Forgetting")
        "run_command" -> Icons.Rounded.Terminal to (if (done) "Ran " else "Running ") + "`" + s("command").lineSequence().first().take(80) + "`"
        "browser_open" -> Icons.Rounded.Language to (if (done) "Opened ${domainOf(s("url"))}" else "Opening ${domainOf(s("url"))}")
        "browser_click" -> Icons.Rounded.TouchApp to (if (done) "Clicked ${s("element")}" else "Clicking ${s("element")}")
        "browser_type" -> Icons.Rounded.Keyboard to (if (done) "Typed “${s("text").take(40)}”" else "Typing “${s("text").take(40)}”")
        "browser_scroll" -> Icons.Rounded.SwapVert to (if (done) "Scrolled ${s("direction").ifBlank { "down" }}" else "Scrolling")
        "browser_back" -> Icons.AutoMirrored.Rounded.ArrowBack to (if (done) "Went back" else "Going back")
        "gmail_search" -> Icons.Rounded.Mail to (if (done) "Searched your mail for “${s("query")}”" else "Searching your mail")
        "gmail_read" -> Icons.Rounded.Mail to (if (done) "Read an email" else "Reading an email")
        "gmail_send" -> Icons.AutoMirrored.Rounded.Send to (if (done) "Emailed ${s("to")}" else "Emailing ${s("to")}")
        "gmail_draft" -> Icons.Rounded.Drafts to (if (done) "Drafted an email to ${s("to")}" else "Drafting an email")
        "calendar_events" -> Icons.Rounded.CalendarMonth to (if (done) "Looked at your calendar" else "Looking at your calendar")
        "calendar_create" -> Icons.Rounded.EventAvailable to (if (done) "Added “${s("title")}” to your calendar" else "Adding “${s("title")}”")
        "calendar_update" -> Icons.Rounded.EditCalendar to (if (done) "Changed an event" else "Changing an event")
        "calendar_delete" -> Icons.Rounded.EventBusy to (if (done) "Deleted an event" else "Deleting an event")
        "drive_search" -> Icons.Rounded.FolderOpen to (if (done) "Searched your Drive for “${s("query")}”" else "Searching your Drive")
        "drive_read" -> Icons.Rounded.Description to (if (done) "Read a Drive file" else "Reading a Drive file")
        "drive_download" -> Icons.Rounded.Download to (if (done) "Copied a Drive file to the workspace" else "Copying from Drive")
        "drive_create" -> Icons.Rounded.UploadFile to (if (done) "Put “${s("name")}” in your Drive" else "Putting “${s("name")}” in your Drive")
        "phone_remind" -> Icons.Rounded.Alarm to (if (done) "Set a reminder: ${s("text")}" else "Setting a reminder")
        "phone_notify" -> Icons.Rounded.Notifications to (if (done) "Sent you a notification" else "Sending a notification")
        "phone_copy" -> Icons.Rounded.ContentCopy to (if (done) "Sent text to copy" else "Sending text to copy")
        "phone_open" -> Icons.AutoMirrored.Rounded.OpenInNew to (if (done) "Sent a link: ${domainOf(s("url"))}" else "Sending a link")
        "phone_location" -> Icons.Rounded.LocationOn to (if (done) "Asked where your phone is" else "Asking where your phone is")
        "phone_status" -> Icons.Rounded.BatteryStd to (if (done) "Checked your phone" else "Checking your phone")
        "schedule_routine" -> Icons.Rounded.Schedule to (if (done) "Proposed the routine “${s("name")}”" else "Proposing a routine")
        "cancel_routine" -> Icons.Rounded.EventBusy to (if (done) "Withdrew or switched off “${s("name")}”" else "Cancelling a routine")
        "use_skill" -> Icons.Rounded.AutoStories to (if (done) "Followed the skill /${s("name").removePrefix("/")}" else "Looking up /${s("name").removePrefix("/")}")
        "save_skill" -> Icons.Rounded.School to (if (done) "Proposed the skill /${s("name").removePrefix("/")}" else "Writing a skill")
        "ask_bot" -> Icons.Rounded.Forum to (if (done) "Asked ${s("bot").removePrefix("@")}" else "Asking ${s("bot").removePrefix("@")}")
        else -> if ("__" in c.name) {
            // An app's tool (MCP): app__tool.
            val app = c.name.substringBefore("__")
            val tool = c.name.substringAfter("__").replace('_', ' ').replace('-', ' ')
            Icons.Rounded.Extension to (if (done) "Used $app: $tool" else "Using $app: $tool")
        } else Icons.Rounded.Tune to c.name
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChatScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.bots.state.collectAsStateWithLifecycle()
    val chatState by vm.state.collectAsStateWithLifecycle()
    val open = state.open
    if (open == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val bot = open.bot
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var routinesOpen by remember { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    var groupEditing by remember { mutableStateOf(false) }
    var draft by rememberSaveable(bot.id) { mutableStateOf("") }
    val members = remember(open.group, state.bots) { open.group?.members.orEmpty().mapNotNull { m -> state.bots.firstOrNull { it.id == m } } }
    // Who said something: in a group, the Bot by its id; otherwise this Bot.
    val who: (String?) -> Pair<String, CubeColors> = { id ->
        val b = id?.let { i -> state.bots.firstOrNull { it.id == i } }
        if (b != null) b.name to cubeColors(b.color) else bot.name to cubeColors(bot.color)
    }
    LaunchedEffect(Unit) { vm.bots.watchOpen() }
    val colors = remember(bot.color) { cubeColors(bot.color) }
    // The Bot asked where the phone is: Android's permission first (answered either way).
    val locationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.bots.locationAnswered() }
    LaunchedEffect(open.locationAsk) {
        if (open.locationAsk != null) locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (open.isGroup) GroupCubes(members, 20.dp, spinning = open.running) else Cube(24.dp, spinning = open.running, colors = colors)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (open.isGroup) members.joinToString(", ") { it.name } else "${displayModelName(bot.model)} · ${providerName(bot.provider)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (open.isGroup) IconButton(onClick = { groupEditing = true }) { Icon(Icons.Rounded.Tune, "Edit group") }
                    else {
                        val pending = open.routines.count { !it.approved }
                        IconButton(onClick = { routinesOpen = true }) {
                            BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) { Icon(Icons.Rounded.Schedule, "Routines") }
                        }
                        IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Tune, "Edit Bot") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        CubicMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!open.isGroup) DropdownMenuItem(
                                text = { Text("Teach a task") },
                                leadingIcon = { Icon(Icons.Rounded.School, null) },
                                enabled = bot.computer && !open.loading,
                                onClick = { menu = false; vm.bots.startTeach(bot) },
                            )
                            DropdownMenuItem(
                                text = { Text("Skills") },
                                leadingIcon = { Icon(Icons.Rounded.AutoStories, null) },
                                onClick = { menu = false; skillsOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear chat", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                enabled = !open.running && open.messages.isNotEmpty(),
                                onClick = { menu = false; confirmClear = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    open.loading && open.messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Cube(32.dp, spinning = true, colors = colors)
                    }
                    open.messages.isEmpty() && !open.running -> if (open.isGroup) GroupWelcome(bot.name, members) else BotWelcome(bot)
                    else -> BotMessages(open, chatState.settings.thoughtsOpen, vm::setThoughtsOpen, who) { m -> BotShot(vm, m.bot ?: bot.id, m.id) }
                }
            }
            AnimatedVisibility(
                visible = open.approval != null,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
            ) { open.approval?.let { ActionApproval(if (open.isGroup) who(open.live?.bot).first else bot.name, it.text, onAnswer = vm.bots::answer) } }
            RoutineApprovals(vm, open)
            SkillApprovals(vm, open)
            AnimatedVisibility(
                visible = open.error != null || open.reconnecting,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(100)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (open.reconnecting) ShimmerText("Reconnecting to your PC")
                    else {
                        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Text(
                            open.error.orEmpty(),
                            Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = vm.bots::dismissError) { Text("OK") }
                    }
                }
            }
            // Finishing an @name (groups) or a /skill from what's being typed.
            val word = draft.takeLastWhile { !it.isWhitespace() }
            val suggestions = when {
                open.isGroup && word.startsWith("@") -> members.filter { it.name.replace(" ", "").startsWith(word.drop(1), ignoreCase = true) }.map { "@" + it.name.replace(" ", "") to it.name }
                word.startsWith("/") -> state.skills.filter { it.approved && it.name.startsWith(word.drop(1).lowercase()) }.map { "/" + it.name to it.about }
                else -> emptyList()
            }
            if (suggestions.isNotEmpty()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.take(8).forEach { (insert, hint) ->
                    SuggestionChip(
                        onClick = { draft = draft.dropLast(word.length) + insert + " " },
                        label = { Text(insert + if (hint.isNotBlank() && !insert.startsWith("@")) " · " + hint.take(40) else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = MaterialTheme.shapes.small,
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }
            BotComposer(
                draft = draft,
                onDraft = { draft = it },
                running = open.running,
                enabled = !open.loading,
                placeholder = when {
                    open.running -> "Add something while it works"
                    open.isGroup -> "Message ${bot.name}"
                    else -> "Message ${bot.name}"
                },
                onSend = { vm.bots.send(draft); draft = "" },
                onStop = vm.bots::stop,
            )
        }
    }
    if (routinesOpen) RoutinesSheet(vm, open, onDismiss = { routinesOpen = false })
    if (skillsOpen) SkillsSheet(vm, onDismiss = { skillsOpen = false })
    if (groupEditing) GroupEditSheet(vm, open.group, onDismiss = { groupEditing = false }, onSaved = { groupEditing = false })
    if (state.teaching != null) TeachScreen(vm)
    if (editing) {
        BotEditSheet(vm, BotDraft.of(bot), onOpenSettings = onOpenSettings, onDismiss = { editing = false }, onSaved = { editing = false })
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the chat?") },
        text = {
            Text(
                if (open.isGroup) "The group's messages are deleted from your PC. Each Bot's own chat and memory stay."
                else "${bot.name}'s messages are deleted from your PC. What it remembers and the files it saved stay.",
            )
        },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; vm.bots.clearChat() }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun BotWelcome(bot: Bot) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Cube(56.dp, colors = cubeColors(bot.color))
        Text(bot.name, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        if (bot.job.isNotBlank()) Text(
            bot.job,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "It works on your PC: you can close the app and come back to the result.",
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BotMessages(open: OpenBot, thoughtsOpen: Boolean?, onThoughts: (Boolean) -> Unit, who: (String?) -> Pair<String, CubeColors>, shot: @Composable (BotMessage) -> Unit) {
    val rows = remember(open.messages, open.live, open.running) { rowsOf(open) }
    // Newest at the bottom and kept there while the Bot writes: reverseLayout pins the view to the
    // bottom only while it's there, so each new row (a message, a step, the turn under way) brings it back.
    val listState = rememberLazyListState()
    LaunchedEffect(open.bot.id, rows.size) { listState.animateScrollToItem(0) }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
    ) {
        items(rows.asReversed(), key = { it.key }) { row ->
            Box(Modifier.animateItem(fadeInSpec = tween(200), placementSpec = null, fadeOutSpec = null)) {
                when (row) {
                    is BotRowItem.User -> BotUserBubble(row.m.text)
                    is BotRowItem.RoutineRun -> RoutineRunRow(row.m.routine.orEmpty(), row.m.text)
                    is BotRowItem.Handoff -> HandoffRow(row.m.from.orEmpty(), row.m.text)
                    is BotRowItem.Taught -> TaughtRow(row.m.text, row.m.taught)
                    is BotRowItem.Head -> who(row.bot).let { (name, colors) -> BotHeader(name, colors, row.spinning, null, row.took) }
                    is BotRowItem.Step -> StepRow(row.call, row.result, live = row.live, shot = shot)
                    is BotRowItem.Reply -> Column {
                        if (row.m.reasoning.isNotBlank()) ReasoningBlock(row.m.reasoning, active = false, preferred = thoughtsOpen, onToggle = onThoughts)
                        if (row.m.text.isNotBlank()) MarkdownText(row.m.text)
                        if (row.m.stopped) Text("Stopped", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.sources.isNotEmpty()) Sources(row.sources)
                    }
                    is BotRowItem.Failure -> ErrorCard(row.m.error.orEmpty(), null)
                    is BotRowItem.Live -> who(row.turn.bot).let { (name, colors) -> LiveTurnView(name, colors, row, thoughtsOpen, onThoughts) }
                }
            }
        }
    }
}

/** An empty group chat: who's in it and how it works. */
@Composable
private fun GroupWelcome(name: String, members: List<Bot>) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GroupCubes(members, 40.dp)
        Text(name, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            members.joinToString(", ") { it.name },
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "@mention a Bot to ask it; otherwise the one whose job fits best answers. Bots bring each other in when part of the job is someone else's.",
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A task another Bot handed this one: who asked, and what (folded to a few lines). */
@Composable
private fun HandoffRow(from: String, text: String) {
    var unfolded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { unfolded = !unfolded }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Forum, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$from asked", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text,
            Modifier.padding(start = 24.dp, top = 2.dp).animateContentSize(tween(200)),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (unfolded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A task the user showed the Bot in its browser: what for, and the steps (folded). */
@Composable
private fun TaughtRow(what: String, steps: List<String>) {
    var unfolded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { unfolded = !unfolded }.padding(vertical = 4.dp).animateContentSize(tween(200))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.School, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "You showed it a task · ${steps.size} step${if (steps.size == 1) "" else "s"}",
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(what, Modifier.padding(start = 24.dp, top = 2.dp), style = MaterialTheme.typography.bodyMedium)
        if (unfolded) steps.forEachIndexed { i, st ->
            Text("${i + 1}. $st", Modifier.padding(start = 24.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BotHeader(name: String, colors: CubeColors, spinning: Boolean, phase: String?, took: Long) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cube(16.dp, spinning = spinning, colors = colors)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (phase != null) ShimmerText(phase, Modifier.padding(start = 10.dp))
        else if (took > 0 && !spinning) Text(
            "  ${if (took < 60_000) "%.1f s".format(took / 1000.0) else "${took / 60_000} min ${took % 60_000 / 1000} s"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveTurnView(name: String, colors: CubeColors, row: BotRowItem.Live, thoughtsOpen: Boolean?, onThoughts: (Boolean) -> Unit) {
    val t = row.turn
    val phase = when {
        t.tool != null -> null // the step says what it's doing
        t.text.isNotEmpty() -> "Writing"
        else -> "Thinking"
    }
    Column(Modifier.animateContentSize(tween(180))) {
        if (row.withHead) BotHeader(name, colors, spinning = true, phase = phase, took = 0)
        else if (phase != null && t.text.isEmpty() && t.reasoning.isEmpty()) ShimmerText(phase)
        if (t.reasoning.isNotBlank()) ReasoningBlock(t.reasoning, active = t.text.isEmpty() && t.tool == null, preferred = thoughtsOpen, onToggle = onThoughts)
        if (t.text.isNotEmpty()) MarkdownText(t.text, streaming = true)
        t.tool?.let { StepRow(it, null, live = true) }
    }
}

@Composable
private fun StepRow(call: BotCall, result: BotMessage?, live: Boolean = false, shot: (@Composable (BotMessage) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val (icon, label) = stepLabel(call, done = !live)
    val failed = result?.failed == true
    val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            Modifier.clip(MaterialTheme.shapes.extraSmall)
                .clickable(enabled = result != null) { open = !open }
                .heightIn(min = 32.dp)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            if (live) ShimmerText(label)
            else Text(
                label + when {
                    result?.text?.startsWith("NOT DONE. The user said no") == true -> " (you said no)"
                    failed -> " (failed)"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (result != null && !failed) Icon(Icons.Rounded.Check, null, Modifier.padding(start = 6.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
        }
        // What the browser showed, so you can watch it work.
        if (result?.shot == true && shot != null) shot(result)
        AnimatedVisibility(open && result != null, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(160)) + fadeOut(tween(120))) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    Text(
                        result?.text.orEmpty().trim(),
                        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotUserBubble(text: String) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End) {
        Box {
            val shape = CutCornerShape(topStart = 14.dp, topEnd = 2.dp, bottomEnd = 14.dp, bottomStart = 2.dp)
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clip(shape).combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
            CubicMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { copyToClipboard(context, text); menu = false },
                )
            }
        }
    }
}

@Composable
private fun BotComposer(
    draft: String,
    onDraft: (String) -> Unit,
    running: Boolean,
    enabled: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // Held with its cursor, so text put in from outside (a suggestion) leaves the cursor at its end.
            var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(draft)) }
            if (field.text != draft) field = androidx.compose.ui.text.input.TextFieldValue(draft, androidx.compose.ui.text.TextRange(draft.length))
            TextField(
                value = field,
                onValueChange = { field = it; onDraft(it.text) },
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = 6,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            // Typing while it works sends the message on (it's answered after the current reply); otherwise Stop.
            val mode = when {
                draft.isNotBlank() -> 1
                running -> 2
                else -> 0
            }
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                label = "send",
                modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
            ) { m ->
                when (m) {
                    2 -> FilledIconButton(onClick = onStop, shape = MaterialTheme.shapes.small) { Icon(Icons.Rounded.Stop, "Stop") }
                    else -> FilledIconButton(onClick = onSend, enabled = m == 1 && enabled, shape = MaterialTheme.shapes.small) {
                        Icon(Icons.Rounded.ArrowUpward, "Send")
                    }
                }
            }
        }
    }
}

/** The Bot waits for a yes before doing something that sends data (a form on a website). */
@Composable
private fun ActionApproval(name: String, text: String, onAnswer: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PanTool, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("$name asks first", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(text, Modifier.padding(top = 6.dp, end = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAnswer(false) }, shape = MaterialTheme.shapes.small) { Text("Don't") }
                TextButton(onClick = { onAnswer(true) }, shape = MaterialTheme.shapes.small) { Text("Allow") }
            }
        }
    }
}

/** A browser step's screenshot; tap for the whole page view. */
@Composable
private fun BotShot(vm: ChatViewModel, botId: String, messageId: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, messageId) { value = vm.bots.shot(botId, messageId) }
    var full by remember { mutableStateOf(false) }
    val b = bitmap ?: return
    val image = remember(b) { b.asImageBitmap() }
    Image(
        image,
        "What the browser showed",
        Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(max = 200.dp).clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).clickable { full = true },
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
    )
    if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable { full = false }.padding(16.dp), contentAlignment = Alignment.Center) {
            Image(image, "What the browser showed", Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium), contentScale = ContentScale.FillWidth)
        }
    }
}
