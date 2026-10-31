package dev.martin.cubicai.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.UiState
import dev.martin.cubicai.data.Chat
import dev.martin.cubicai.data.Personas
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.Role
import dev.martin.cubicai.data.SearchMode
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showModels by remember { mutableStateOf(false) }
    var showBehaviour by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }

    // An open keyboard would cover the drawer's Settings entry.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }

    // Back closes the drawer first instead of leaving the app.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                chats = state.chats,
                currentId = state.current.id,
                open = drawerState.targetValue == DrawerValue.Open,
                onNew = { vm.newChat(); scope.launch { drawerState.close() } },
                onOpen = { vm.openChat(it); scope.launch { drawerState.close() } },
                onDelete = vm::deleteChat,
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Rounded.Menu, "Chats") }
                    },
                    title = {
                        val provider = state.settings.provider
                        Row(
                            Modifier.clip(MaterialTheme.shapes.small).clickable { showModels = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Takes what's left after the arrow, so a long name is cut short instead of pushing it out.
                            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    displayModelName(state.settings.model(provider)).ifBlank { "Pick a model" },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(provider.shortName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(20.dp))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBehaviour = true }) { Icon(Icons.Rounded.Tune, "Behaviour") }
                        IconButton(onClick = vm::newChat) { Icon(Icons.Rounded.AddBox, "New chat") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The welcome screen hands over to the conversation with a short fade instead of a hard cut.
                    Crossfade(state.current.messages.isEmpty(), animationSpec = tween(220), label = "emptyState") { empty ->
                        if (empty) EmptyState(
                            state,
                            onSuggestion = vm::sendSuggestion,
                            onOpenSettings = onOpenSettings,
                            onBehaviour = { showBehaviour = true },
                            onCreate = { showCreate = true },
                            onIdea = vm::setDraft,
                        )
                        else MessageList(state, vm)
                    }
                }
                Composer(state, vm, onCreate = { showCreate = true })
            }
        }
    }

    if (showModels) ModelPickerSheet(vm, onOpenSettings = onOpenSettings, onDismiss = { showModels = false })
    if (showBehaviour) BehaviourSheet(vm, onDismiss = { showBehaviour = false })
    if (showCreate) CreateSheet(vm) { showCreate = false }
}

@Composable
private fun MessageList(state: UiState, vm: ChatViewModel) {
    val messages = state.current.messages
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // reverseLayout keeps the view pinned to the newest message while it streams in.
    LaunchedEffect(state.current.id, messages.size) { listState.animateScrollToItem(0) }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(messages.asReversed(), key = { it.id }) { message ->
                val isLast = message.id == messages.last().id
                // Fade only: a placement animation slid the bubble above over the reply header while the reply grew.
                Box(Modifier.animateItem(fadeInSpec = tween(220), placementSpec = null, fadeOutSpec = tween(120))) {
                    if (message.role == Role.USER) {
                        UserMessage(message, canEdit = !state.streaming) { vm.editMessage(message.id, it) }
                    } else {
                        AssistantMessage(
                            message = message,
                            streaming = state.streaming && isLast,
                            status = if (state.streaming && isLast) state.status else null, // only the reply in progress
                            fallbackName = displayModelName(message.model.orEmpty()),
                            isLast = isLast,
                            onRegenerate = vm::regenerate,
                            onSpeak = vm::speak,
                            progress = state.mediaProgress[message.id],
                            onSave = { vm.saveMedia(message) },
                            // A face only means something for a character; a chat without a behaviour has none.
                            onUseAsFace = if (state.current.personaId == Personas.NONE) null else ({ vm.useAsFace(message) }),
                            onEditPrompt = { vm.remakeWithPrompt(message, it) },
                            onAnswerVideo = { vm.answerVideo(message, it) },
                            thoughtsOpen = state.settings.thoughtsOpen,
                            onThoughtsToggle = vm::setThoughtsOpen,
                        )
                    }
                }
            }
        }
        // Text scrolling up fades out under the top bar instead of being cut off at a hard edge.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(14.dp)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, Color.Transparent))),
        )
        AnimatedVisibility(
            visible = !atBottom,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.7f),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.7f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                shape = MaterialTheme.shapes.small,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) { Icon(Icons.Rounded.KeyboardArrowDown, "Jump to latest") }
        }
    }
}

