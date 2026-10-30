package dev.martin.cubicai.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.martin.cubicai.data.Message

/** How much of the model's context the chat fills, and how many tokens it has used so far. */
private class ContextUsage(val used: Int, val exact: Boolean, val read: Int, val written: Int)

private fun contextUsage(messages: List<Message>): ContextUsage {
    // The last reply with reported counts covers everything up to it; anything newer is estimated (about 4 characters a token).
    val reported = messages.indexOfLast { it.promptTokens > 0 }
    val base = messages.getOrNull(reported)?.let { it.promptTokens + it.outputTokens } ?: 0
    val newer = messages.drop(reported + 1).sumOf { m -> m.content.length / 4 + m.attachments.count { it.isImage } * 258 }
    return ContextUsage(
        used = base + newer,
        exact = reported >= 0 && newer == 0,
        read = messages.sumOf { it.promptTokens },
        written = messages.sumOf { it.outputTokens },
    )
}

private fun tokens(n: Int): String = when {
    n >= 999_950 -> "%.1fM".format(n / 1e6).replace(".0M", "M")
    n >= 1000 -> "%.1fK".format(n / 1e3).replace(".0K", "K")
    else -> n.toString()
}

/** Small meter beside the send button; tap it for the details. */
@Composable
fun ContextMeter(messages: List<Message>, limit: Int, modifier: Modifier = Modifier) {
    val usage = remember(messages) { contextUsage(messages) }
    var open by remember { mutableStateOf(false) }
    val fraction = if (limit > 0) (usage.used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val color = when {
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.75f -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    val approx = if (usage.exact) "" else "~"
    val percent = (fraction * 100).toInt().let { if (it == 0 && usage.used > 0) "<1%" else "$approx$it%" }
    Box(modifier) {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable { open = true }
                .heightIn(min = 48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (limit > 0) Meter(fraction, color, Modifier.width(22.dp).height(6.dp))
            Text(
                if (limit > 0) percent else "$approx${tokens(usage.used)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CubicMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(260.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Context window", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (limit > 0) "$approx${tokens(usage.used)} of ${contextLabel(limit)} tokens used" else "$approx${tokens(usage.used)} tokens used",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (limit > 0) {
                    Meter(fraction, color, Modifier.fillMaxWidth().height(8.dp), cut = 3.dp)
                    Text(
                        "${tokens((limit - usage.used).coerceAtLeast(0))} tokens left ($percent used)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (usage.read > 0) Text(
                    "This chat so far: ${tokens(usage.read)} read, ${tokens(usage.written)} written",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        fraction >= 0.9f -> "Almost full. The model may lose the start of the chat; a new chat starts fresh."
                        !usage.exact && usage.read == 0 -> "Estimated from the text. Exact counts show after the next reply, if the provider reports them."
                        else -> "The whole chat is sent with every message, so it fills up as you go."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier, cut: Dp = 2.dp) {
    val shown by animateFloatAsState(fraction, tween(260), label = "context")
    val shape = CutCornerShape(cut)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        // A plain slice inside the bevelled track: a tiny bevelled fill would read as a round dot.
        if (shown > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceAtLeast(0.03f)).background(color))
    }
}
