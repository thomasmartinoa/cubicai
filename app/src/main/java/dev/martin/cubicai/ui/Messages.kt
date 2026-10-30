package dev.martin.cubicai.ui

import androidx.compose.animation.AnimatedVisibility
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
    Column(Modifier.fillMaxWidth()) {
        // The reasoning block says "Thinking…" itself, so the header only shows PC status or the wait before it.
        val working = streaming && (status != null || (message.content.isEmpty() && message.reasoning.isBlank()))
        ReplyHeader(
            name = message.persona ?: fallbackName.ifBlank { "CubicAI" },
            status = if (working) status ?: "Thinking…" else null,
            spinning = streaming,
        )
        if (message.reasoning.isNotBlank()) ReasoningBlock(message.reasoning, active = streaming && message.content.isEmpty())
        if (message.content.isNotEmpty()) MarkdownText(message.content)
        if (message.stopped) Text(
            "Stopped",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message.error?.let { ErrorCard(it, onRetry = if (isLast) onRegenerate else null) }
        if (message.citations.isNotEmpty()) Sources(message.citations)
        if (!streaming && message.content.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallAction(Icons.Rounded.ContentCopy, "Copy") { copyToClipboard(context, message.content) }
            SmallAction(Icons.AutoMirrored.Rounded.VolumeUp, "Read aloud") { onSpeak(message.content) }
            SmallAction(Icons.Rounded.Share, "Share") { shareText(context, message.content) }
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

@Composable
private fun ReasoningBlock(reasoning: String, active: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { expanded = !expanded }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Psychology, null, Modifier.size(18.dp), tint = muted)
            Spacer(Modifier.width(6.dp))
            Text(if (active) "Thinking…" else "Thought process", style = MaterialTheme.typography.labelLarge, color = muted)
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = muted)
        }
        AnimatedVisibility(expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(reasoning.trim(), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** Cube and name above each reply; the cube rolls its light while the reply is being written. */
@Composable
private fun ReplyHeader(name: String, status: String?, spinning: Boolean) {
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cube(16.dp, spinning = spinning)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        status?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
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
