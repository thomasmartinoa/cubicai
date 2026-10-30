package dev.martin.cubicai.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
private fun EditDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), maxLines = 10) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Send") } },
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
) {
    val context = LocalContext.current
    val images = message.attachments.filter { it.isImage }
    val hasText = message.content.isNotEmpty()
    val thinking = streaming && !hasText && message.reasoning.isNotBlank()
    val waiting = streaming && !hasText && message.reasoning.isBlank() && images.isEmpty()
    val writing = streaming && hasText

    // Seconds spent thinking, ticking only while the model is.
    val seconds by produceState(0, thinking) {
        val start = System.currentTimeMillis()
        while (thinking) {
            value = ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(1000)
        }
    }
    val phase = when {
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
            spinning = streaming,
        )
        if (message.reasoning.isNotBlank()) ReasoningBlock(message.reasoning, active = thinking)
        if (hasText) MarkdownText(message.content)
        if (writing) TypingCursor()
        if (images.isNotEmpty()) CreatedImages(images)
        if (message.stopped) Text(
            "Stopped",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message.error?.let { ErrorCard(it, onRetry = if (isLast) onRegenerate else null) }
        if (message.citations.isNotEmpty()) Sources(message.citations)
        if (!streaming && (hasText || images.isNotEmpty())) Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasText) {
                SmallAction(Icons.Rounded.ContentCopy, "Copy") { copyToClipboard(context, message.content) }
                SmallAction(Icons.AutoMirrored.Rounded.VolumeUp, "Read aloud") { onSpeak(message.content) }
                SmallAction(Icons.Rounded.Share, "Share") { shareText(context, message.content) }
            }
            if (images.isNotEmpty()) SmallAction(Icons.Rounded.Download, "Save to gallery") {
                val saved = saveToGallery(context, images.first().path)
                Toast.makeText(context, if (saved) "Saved to Pictures/CubicAI" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
            }
            if (isLast) SmallAction(Icons.Rounded.Refresh, "Regenerate", onRegenerate)
            Spacer(Modifier.weight(1f))
            message.model?.let {
                Text(shortModelName(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Images the model created: shown large, tap for full screen. */
@Composable
private fun CreatedImages(images: List<Attachment>) {
    var viewing by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        images.forEach { a ->
            LocalImage(
                a.path,
                Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(MaterialTheme.shapes.medium).clickable { viewing = a.path },
                maxSize = 1024,
                contentScale = ContentScale.Fit,
            )
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

@Composable
private fun ReasoningBlock(reasoning: String, active: Boolean) {
    // Opens by itself while the model thinks, so you can watch it work, and folds away when it answers.
    var expanded by remember { mutableStateOf(active) }
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active) expanded = true else if (wasActive) expanded = false
        wasActive = active
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "arrow")
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { expanded = !expanded }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Psychology, null, Modifier.size(18.dp), tint = muted)
            Spacer(Modifier.width(6.dp))
            Text(if (active) "Thinking" else "Thought process", style = MaterialTheme.typography.labelLarge, color = muted)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp).graphicsLayer { rotationZ = arrow }, tint = muted)
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(200)) + fadeIn(tween(200)), exit = shrinkVertically(tween(160)) + fadeOut(tween(120))) {
            val scroll = rememberScrollState()
            LaunchedEffect(reasoning) { if (active) scroll.scrollTo(scroll.maxValue) }
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
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
private fun ShimmerText(text: String, modifier: Modifier = Modifier) {
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

/** Blinking block after the text while it streams in. */
@Composable
private fun TypingCursor() {
    val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "cursor",
    )
    Box(
        Modifier.padding(top = 2.dp).size(width = 8.dp, height = 14.dp).graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun ErrorCard(error: String, onRetry: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = if (onRetry == null) 12.dp else 0.dp)) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            onRetry?.let { TextButton(onClick = it, modifier = Modifier.align(Alignment.End)) { Text("Retry") } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sources(urls: List<String>) {
    val uriHandler = LocalUriHandler.current
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) urls else urls.take(6)
    Column(Modifier.padding(top = 10.dp)) {
        Text("Sources", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
