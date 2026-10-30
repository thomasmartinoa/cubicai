package dev.martin.cubicai.ui

import android.content.Context
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

private fun buildMarkwon(context: Context, colors: ColorScheme): Markwon {
    val density = context.resources.displayMetrics.density
    return Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(TablePlugin.create { theme ->
            theme.tableBorderColor(colors.outline.toArgb())
                .tableCellPadding((8 * density).toInt())
                .tableHeaderRowBackgroundColor(colors.surfaceContainerHigh.toArgb())
        })
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .codeTextColor(colors.onSurface.toArgb())
                    .codeBlockTextColor(colors.onSurface.toArgb())
                    .codeBackgroundColor(colors.surfaceContainerHighest.toArgb())
                    .codeBlockBackgroundColor(colors.surfaceContainerHigh.toArgb())
                    .codeBlockMargin((12 * density).toInt())
                    .codeTypeface(Typeface.MONOSPACE)
                    .blockQuoteColor(colors.outline.toArgb())
                    .linkColor(colors.primary.toArgb())
                    .headingBreakHeight(0)
            }
        })
        .build()
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val markwon = remember(colors) { buildMarkwon(context, colors) }
    val textColor = colors.onSurface.toArgb()
    // A finished message renders once, right away, so the list measures it at full height.
    // While a reply streams in, each new version is parsed off the main thread (one parse at a time, newest text
    // first) and swapped in when ready; parsing the whole reply on the UI thread for every update caused jank.
    var rendered by remember(markwon) { mutableStateOf(markdown to markwon.toMarkdown(markdown)) }
    val latest by rememberUpdatedState(markdown)
    LaunchedEffect(markwon) {
        snapshotFlow { latest }.conflate().collect { text ->
            if (text != rendered.first) {
                rendered = text to withContext(Dispatchers.Default) { markwon.toMarkdown(text) }
                // Re-laying out a long reply costs a frame or two, so long replies refresh a little less often (max ~8/s).
                delay((text.length / 40L).coerceAtMost(120L))
            }
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 16f
                setLineSpacing(0f, 1.2f)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            val spanned = rendered.second
            if (view.tag !== spanned) {
                view.tag = spanned
                markwon.setParsedMarkdown(view, spanned)
            }
        },
    )
}
