package dev.martin.cubicai.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.Role
import dev.martin.cubicai.data.SearchMode
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showModels by remember { mutableStateOf(false) }
    var showBehaviour by remember { mutableStateOf(false) }

    // An open keyboard would cover the drawer's Settings entry.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }

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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    shortModelName(state.settings.model(provider)).ifBlank { "Pick a model" },
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
                    if (state.current.messages.isEmpty()) EmptyState(state, onSuggestion = vm::send, onOpenSettings = onOpenSettings, onBehaviour = { showBehaviour = true })
                    else MessageList(state, vm)
                }
                Composer(state, vm)
            }
        }
    }

    if (showModels) ModelPickerSheet(vm, onOpenSettings = onOpenSettings, onDismiss = { showModels = false })
    if (showBehaviour) BehaviourSheet(vm, onDismiss = { showBehaviour = false })
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
                Box(Modifier.animateItem(fadeInSpec = tween(220), placementSpec = tween(220), fadeOutSpec = tween(120))) {
                    if (message.role == Role.USER) {
                        UserMessage(message, canEdit = !state.streaming) { vm.editMessage(message.id, it) }
                    } else {
                        AssistantMessage(
                            message = message,
                            streaming = state.streaming && isLast,
                            status = state.status,
                            fallbackName = shortModelName(message.model.orEmpty()),
                            isLast = isLast,
                            onRegenerate = vm::regenerate,
                            onSpeak = vm::speak,
                        )
                    }
                }
            }
        }
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
private fun EmptyState(state: UiState, onSuggestion: (String) -> Unit, onOpenSettings: () -> Unit, onBehaviour: () -> Unit) {
    val provider = state.settings.provider
    val needsKey = when (provider) {
        Providers.LOCAL -> !state.settings.isPaired
        Providers.CUSTOM -> false
        else -> state.settings.key(provider).isBlank()
    }
    val persona = state.settings.personas.firstOrNull { it.id == state.current.personaId }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Cube(56.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            if (needsKey) "One step left" else "Ask anything, or add a photo or file",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        if (needsKey) {
            Text(
                if (provider == Providers.LOCAL) "Pair with your PC to chat with your own models over home Wi-Fi or Tailscale."
                else "Add your ${provider.name} API key in Settings, or pair with your PC to use local models.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(shape = MaterialTheme.shapes.small, onClick = onOpenSettings) { Text("Open settings") }
        } else {
            AssistChip(
                onClick = onBehaviour,
                label = { Text("Behaviour: ${persona?.name ?: "None"}") },
                leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
            )
            Spacer(Modifier.height(24.dp))
            listOf(
                "Summarise today's top news",
                "Explain how vaccines train the immune system",
                "Help me write a polite complaint email",
                "Plan a 3-day trip to Munnar",
            ).forEachIndexed { i, prompt ->
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { delay(70L * i); shown = true }
                AnimatedVisibility(shown, enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 5 }) {
                    Text(
                        prompt,
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onSuggestion(prompt) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(state: UiState, vm: ChatViewModel) {
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
        try { camera.launch(uri) } catch (_: ActivityNotFoundException) { }
    }

    fun launchSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try { speech.launch(intent) } catch (_: ActivityNotFoundException) { }
    }

    val canSend = (state.draft.isNotBlank() || state.pending.isNotEmpty()) && !state.attaching

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
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
                placeholder = { Text(if (state.imageMode) "Describe the image you want" else "Ask anything") },
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
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Rounded.Add, "Attach") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
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
                if (state.settings.provider.search != SearchMode.NONE) {
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
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp),
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
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clickable { vm.removePending(a) },
                ) {
                    Icon(Icons.Rounded.Close, "Remove", Modifier.padding(3.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
        if (state.attaching) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ChatDrawer(
    chats: List<Chat>,
    currentId: String,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            CubeMark()
            Spacer(Modifier.width(12.dp))
            Text("CubicAI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Rounded.AddBox, null) },
            selected = false,
            onClick = onNew,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (chats.isNotEmpty()) Text(
            "Recent",
            Modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(chats, key = { it.id }) { chat ->
                var menu by remember { mutableStateOf(false) }
                NavigationDrawerItem(
                    label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = chat.id == currentId,
                    onClick = { onOpen(chat.id) },
                    badge = {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.MoreVert, "Options", Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { menu = false; onDelete(chat.id) },
                                )
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Rounded.Settings, null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(12.dp),
        )
        Spacer(Modifier.width(1.dp))
    }
}
