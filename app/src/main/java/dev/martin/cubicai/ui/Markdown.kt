package dev.martin.cubicai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.UpdateAppearance
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
import androidx.core.content.res.ResourcesCompat
import dev.martin.cubicai.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.utils.LeadingMarginUtils
import org.commonmark.node.ListItem

private fun buildMarkwon(context: Context, colors: ColorScheme): Markwon {
    val density = context.resources.displayMetrics.density
    val headingFont = ResourcesCompat.getFont(context, R.font.chakra_petch_semibold)
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
                    // Headings match the app's Chakra Petch titles, a step smaller than Markwon's 2x/1.5x defaults.
                    .apply { headingFont?.let { headingTypeface(it) } }
                    .headingTextSizeMultipliers(floatArrayOf(1.45f, 1.3f, 1.15f, 1.05f, 1f, 1f))
                    .thematicBreakColor(colors.outlineVariant.toArgb())
                    .thematicBreakHeight(density.toInt().coerceAtLeast(1))
                    .listItemColor(colors.primary.toArgb())
                    .bulletWidth((6 * density).toInt())
            }

            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                // Square bullets (the cube motif) instead of circles; both markers fade in with their item's first word.
                builder.setFactory(ListItem::class.java) { config, props ->
                    if (CoreProps.LIST_ITEM_TYPE.require(props) == CoreProps.ListItemType.BULLET) {
                        SquareBulletSpan(config.theme(), CoreProps.BULLET_LIST_ITEM_LEVEL.require(props))
                    } else NumberSpan(config.theme(), "${CoreProps.ORDERED_LIST_ITEM_NUMBER.require(props)}.\u00a0")
                }
            }
        })
        .build()
}

/** List bullet drawn as a small square: filled at the top level, outlined when nested. */
private class SquareBulletSpan(private val theme: MarkwonTheme, private val level: Int) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean) = theme.blockMargin

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        if (!first || !LeadingMarginUtils.selfStart(start, text, this)) return
        // While streaming, an item's bullet waits for its first word and fades in with it.
        if (text.subSequence(start, end).isBlank()) return
        val fade = markerAlpha(text, start)
        val style = p.style
        val color = p.color
        theme.applyListItemStyle(p)
        p.alpha = (p.alpha * fade).toInt()
        val side = theme.getBulletWidth(bottom - top).toFloat()
        val cx = x + dir * theme.blockMargin / 2f
        val cy = baseline - p.textSize * 0.3f // middle of the lowercase letters
        p.style = if (level == 0) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeWidth = side / 4f
        val half = if (level == 0) side / 2f else side / 2f - p.strokeWidth / 2f
        c.drawRect(cx - half, cy - half, cx + half, cy + half, p)
        p.style = style
        p.color = color
    }
}

/** How much a list marker should show: the fade of the first word of its item (1 once it has settled). */
private fun markerAlpha(text: CharSequence, start: Int): Float =
    (text as? Spanned)?.getSpans(start, start + 1, FadeSpan::class.java)?.firstOrNull()?.alpha ?: 1f

/** "1." marker laid out like Markwon's own (right-aligned in the margin, which widens for long numbers), but fading. */
private class NumberSpan(private val theme: MarkwonTheme, private val number: String) : LeadingMarginSpan {
    private var margin = 0

    override fun getLeadingMargin(first: Boolean) = maxOf(margin, theme.blockMargin)

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        if (!first || !LeadingMarginUtils.selfStart(start, text, this)) return
        if (text.subSequence(start, end).isBlank()) return
        val color = p.color
        theme.applyListItemStyle(p)
        p.alpha = (p.alpha * markerAlpha(text, start)).toInt()
        val numberWidth = (p.measureText(number) + .5f).toInt()
        val width = maxOf(theme.blockMargin, numberWidth)
        margin = if (numberWidth > theme.blockMargin) numberWidth else 0
        val left = if (dir > 0) x + width - numberWidth else x - width + (width - numberWidth)
        c.drawText(number, left.toFloat(), baseline.toFloat(), p)
        p.color = color
    }
}

