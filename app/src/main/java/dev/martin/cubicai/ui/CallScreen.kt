package dev.martin.cubicai.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.StopScreenShare
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.call.CallPhase
import dev.martin.cubicai.call.CallState
import dev.martin.cubicai.call.CameraFrames
import dev.martin.cubicai.data.Personas
import dev.martin.cubicai.net.VoiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** Starts a call in the open chat, asking for the microphone first if needed. */
@Composable
fun rememberCallStarter(vm: ChatViewModel): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startCall() else vm.showNotice("A call needs the microphone.")
    }
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startCall()
        else ask.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
fun CallScreen(vm: ChatViewModel) {
    val call by vm.call.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val persona = state.settings.personas.firstOrNull { it.id == state.current.personaId } ?: Personas.builtIn.first()
    var showVoices by remember { mutableStateOf(false) }
    var showBehaviour by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }

    BackHandler { vm.call.setMinimized(true) }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.call.setCamera(true) else vm.showNotice("Video needs the camera.")
    }
    val screenConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) vm.call.shareScreen(result.resultCode, data)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (call.camera) {
            CameraPreview(call.backCamera, Modifier.fillMaxSize())
            // Scrims keep the controls and captions readable over the picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.55f), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f),
            )))
        }
        val onPicture = call.camera
        val ink = if (onPicture) Color.White else MaterialTheme.colorScheme.onBackground
        val soft = if (onPicture) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Top: back to the chat, who you're talking to and for how long, the call's settings.
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.call.setMinimized(true) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Show the chat", tint = ink) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (persona.id == Personas.NONE) "CubicAI" else persona.name,
                        style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            elapsed(call.startedAt),
                            displayModelName(state.settings.model(state.settings.provider)),
                            "free on your PC".takeIf { state.settings.provider == dev.martin.cubicai.data.Providers.LOCAL },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { showOptions = true }) { Icon(Icons.Rounded.Tune, "Call settings", tint = ink) }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (onPicture) Arrangement.Bottom else Arrangement.Center,
            ) {
                if (!onPicture) {
                    LiveCube(vm, 132.dp, spinning = call.phase == CallPhase.THINKING || call.phase == CallPhase.CONNECTING || call.making)
                    Spacer(Modifier.height(28.dp))
                }
                PhaseLabel(call, ink, soft)
                if (call.screen) ScreenNote(onStop = vm.call::stopSharing)
                if (state.settings.callCaptions) Captions(call, ink, soft)
            }

            // Behaviour and voice, the two things you change most mid-call.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                CallChip(Icons.Rounded.Face, persona.name, onPicture, adult = persona.adult) { showBehaviour = true }
                val voiceId = state.settings.callVoice(persona)
                CallChip(Icons.Rounded.RecordVoiceOver, call.voices.firstOrNull { it.id == voiceId }?.name ?: "Voice", onPicture) { showVoices = true }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                CallButton(if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (call.muted) "Unmute" else "Mute", on = call.muted, ink, onClick = vm.call::toggleMute)
                CallButton(if (call.camera) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, "Video", on = call.camera, ink) {
                    when {
                        call.camera -> vm.call.setCamera(false)
                        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> vm.call.setCamera(true)
                        else -> cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
                CallButton(
                    if (call.screen) Icons.AutoMirrored.Rounded.StopScreenShare else Icons.AutoMirrored.Rounded.ScreenShare,
                    "Screen", on = call.screen, ink,
                ) {
                    if (call.screen) vm.call.stopSharing()
                    else context.getSystemService(MediaProjectionManager::class.java)?.let { screenConsent.launch(it.createScreenCaptureIntent()) }
                }
                if (call.camera) CallButton(Icons.Rounded.Cameraswitch, "Flip", on = false, ink, onClick = vm.call::flipCamera)
                else CallButton(if (call.speaker) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.Hearing, if (call.speaker) "Speaker" else "Earpiece", on = call.speaker, ink, onClick = vm.call::toggleSpeaker)
                CallButton(Icons.Rounded.CallEnd, "End", on = false, ink, danger = true, onClick = vm::endCall)
            }
        }
    }

    if (showVoices) VoiceSheet(vm, onDismiss = { showVoices = false })
    if (showBehaviour) BehaviourSheet(vm, onDismiss = { showBehaviour = false })
    if (showOptions) CallOptionsSheet(vm, onDismiss = { showOptions = false })
}