@Composable
private fun EmptyState(
    state: UiState,
    onSuggestion: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBehaviour: () -> Unit,
    onCreate: () -> Unit,
    onIdea: (String) -> Unit,
) {
    val provider = state.settings.provider
    val needsKey = when (provider) {
        Providers.LOCAL -> !state.settings.isPaired
        Providers.CUSTOM -> false
        else -> state.settings.key(provider).isBlank()
    }
    val persona = state.settings.personas.firstOrNull { it.id == state.current.personaId }
    val create = state.create?.takeIf { provider == Providers.LOCAL && !needsKey }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Cube(56.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            when {
                needsKey -> "One step left"
                create != null -> "What should your PC make?"
                else -> "How can I help?"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (needsKey) {
            Text(
                if (provider == Providers.LOCAL) "Pair with your PC to chat with your own models over home Wi-Fi or Tailscale."
                else "Add your ${provider.name} API key in Settings, or pair with your PC to use local models.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(shape = MaterialTheme.shapes.small, onClick = onOpenSettings) { Text("Open settings") }
        } else if (create != null) {
            // Creating: what's chosen, a way to change it, and prompt ideas that fill the box (a run takes a while,
            // so nothing is sent until the user has looked at it).
            val g = create.generator
            val photo = when {
                state.pending.any { it.isImage } -> ""
                g.image == "required" -> if (g.prompt) " Add a photo with +, then describe what to do." else " Add a photo with +, then tap send."
                g.image == "optional" -> " Add a photo with + to start from it."
                else -> if (g.audio == "optional") " Add a voice recording with + to speak in that voice." else ""
            }
            Text("${g.note}.$photo", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            AssistChip(
                onClick = onCreate,
                label = { Text(createSummary(create)) },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) },
            )
            Spacer(Modifier.height(24.dp))
            Suggestions(createIdeas(g), onIdea)
        } else {
            Text("Ask anything, or add a photo or file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            AssistChip(
                onClick = onBehaviour,
                label = { Text("Behaviour: ${persona?.name ?: "None"}") },
                leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
            )
            Spacer(Modifier.height(24.dp))
            Suggestions(
                listOf(
                    "Summarise today's top news",
                    "Explain how vaccines train the immune system",
                    "Help me write a polite complaint email",
                    "Plan a 3-day trip to Munnar",
                ),
                onSuggestion,
            )
        }
    }
}

@Composable
private fun Suggestions(prompts: List<String>, onClick: (String) -> Unit) {
    // Keyed by the list so switching generator replays the fade-in.
    key(prompts) {
        prompts.forEachIndexed { i, prompt ->
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(70L * i); shown = true }
            AnimatedVisibility(shown, enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 5 }) {
                Text(
                    prompt,
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onClick(prompt) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun Composer(state: UiState, vm: ChatViewModel, onCreate: () -> Unit) {
    val context = LocalContext.current
    var attachMenu by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { vm.addAttachments(listOf(it)) }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.addAttachments(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addAttachments(it) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            vm.setDraft((state.draft + " " + it).trim())
        }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "${System.currentTimeMillis()}.jpg"))
        cameraUri = uri
        try { camera.launch(uri) } catch (_: ActivityNotFoundException) { vm.showNotice("No camera app found") }
    }

    fun launchSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try { speech.launch(intent) } catch (_: ActivityNotFoundException) { vm.showNotice("Voice input isn't available on this phone") }
    }

    // Creating on the PC is offered while the PC is the chosen provider.
    val create = state.create?.takeIf { state.settings.provider == Providers.LOCAL }
    // A generator that needs a photo can also work on the latest picture already in the chat.
    val chatPhoto = state.current.messages.any { m -> m.attachments.any { it.isImage } }
    val hasPhoto = state.pending.any { it.isImage } || (create?.generator?.image == "required" && chatPhoto)
    val canSend = !state.attaching && if (create != null) {
        (!create.generator.prompt || state.draft.isNotBlank()) && (create.generator.image != "required" || hasPhoto)
    } else state.draft.isNotBlank() || state.pending.isNotEmpty()

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), // same 16dp edge as the messages
    ) {
        Column(Modifier.animateContentSize(tween(180))) {
            AnimatedVisibility(
                visible = state.pending.isNotEmpty() || state.attaching,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(100)),
            ) { PendingAttachments(state, vm) }
            TextField(
                value = state.draft,
                onValueChange = vm::setDraft,
                placeholder = {
                    Text(
                        when {
                            create != null -> createPlaceholder(create.generator, hasPhoto, usesLast = hasPhoto && state.pending.none { it.isImage })
                            state.imageMode -> "Describe the image you want"
                            else -> "Ask anything"
                        },
                    )
                },
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            // 4dp + the buttons' 12dp inner padding puts the + and mic icons on the text field's 16dp edges.
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Rounded.Add, "Attach") }
                    CubicMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Camera") },
                            leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                            onClick = { attachMenu = false; launchCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text("Photos") },
                            leadingIcon = { Icon(Icons.Rounded.Image, null) },
                            onClick = {
                                attachMenu = false
                                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Files") },
                            leadingIcon = { Icon(Icons.Rounded.AttachFile, null) },
                            onClick = { attachMenu = false; files.launch(arrayOf("*/*")) },
                        )
                    }
                }
                if (state.settings.provider.search != SearchMode.NONE && create == null) {
                    FilterChip(
                        selected = state.settings.webSearch,
                        onClick = vm::toggleWebSearch,
                        label = { Text("Search") },
                        leadingIcon = { Icon(Icons.Rounded.Language, null, Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                if (state.settings.provider == Providers.LOCAL && state.settings.isPaired) {
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = create != null,
                        onClick = onCreate,
                        label = { Text(create?.generator?.name ?: "Create", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.widthIn(max = 230.dp),
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                if (state.settings.provider.images) {
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = state.imageMode,
                        onClick = vm::toggleImageMode,
                        label = { Text("Image") },
                        leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.current.messages.isNotEmpty() && create == null) ContextMeter(state.current.messages, state.contextLimit)
                AnimatedContent(
                    targetState = when { state.streaming -> 2; canSend -> 1; else -> 0 },
                    transitionSpec = { (fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.8f)) togetherWith fadeOut(tween(80)) },
                    label = "action",
                ) { mode ->
                    when (mode) {
                        2 -> FilledIconButton(onClick = vm::stop, shape = MaterialTheme.shapes.small) { Icon(Icons.Rounded.Stop, "Stop") }
                        1 -> FilledIconButton(onClick = { vm.send() }, shape = MaterialTheme.shapes.small) { Icon(Icons.Rounded.ArrowUpward, "Send") }
                        else -> IconButton(onClick = ::launchSpeech) { Icon(Icons.Rounded.Mic, "Voice input") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingAttachments(state: UiState, vm: ChatViewModel) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp), // on the text's 16dp edge
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.pending.forEach { a ->
            Box {
                if (a.isImage) LocalImage(a.path, Modifier.size(64.dp).clip(MaterialTheme.shapes.small))
                else FileChip(a.name, Modifier.padding(top = 8.dp, end = 8.dp))
                Surface(
                    shape = CutCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                ) {
                    Icon(Icons.Rounded.Close, "Remove", Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                }
                Box(Modifier.align(Alignment.TopEnd).size(48.dp).offset(x = 12.dp, y = (-12).dp).clickable { vm.removePending(a) })
            }
        }
        if (state.attaching) Cube(24.dp, spinning = true)
    }
}

@Composable
private fun ChatDrawer(
    chats: List<Chat>,
    currentId: String,
    open: Boolean,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        // 24dp mark at the items' icon column, so "CubicAI" starts where the item labels do.
        Row(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Cube(24.dp)
            Spacer(Modifier.width(12.dp))
            Text("CubicAI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        NavigationDrawerItem(
            shape = MaterialTheme.shapes.small,
            label = { Text("New chat") },
            icon = { Icon(Icons.Rounded.AddBox, null) },
            selected = false,
            onClick = onNew,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        // Search appears once the list is long enough to need it, and clears when the drawer closes,
        // so a forgotten filter never hides chats next time.
        var query by remember { mutableStateOf("") }
        val focus = LocalFocusManager.current
        LaunchedEffect(open) {
            if (!open) {
                query = ""
                focus.clearFocus() // otherwise the keyboard stays up over the chat that was just opened
            }
        }
        if (chats.size >= 6 || query.isNotEmpty()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search chats") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        val today = remember { LocalDate.now() }
        val shown = remember(chats, query) {
            val q = query.trim()
            // Matches from the start of a word, so "tea" finds "tea" and "teaspoon" but not "steam".
            val match = Regex("\\b" + Regex.escape(q), RegexOption.IGNORE_CASE)
            if (q.isEmpty()) chats
            else chats.filter { c -> match.containsMatchIn(c.title) || c.messages.any { match.containsMatchIn(it.content) } }
        }
        val groups = remember(shown) { shown.groupBy { dateGroup(it.updatedAt, today) } }
        if (shown.isEmpty() && query.isNotBlank()) {
            Text(
                "No chats match \u201c${query.trim()}\u201d",
                Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            groups.forEach { (label, group) ->
                item(key = "group:$label") {
                    Text(
                        label,
                        Modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(group, key = { it.id }) { chat ->
                    var menu by remember { mutableStateOf(false) }
                    NavigationDrawerItem(
                        shape = MaterialTheme.shapes.small,
                        label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = chat.id == currentId,
                        onClick = { onOpen(chat.id) },
                        badge = {
                            Box {
                                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Rounded.MoreVert, "Options", Modifier.size(18.dp))
                                }
                                CubicMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; onDelete(chat.id) },
                                    )
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        NavigationDrawerItem(
            shape = MaterialTheme.shapes.small,
            label = { Text("Settings") },
            icon = { Icon(Icons.Rounded.Settings, null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(12.dp),
        )
        Spacer(Modifier.width(1.dp))
    }
}

/** Drawer section for a chat last touched at [time]. */
private fun dateGroup(time: Long, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate(), today)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "Previous 7 days"
        days < 30 -> "Previous 30 days"
        else -> "Older"
    }
}
