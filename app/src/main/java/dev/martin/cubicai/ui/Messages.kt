package dev.martin.cubicai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import dev.martin.cubicai.net.MediaProgress
import org.json.JSONObject
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.martin.cubicai.data.Attachment
import dev.martin.cubicai.data.Message

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(message: Message, canEdit: Boolean, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End) {
        AttachmentStrip(message.attachments)
        if (message.spoken) SpokenMark(Modifier.padding(bottom = 4.dp))
        if (message.content.isNotEmpty()) Box {
            // Two opposite bevels give the bubble a slant; the square corner points at the speaker.
            val shape = CutCornerShape(topStart = 14.dp, topEnd = 2.dp, bottomEnd = 14.dp, bottomStart = 2.dp)
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clip(shape).combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(
                    message.content,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            CubicMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { copyToClipboard(context, message.content); menu = false },
                )
                if (canEdit) DropdownMenuItem(
                    text = { Text("Edit & resend") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { editing = true; menu = false },
                )
            }
        }
    }
    if (editing) EditDialog(message.content, onDismiss = { editing = false }, onSave = { onEdit(it); editing = false })
}

@Composable
private fun EditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    title: String = "Edit message",
    note: String = "Sending replaces the replies that came after this message.",
    confirm: String = "Send",
) {
    // Opens ready to type: field focused, keyboard up, cursor after the last word.
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), maxLines = 10)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.text) }, enabled = text.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AttachmentStrip(items: List<Attachment>) {
    if (items.isEmpty()) return
    var viewing by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { a ->
            if (a.isImage) LocalImage(
                a.path,
                Modifier.size(112.dp).clip(MaterialTheme.shapes.medium).clickable { viewing = a.path },
            ) else FileChip(a.name)
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

@Composable
fun FileChip(name: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                name,
                Modifier.widthIn(max = 160.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AssistantMessage(
    message: Message,
    streaming: Boolean,
    status: String?,
    fallbackName: String,
    isLast: Boolean,
    onRegenerate: () -> Unit,
    onSpeak: (String) -> Unit,
    progress: MediaProgress? = null,
    onSave: () -> Unit = {},
    /** Makes this picture the character's face in the chat. */
    onUseAsFace: (() -> Unit)? = null,
    /** Makes what the model asked for again, with the prompt the user changed. */
    onEditPrompt: ((String) -> Unit)? = null,
    /** The user's yes or no to a video the model wants to make. */
    onAnswerVideo: ((Boolean) -> Unit)? = null,
    /** Thinking shown open or folded, as the user last left it (null: open only while the model thinks). */
    thoughtsOpen: Boolean? = null,
    onThoughtsToggle: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val images = message.attachments.filter { it.isImage }
    val media = message.attachments.filter { it.isMedia }
    val hasText = message.content.isNotEmpty()
    val making = message.mediaPending
    val thinking = streaming && !hasText && message.reasoning.isNotBlank()
    val waiting = streaming && !hasText && message.reasoning.isBlank() && images.isEmpty() && !making
    val writing = streaming && hasText
    val request = remember(message.media?.params) { message.media?.params?.let { runCatching { JSONObject(it) }.getOrNull() } }
    // A video waits for the picture it's made from, which is its own reply just above.
    val awaitingPicture = making && request?.has("picture") == true && !request.has("source")
    val declined = request?.optBoolean("declined") == true

    // Seconds spent thinking, ticking only while the model is.
    val seconds by produceState(0, thinking) {
        val start = System.currentTimeMillis()
        while (thinking) {
            value = ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(1000)
        }
    }
    val phase = when {
        making && !message.stopped -> when {
            awaitingPicture -> "Waiting for its picture"
            progress == null -> if (streaming) "Sending to your PC" else "Waiting for your PC"
            progress.steps > 0 -> "${progress.phase} · ${progress.step}/${progress.steps}"
            else -> progress.phase.ifBlank { "Working" }
        }
        status != null -> status
        thinking -> if (seconds > 0) "Thinking for ${seconds}s" else "Thinking"
        waiting -> "Thinking"
        writing -> "Writing"
        else -> null
    }

    Column(Modifier.fillMaxWidth()) {
        ReplyHeader(
            name = message.persona ?: fallbackName.ifBlank { "CubicAI" },
            phase = phase,
            spinning = streaming || (making && !message.stopped),
        )
        if (message.media?.ask == true) VideoQuestion(request, onAnswerVideo.takeIf { !streaming })
        if (making && !message.stopped && !awaitingPicture) MediaProgressCard(progress, request?.optString("kind").orEmpty(), request?.optString("aspect")?.ifEmpty { request.optString("frame") })
        if (message.reasoning.isNotBlank()) ReasoningBlock(message.reasoning, active = thinking, preferred = thoughtsOpen, onToggle = onThoughtsToggle)
        // The height eases open as lines arrive, so the reply above glides up instead of jumping a line at a time.
        if (hasText) MarkdownText(message.content, Modifier.animateContentSize(tween(180)), streaming = streaming)
        if (images.isNotEmpty()) CreatedImages(images)
        message.attachments.filter { it.isVideo }.forEach { VideoPlayer(it.path, Modifier.padding(top = 4.dp)) }
        val sound = if (message.media?.generator == "chatterbox") "Speech" else "Music"
        message.attachments.filter { it.isAudio }.forEach { AudioPlayer(it, sound, Modifier.padding(top = 4.dp)) }
        // What the chat model asked for, in its own words.
        message.media?.call?.let { call ->
            val asked = remember(call.arguments) { runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject()) }
            var editing by remember { mutableStateOf(false) }
            val kind = when {
                message.media.generator == "chatterbox" -> "voice"
                request?.optString("kind") == "audio" -> "music"
                else -> asked.optString("kind").ifEmpty { "picture" }
            }
            ModelPrompt(asked, kind, onEdit = { editing = true }.takeIf { onEditPrompt != null && !streaming && !making && !message.media.ask })
            if (editing) EditDialog(
                asked.optString("prompt"),
                onDismiss = { editing = false },
                onSave = { onEditPrompt?.invoke(it); editing = false },
                title = "Edit the prompt",
                note = "Makes it again as a new reply; this one stays.",
                confirm = "Make again",
            )
        }
        if (message.stopped) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (declined) "Not made" else "Stopped",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A stopped creation left nothing behind, so offer to run it again rather than make you retype it.
            if (message.media != null && media.isEmpty() && isLast && !streaming) {
                TextButton(onClick = onRegenerate, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(start = 8.dp)) {
                    Text(if (declined) "Make it now" else "Try again")
                }
            }
        }
        message.error?.let { ErrorCard(it, onRetry = if (isLast) onRegenerate else null) }
        if (message.citations.isNotEmpty()) Sources(message.citations)
        if (!streaming && (hasText || media.isNotEmpty())) Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pulled left by the buttons' inner padding so the first icon lines up with the text above.
            Row(Modifier.offset(x = (-15).dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasText) {
                SmallAction(Icons.Rounded.ContentCopy, "Copy") { copyToClipboard(context, message.content) }
                SmallAction(Icons.AutoMirrored.Rounded.VolumeUp, "Read aloud") { onSpeak(message.content) }
                SmallAction(Icons.Rounded.Share, "Share") { shareText(context, message.content) }
            }
            if (media.isNotEmpty()) {
                SmallAction(Icons.Rounded.Download, "Save", onSave)
                if (!hasText) SmallAction(Icons.Rounded.Share, "Share") { shareFile(context, media.first()) }
                if (images.isNotEmpty() && onUseAsFace != null) SmallAction(Icons.Rounded.Face, "Use as face", onUseAsFace)
            }
            if (isLast) SmallAction(Icons.Rounded.Refresh, "Regenerate", onRegenerate)
            }
            // The header already names the model unless a behaviour's name is shown there; then how long it took.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                val model = message.model?.takeIf { message.persona != null }?.let(::displayModelName)
                if (model != null) Text(
                    model, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (message.took > 0) Text(
                    (if (model != null) " · " else "") + duration(message.took),
                    style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1,
                )
                if (message.spoken) SpokenMark(Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** Said aloud in a voice call. */
@Composable
private fun SpokenMark(modifier: Modifier = Modifier) {
    Icon(
        Icons.Rounded.GraphicEq, "Said in a call",
        modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "2.4 s", "41 s", "4 min 9 s". */
private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        ms < 10_000 -> "%.1f s".format(ms / 1000.0)
        s < 60 -> "$s s"
        s % 60 == 0L -> "${s / 60} min"
        else -> "${s / 60} min ${s % 60} s"
    }
}

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, description, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Images the model created: shown large, tap for full screen. */
@Composable
private fun CreatedImages(images: List<Attachment>) {
    var viewing by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        images.forEach { a ->
            val ratio = remember(a.path) { imageRatio(a.path) }
            LocalImage(
                a.path,
                Modifier.mediaFrame(ratio).clip(MaterialTheme.shapes.medium).clickable { viewing = a.path },
                maxSize = 1024,
                contentScale = ContentScale.Crop,
            )
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** A video the chat model wants to make: it takes minutes on the PC, so it starts only on the user's yes. */
@Composable
private fun VideoQuestion(request: JSONObject?, onAnswer: ((Boolean) -> Unit)?) {
    val seconds = request?.optInt("seconds")?.takeIf { it > 0 } ?: 4
    val fromLast = remember(request) {
        runCatching { JSONObject(request?.optString("picture").orEmpty()).optString("start_from") == "last_picture" }.getOrDefault(false)
    }
    // Wan takes about a minute per second of video, and the picture it starts from about another.
    val minutes = seconds + if (fromLast) 0 else 1
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Text("Make a $seconds s video?", style = MaterialTheme.typography.titleSmall)
            Text(
                (if (fromLast) "Brings the last picture to life. " else "A picture first, then brought to life. ") +
                    "About $minutes min on your PC; the chat waits meanwhile.",
                Modifier.padding(top = 4.dp, end = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAnswer?.invoke(false) }, enabled = onAnswer != null, shape = MaterialTheme.shapes.small) { Text("Not now") }
                TextButton(onClick = { onAnswer?.invoke(true) }, enabled = onAnswer != null, shape = MaterialTheme.shapes.small) { Text("Make it") }
            }
        }
    }
}

/** The prompt the chat model wrote for something it made, folded away like its thoughts. */
@Composable
private fun ModelPrompt(asked: JSONObject, kind: String, onEdit: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "arrow")
    Column(Modifier.padding(top = 4.dp)) {
        Row(
            Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { expanded = !expanded }.padding(end = 4.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Notes, null, Modifier.size(16.dp), tint = muted)
            Spacer(Modifier.width(8.dp))
            Text(if (kind == "voice") "What it says" else "Prompt", style = MaterialTheme.typography.labelLarge, color = muted)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp).graphicsLayer { rotationZ = arrow }, tint = muted)
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(160)) + fadeOut(tween(120))) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = if (onEdit != null) 0.dp else 12.dp)) {
                    Text(asked.optString("prompt").trim(), style = MaterialTheme.typography.bodySmall, color = muted)
                    asked.optString("motion").takeIf { kind == "video" && it.isNotBlank() }?.let {
                        Text("Motion: ${it.trim()}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    asked.optString("lyrics").takeIf { kind == "music" && it.isNotBlank() }?.let {
                        Text(it.trim(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    asked.optString("negative").takeIf { it.isNotBlank() }?.let {
                        Text("Avoid: $it", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    // Pulled in by its 12dp inner padding so the label lines up with the text above.
                    if (onEdit != null) TextButton(onClick = onEdit, shape = MaterialTheme.shapes.small, modifier = Modifier.offset(x = (-12).dp)) {
                        Text("Edit and make again")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReasoningBlock(reasoning: String, active: Boolean, preferred: Boolean?, onToggle: (Boolean) -> Unit) {
    // Until the user opens or folds one, it opens by itself while the model thinks and folds away when it answers.
    // After that every reply follows their choice.
    var expanded by remember { mutableStateOf(preferred ?: active) }
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active, preferred) {
        if (preferred != null) expanded = preferred else if (active) expanded = true else if (wasActive) expanded = false
        wasActive = active
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "arrow")
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { expanded = !expanded; onToggle(expanded) }
                .padding(end = 4.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Same 16dp mark + 8dp gap as the reply header, so both labels start on one line.
            Icon(Icons.Rounded.Psychology, null, Modifier.size(16.dp), tint = muted)
            Spacer(Modifier.width(8.dp))
            Text(if (active) "Thoughts" else "Thought process", style = MaterialTheme.typography.labelLarge, color = muted)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp).graphicsLayer { rotationZ = arrow }, tint = muted)
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(160)) + fadeOut(tween(120))) {
            val scroll = rememberScrollState()
            // Follow the newest thoughts: track the scroll range itself, which grows only after the new text is laid out.
            LaunchedEffect(active) {
                if (active) snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    reasoning.trim(),
                    Modifier.heightIn(max = 200.dp).verticalScroll(scroll).padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
    }
}

/** Cube and name above each reply; the cube rolls its light and the phase label shimmers while the reply is being made. */
@Composable
private fun ReplyHeader(name: String, phase: String?, spinning: Boolean) {
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cube(16.dp, spinning = spinning)
        Spacer(Modifier.width(8.dp))
        // One line: a long model name is cut short rather than wrapping or pushing the status out.
        Text(
            name,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
            label = "phase",
        ) { text ->
            if (text != null) ShimmerText(text.trimEnd('.', '…'), Modifier.padding(start = 10.dp))
            else Spacer(Modifier.width(0.dp))
        }
    }
}

/** Label with a band of light sweeping across it: "the model is busy". */
@Composable
internal fun ShimmerText(text: String, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val glow = MaterialTheme.colorScheme.onSurface
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -150f,
        targetValue = 450f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "sweep",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // The band is painted over the glyphs at draw time, so the sweep never recomposes or re-lays-out the text.
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = base,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, glow, Color.Transparent),
                            start = Offset(sweep - 80f, 0f),
                            end = Offset(sweep + 80f, 0f),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
        Dots(base)
    }
}

/** Three dots that fill in one after another. */
@Composable
private fun Dots(color: Color) {
    val t by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "dots",
    )
    Row(Modifier.padding(start = 3.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            Box(Modifier.size(3.dp).graphicsLayer { alpha = if (t.toInt() >= i) 1f else 0.25f }.background(color))
        }
    }
}

@Composable
internal fun ErrorCard(error: String, onRetry: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = if (onRetry == null) 12.dp else 0.dp)) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            onRetry?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) { Text("Retry") } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Sources(urls: List<String>) {
    val uriHandler = LocalUriHandler.current
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) urls else urls.take(6)
    Column(Modifier.padding(top = 10.dp)) {
        Text("Sources", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Chips keep 48dp touch targets; the negative row gap makes the visible gaps 8dp both ways.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy((-8).dp)) {
            shown.forEach { url ->
                AssistChip(
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    label = { Text(domainOf(url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Link, null, Modifier.size(16.dp)) },
                )
            }
            if (!showAll && urls.size > shown.size) AssistChip(
                onClick = { showAll = true },
                label = { Text("+${urls.size - shown.size} more") },
            )
        }
    }
}