/** The call while the chat is on screen: tap to go back to it. */
@Composable
fun CallBar(vm: ChatViewModel) {
    val call by vm.call.state.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.call.setMinimized(false) }.statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cube(18.dp, spinning = call.phase == CallPhase.THINKING || call.making)
            Spacer(Modifier.width(10.dp))
            Text(
                "On a call · ${elapsed(call.startedAt)} · ${phaseText(call)}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = vm.call::toggleMute) { Icon(if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (call.muted) "Unmute" else "Mute") }
            IconButton(onClick = vm::endCall) { Icon(Icons.Rounded.CallEnd, "End call", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun phaseText(call: CallState) = when {
    call.phase == CallPhase.CONNECTING -> call.status ?: "Connecting"
    call.muted -> "Muted"
    call.making && call.phase == CallPhase.LISTENING -> "Making it on your PC"
    call.phase == CallPhase.THINKING && call.working != null -> call.working
    else -> call.phase.label
}

@Composable
private fun elapsed(since: Long): String {
    val now by produceState(System.currentTimeMillis(), since) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun PhaseLabel(call: CallState, ink: Color, soft: Color) {
    val text = phaseText(call)
    val busy = call.phase == CallPhase.THINKING || call.phase == CallPhase.CONNECTING || (call.making && call.phase == CallPhase.LISTENING)
    AnimatedContent(text to busy, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "phase") { (label, shimmer) ->
        if (shimmer) ShimmerText(label.trimEnd('.', '…'))
        else Text(label, style = MaterialTheme.typography.titleMedium, color = if (call.phase == CallPhase.HEARING) MaterialTheme.colorScheme.primary else ink)
    }
    if (call.phase == CallPhase.CONNECTING && call.status != null && call.status.length > 40) {
        Text(call.status, style = MaterialTheme.typography.bodySmall, color = soft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Captions(call: CallState, ink: Color, soft: Color) {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 120.dp).animateContentSize(tween(180)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (call.you.isNotBlank()) Text(
            call.you, style = MaterialTheme.typography.bodyMedium, color = soft, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (call.them.isNotBlank()) Text(
            call.them, style = MaterialTheme.typography.bodyLarge, color = ink, textAlign = TextAlign.Center,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun ScreenNote(onStop: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(top = 16.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Sharing your screen. Go to any app and talk: it sees the screen each time you speak.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onStop, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
        }
    }
}

@Composable
private fun CallChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onPicture: Boolean, adult: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (onPicture) Color.Black.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (onPicture) Color.White else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            if (adult) AdultTag(Modifier.padding(start = 6.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    ink: Color,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val colors = MaterialTheme.colorScheme
        if (danger) FilledIconButton(
            onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.error, contentColor = colors.onError),
        ) { Icon(icon, label) }
        else FilledTonalIconButton(
            onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (on) colors.primary else colors.surfaceContainerHighest,
                contentColor = if (on) colors.onPrimary else colors.onSurface,
            ),
        ) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The cube, breathing with whoever is talking: it swells with the voice, and two hexagonal rings ripple out. */
@Composable
private fun LiveCube(vm: ChatViewModel, size: Dp, spinning: Boolean) {
    val level = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val target = maxOf(vm.call.voiceLevel, vm.call.micLevel * 0.7f)
            level.floatValue += (target - level.floatValue) * 0.25f
        }
    }
    val ring = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size * 1.9f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val l = level.floatValue
            val base = this.size.minDimension / 2f
            for (i in 0..1) {
                val r = base * (0.55f + l * 0.25f + i * (0.12f + l * 0.12f))
                val alpha = ((0.18f + l * 0.5f) * (1f - i * 0.45f)).coerceIn(0f, 1f)
                drawPath(hexagon(center, r), ring.copy(alpha = alpha), style = Stroke(width = (2.dp.toPx() * (1f - i * 0.3f))))
            }
        }
        Cube(size, Modifier.graphicsLayer {
            val scale = 1f + level.floatValue * 0.1f
            scaleX = scale
            scaleY = scale
        }, spinning = spinning)
    }
}

private fun hexagon(c: Offset, r: Float) = Path().apply {
    for (i in 0 until 6) {
        val a = Math.toRadians(-90.0 + 60.0 * i)
        val x = c.x + r * cos(a).toFloat()
        val y = c.y + r * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The camera's live picture; its current frame goes to the model with each thing the user says. */
@Composable
private fun CameraPreview(back: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // so the frame can be read back
        }
    }
    DisposableEffect(back, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            runCatching {
                provider?.unbindAll()
                val wanted = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val other = if (back) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                // A phone with one camera keeps using it when flipped.
                provider?.bindToLifecycle(owner, if (provider?.hasCamera(wanted) == true) wanted else other, preview)
            }
        }, ContextCompat.getMainExecutor(context))
        CameraFrames.grab = { view.bitmap }
        onDispose {
            CameraFrames.grab = null
            provider?.unbindAll()
        }
    }
    AndroidView({ view }, modifier)
}

// --- Voices and call settings ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val persona = state.settings.personas.firstOrNull { it.id == state.current.personaId } ?: Personas.builtIn.first()
    val chosen = state.settings.callVoice(persona)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<String?>(null) }
    val voices by produceState<Result<List<VoiceInfo>>?>(null) { value = runCatching { vm.call.voices() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val edge = Modifier.padding(horizontal = 20.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("Voice", style = MaterialTheme.typography.titleLarge, modifier = edge)
            Text(
                "How ${if (persona.id == Personas.NONE) "the assistant" else persona.name} sounds in calls. Each behaviour keeps its own. Tap ▶ to hear one.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = edge,
            )
            Spacer(Modifier.height(12.dp))
            val result = voices
            when {
                result == null -> Row(edge.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cube(18.dp, spinning = true)
                    Spacer(Modifier.width(10.dp))
                    Text("Asking your PC…", style = MaterialTheme.typography.bodyMedium)
                }
                result.isFailure -> Text(
                    result.exceptionOrNull()?.message ?: "Couldn't reach your PC.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = edge.padding(vertical = 16.dp),
                )
                else -> result.getOrThrow().groupBy { it.accent }.forEach { (accent, list) ->
                    Text(accent, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = edge.padding(top = 8.dp, bottom = 4.dp))
                    list.forEach { v ->
                        val on = v.id == chosen
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (on) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                                .clickable { vm.call.setVoice(v.id) }
                                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val tone = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            Box(
                                Modifier.padding(end = 16.dp).size(18.dp).border(2.dp, tone, CutCornerShape(5.dp)).padding(3.dp)
                                    .background(if (on) tone else Color.Transparent, CutCornerShape(2.dp)),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(v.name, style = MaterialTheme.typography.titleSmall)
                                Text("${v.about} · ${v.gender}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                playing = v.id
                                scope.launch {
                                    runCatching { playPcm(context, vm.call.preview(v.id), vm.call.active) }
                                        .onFailure { vm.showNotice(it.message ?: "Couldn't play the voice.") }
                                    if (playing == v.id) playing = null
                                }
                            }) {
                                if (playing == v.id) Cube(18.dp, spinning = true) else Icon(Icons.Rounded.PlayArrow, "Hear ${v.name}")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallOptionsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Call settings", style = MaterialTheme.typography.titleLarge)
            CallOptions(vm)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** How calls behave: shared by the call's settings sheet and Settings → Voice calls. */
@Composable
fun CallOptions(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val shape = MaterialTheme.shapes.small
    fun toggle(value: Boolean, set: (Boolean) -> Unit) = Modifier.clip(shape).toggleable(value, role = Role.Switch) { set(it) }

    SettingRow(
        "Check-ins",
        "When you go quiet, it speaks up with a question or a thought (twice at most, then it waits for you).",
        toggle(s.callCheckIns) { v -> vm.updateSettings { it.copy(callCheckIns = v) } },
    ) { CutSwitch(s.callCheckIns) }
    AnimatedVisibility(s.callCheckIns) {
        Choice(listOf(15 to "15 s", 30 to "30 s", 60 to "1 min", 120 to "2 min"), s.callCheckInSeconds) { v ->
            vm.updateSettings { it.copy(callCheckInSeconds = v) }
        }
    }
    SettingRow(
        "Make things",
        "Ask for a picture, a song or a video while you talk, and it's made on your PC.",
        toggle(s.callMakesThings) { v -> vm.updateSettings { it.copy(callMakesThings = v) } },
    ) { CutSwitch(s.callMakesThings) }
    SettingRow(
        "Captions",
        "Shows what you and the voice say, as it's said.",
        toggle(s.callCaptions) { v -> vm.updateSettings { it.copy(callCaptions = v) } },
    ) { CutSwitch(s.callCaptions) }
    Text("Speaking speed", style = MaterialTheme.typography.bodyLarge)
    Choice(listOf(0.85f to "Slower", 1.0f to "Normal", 1.15f to "Faster", 1.3f to "Fastest"), s.callSpeed, vm.call::setSpeed)
    Text("Your turn ends after a pause of", style = MaterialTheme.typography.bodyLarge)
    Choice(listOf(400 to "Short", 600 to "Normal", 1000 to "Long", 1600 to "Very long"), s.callPatience, vm.call::setPatience)
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size, MaterialTheme.shapes.small),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                icon = {},
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** Plays a voice sample (16-bit PCM at 24 kHz); in a call it goes where the call's sound goes. */
private suspend fun playPcm(context: Context, pcm: ByteArray, inCall: Boolean) = withContext(Dispatchers.IO) {
    if (pcm.isEmpty()) return@withContext
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (inCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(pcm.size)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    try {
        track.write(pcm, 0, pcm.size)
        track.play()
        delay(pcm.size / 2 * 1000L / 24000 + 150)
    } finally {
        runCatching { track.stop() }
        track.release()
    }
}

/** The button that starts a call, beside the microphone in the composer. */
@Composable
fun CallStartButton(vm: ChatViewModel) {
    val start = rememberCallStarter(vm)
    IconButton(onClick = start) { Icon(Icons.Rounded.GraphicEq, "Voice call") }
}