/** Scales the alpha of the text it covers; [StreamFader] animates it per word. */
private class FadeSpan : CharacterStyle(), UpdateAppearance {
    var alpha = 0f

    override fun updateDrawState(tp: TextPaint) {
        tp.alpha = (tp.alpha * alpha).toInt()
        if (tp.bgColor != 0) tp.bgColor = (((tp.bgColor ushr 24) * alpha).toInt() shl 24) or (tp.bgColor and 0xFFFFFF)
    }
}

/**
 * Fades streamed text in word by word, so a reply flows onto the screen instead of jumping in chunks.
 * Each batch of new words is spread over a short window that shrinks when the model writes faster than the
 * fade, so no word starts more than [SPREAD_MS] after it arrived. Driven by the view's own frame callbacks:
 * only alpha changes, so lines are never re-measured.
 */
private class StreamFader(private val view: TextView) : Runnable {
    private class Word(val start: Int, var end: Int, val at: Long, val span: FadeSpan = FadeSpan())

    private val words = ArrayList<Word>()
    private var rendered: Spanned? = null
    private var shown = ""
    private var nextAt = 0L
    private var posted = false
    /** Set once this view shows a reply that is streaming; its last words may land just after the stream ends. */
    private var live = false

    fun show(markwon: Markwon, spanned: Spanned, streaming: Boolean) {
        if (spanned === rendered) return
        rendered = spanned
        markwon.setParsedMarkdown(view, spanned)
        live = live || streaming
        val text = view.text as? Spannable ?: return
        val now = text.toString()
        // Markdown can re-render earlier text (a closing ** turns "**bold" into bold), so only the unchanged
        // start keeps its fades; everything after the first difference counts as new.
        val keep = shown.commonPrefixWith(now).length
        shown = now
        words.removeAll { it.start >= keep }
        words.forEach { w ->
            w.end = minOf(w.end, keep)
            text.setSpan(w.span, w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (live && keep < now.length) {
            val clock = SystemClock.uptimeMillis()
            val ranges = wordRanges(now, keep)
            // New words follow on from the previous batch's wave, but every word starts within SPREAD_MS of arriving:
            // a fast model compresses the wave instead of letting the fade fall further and further behind.
            val deadline = clock + SPREAD_MS
            var at = maxOf(clock, nextAt).coerceAtMost(deadline)
            val step = ((deadline - at) / ranges.size).coerceAtMost(MAX_STEP_MS)
            ranges.forEach { (s, e) ->
                val w = Word(s, e, at)
                text.setSpan(w.span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                words += w
                at += step
            }
            nextAt = at
        }
        run()
    }

    override fun run() {
        posted = false
        val text = view.text as? Spannable ?: return
        val clock = SystemClock.uptimeMillis()
        val it = words.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val t = ((clock - w.at).toFloat() / FADE_MS).coerceIn(0f, 1f)
            if (t >= 1f) {
                text.removeSpan(w.span)
                it.remove()
            } else {
                val alpha = 1f - (1f - t) * (1f - t) // ease out
                if (alpha != w.span.alpha) {
                    w.span.alpha = alpha
                    // Re-setting the span tells the TextView to redraw that range (it caches drawn text blocks).
                    text.setSpan(w.span, w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        if (words.isNotEmpty() && !posted) {
            posted = true
            view.postOnAnimation(this)
        }
    }

    /** Each word with the spaces after it, from [from] to the end. */
    private fun wordRanges(text: String, from: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = from
        while (i < text.length) {
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            while (i < text.length && text[i].isWhitespace()) i++
            out += start to i
        }
        return out
    }

    companion object {
        const val FADE_MS = 320L
        const val SPREAD_MS = 360L
        const val MAX_STEP_MS = 32L
    }
}

/**
 * Renders a reply's Markdown. While [streaming], newly arrived words fade in one after another.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
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
                tag = StreamFader(this)
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            (view.tag as StreamFader).show(markwon, rendered.second, streaming)
        },
    )
}
