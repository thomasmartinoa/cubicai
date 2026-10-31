package dev.martin.cubicai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import dev.martin.cubicai.CreateOptions
import dev.martin.cubicai.net.Generator

/** The tab a generator is listed under. */
fun tabOf(g: Generator) = when {
    g.kind == "image" && g.mode == "create" -> "Image"
    g.kind == "image" -> "Edit"
    g.kind == "video" -> "Video"
    g.mode == "music" -> "Music"
    else -> "Voice"
}

private val TABS = listOf("Image", "Edit", "Video", "Music", "Voice")

/** What the message box asks for while a generator is chosen. */
fun createPlaceholder(g: Generator, hasPhoto: Boolean, usesLast: Boolean = false) = when {
    g.image == "required" && !hasPhoto -> if (g.prompt) "Add a photo, then describe what to do" else "Add a photo to start"
    !g.prompt -> if (usesLast) "Ready: tap send to use the last picture" else "Ready: tap send"
    g.mode == "edit" -> if (usesLast) "Describe the change to the last picture" else "Describe the change"
    g.mode == "animate" -> "Describe how it should move"
    g.kind == "video" -> "Describe the video"
    g.mode == "music" -> "Describe the music"
    g.mode == "speech" -> "Type what to say"
    else -> "Describe the image"
}

/** "Z-Image Turbo · 3:4", "ACE-Step 1.5 · 30 s": what's chosen, for the home screen chip. */
fun createSummary(o: CreateOptions): String {
    val g = o.generator
    val parts = listOfNotNull(
        g.name,
        o.aspect.takeIf { g.aspects.isNotEmpty() && it.isNotEmpty() },
        o.seconds.takeIf { g.maxSeconds > 0 }?.let { if (it < 60 || it % 60 != 0) "$it s" else "${it / 60} min" },
        o.speaker.takeIf { g.voices.isNotEmpty() && it.isNotEmpty() }?.replaceFirstChar { it.uppercase() },
        o.language.takeIf { g.languages.isNotEmpty() && it.isNotEmpty() }?.substringBefore(" ("),
        o.seed?.let { "seed $it" },
        "tuned".takeIf { o.steps != null || o.cfg != null || o.negative != null },
    )
    return parts.joinToString(" · ")
}

/** Prompt ideas for the home screen while a generator is chosen; tapping one fills the message box. */
fun createIdeas(g: Generator): List<String> = when {
    !g.prompt -> emptyList()
    g.mode == "edit" -> listOf(
        "Turn it into a watercolor painting",
        "Make it winter, with fresh snow everywhere",
        "Change the background to a beach at sunset",
    )
    g.mode == "animate" -> listOf(
        "The camera slowly pushes in as the subject smiles",
        "A gentle wind moves through the hair and the trees",
    )
    g.kind == "video" -> listOf(
        "A paper boat drifting down a rain-filled street, close-up",
        "Slow aerial shot over misty tea hills at sunrise",
        "A cat chasing a butterfly through tall grass, slow motion",
    )
    g.mode == "music" -> listOf(
        "Lo-fi piano, rainy night, soft vinyl crackle",
        "Upbeat synthwave with a driving bassline, 118 BPM",
        "Calm acoustic guitar for a slow morning",
    )
    g.mode == "speech" -> listOf(
        "Good morning! Here's what's on for today.",
        "Welcome home. The lights are on and the kettle is warm.",
    )
    g.id == "illustrious" -> listOf(
        "1girl, silver hair, kimono, night festival, lanterns, cherry blossoms",
        "1boy, armor, standing on a cliff, dramatic sky, wind",
    )
    else -> listOf(
        "A red fox sitting in fresh snow, golden hour, shallow depth of field",
        "A rainy neon street in Tokyo at night, reflections on wet asphalt",
        "A cosy reading nook with a sleeping cat, warm lamp light",
    )
}

/** Chooses what the PC's ComfyUI makes from the next message, and how. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadGenerators() }
    val generators = state.generators
    val chosen = state.create
    val tabs = TABS.filter { t -> generators.any { tabOf(it) == t } }
    var tab by remember { mutableStateOf(chosen?.let { tabOf(it.generator) }) }
    // Opens on the last thing made, so going back to it is just Done.
    LaunchedEffect(generators) {
        if (state.create == null) vm.lastCreate()?.let { vm.setCreate(it); tab = tabOf(it.generator) }
    }
    val shownTab = tab ?: tabs.firstOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val edge = Modifier.padding(horizontal = 20.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("Create on your PC", style = MaterialTheme.typography.titleLarge, modifier = edge)
            Text(
                "ComfyUI makes it on your PC. Nothing stays there unless you tap Save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = edge,
            )
            Spacer(Modifier.height(12.dp))
            when {
                generators.isEmpty() && state.loadingGenerators -> Row(edge.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cube(20.dp, spinning = true)
                    Text("Asking your PC what it can make…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                generators.isEmpty() -> Column(edge.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        state.generatorsError
                            ?: "Your PC didn't list anything to make. Check that ComfyUI is installed and its models are in place, then try again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = vm::loadGenerators, shape = MaterialTheme.shapes.small) { Text("Try again") }
                }
                else -> {
                    Row(edge.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabs.forEach { t ->
                            FilterChip(
                                selected = t == shownTab,
                                onClick = { tab = t },
                                label = { Text(t) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    generators.filter { tabOf(it) == shownTab }.forEach { g ->
                        val on = chosen?.generator?.id == g.id
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (on) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                                .clickable { vm.setCreate(if (on) chosen else CreateOptions(g)) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val tone = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            Box(
                                Modifier.padding(end = 16.dp).size(18.dp)
                                    .border(2.dp, tone, CutCornerShape(5.dp))
                                    .padding(3.dp)
                                    .background(if (on) tone else Color.Transparent, CutCornerShape(2.dp)),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(g.name, style = MaterialTheme.typography.titleSmall)
                                val needs = when (g.image) {
                                    "required" -> " · needs a photo"
                                    "optional" -> " · photo optional"
                                    else -> ""
                                }
                                Text(g.note + needs, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    chosen?.takeIf { tabOf(it.generator) == shownTab }?.let { o -> Options(o, vm.lastSeed(), vm::setCreate) }
                }
            }
            Row(edge.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                // Pulled in by its inner padding so the label, not the touch target, sits on the edge.
                if (chosen != null) TextButton(onClick = { vm.setCreate(null); onDismiss() }, Modifier.offset(x = (-12).dp)) { Text("Back to chat") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss, shape = MaterialTheme.shapes.small, enabled = chosen != null) { Text("Done") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(o: CreateOptions, lastSeed: Long?, onChange: (CreateOptions) -> Unit) {
    val g = o.generator
    val edge = Modifier.padding(horizontal = 20.dp)
    if (g.aspects.isNotEmpty()) {
        Label("Shape")
        // An even grid, four to a row, each with an outline of the frame it makes.
        Column(edge, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            g.aspects.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a -> AspectChoice(a, a == o.aspect, Modifier.weight(1f)) { onChange(o.copy(aspect = a)) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (g.maxSeconds > 0) {
        Label("Length")
        val options = if (g.kind == "video") (1..g.maxSeconds).toList() else listOf(30, 60, 120, 180, 240).filter { it <= g.maxSeconds }
        FlowRow(edge, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { n -> Choice(if (n < 60 || n % 60 != 0) "$n s" else "${n / 60} min", n == o.seconds) { onChange(o.copy(seconds = n)) } }
        }
    }
    if (g.voices.isNotEmpty()) {
        Label("Voice")
        FlowRow(edge, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            g.voices.forEach { v -> Choice(v.replaceFirstChar { it.uppercase() }, v == o.speaker) { onChange(o.copy(speaker = v)) } }
        }
    }
    if (g.languages.isNotEmpty()) {
        Label("Language")
        var open by remember { mutableStateOf(false) }
        Box(edge) {
            OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.small) {
                Text(o.language)
                Icon(Icons.Rounded.ExpandMore, null, Modifier.padding(start = 6.dp))
            }
            CubicMenu(expanded = open, onDismissRequest = { open = false }) {
                g.languages.forEach { l -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onChange(o.copy(language = l)) }) }
            }
        }
        Text(
            "Attach a short voice recording (Files) to make it speak in that voice instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = edge.padding(top = 6.dp),
        )
    }
    if (g.lyrics) {
        Label("Lyrics (optional)")
        OutlinedTextField(
            value = o.lyrics,
            onValueChange = { onChange(o.copy(lyrics = it)) },
            placeholder = { Text("[Verse]\nYour lines here\n[Chorus]\n…", color = MaterialTheme.colorScheme.outline) },
            minLines = 3,
            maxLines = 8,
            modifier = edge.fillMaxWidth(),
        )
        Text(
            "Leave it empty for an instrumental.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = edge.padding(top = 6.dp),
        )
    }
    MoreControls(o, lastSeed, onChange)
}

private const val SEED_MAX = 4_294_967_295L

/** Seed, steps, CFG and what to avoid: folded away, starting from the workflow's own values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreControls(o: CreateOptions, lastSeed: Long?, onChange: (CreateOptions) -> Unit) {
    val g = o.generator
    if (!g.seed && g.steps == null && g.cfg == null && g.negativeDefault == null) return
    val edge = Modifier.padding(horizontal = 20.dp)
    var open by remember(g.id) { mutableStateOf(o.tuned) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Text buttons pulled in by their 12dp inner padding, so the labels sit on the 20dp edge.
        TextButton(onClick = { open = !open }, Modifier.padding(start = 8.dp)) {
            Text("More controls")
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        if (o.tuned) TextButton(
            onClick = { onChange(o.copy(seed = null, steps = null, cfg = null, negative = null)) },
            Modifier.padding(end = 8.dp),
        ) { Text("Reset") }
    }
    AnimatedVisibility(open) {
        Column {
            if (g.seed) {
                Label("Seed")
                FlowRow(edge, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Random", o.seed == null) { onChange(o.copy(seed = null)) }
                    Choice("Fixed", o.seed != null) {
                        onChange(o.copy(seed = o.seed ?: lastSeed ?: Random.nextLong(SEED_MAX + 1)))
                    }
                }
                o.seed?.let { seed ->
                    // Typed text lives here so an empty box stays empty; the option keeps the last valid number.
                    var text by remember(g.id) { mutableStateOf(seed.toString()) }
                    LaunchedEffect(seed) { if (text.toLongOrNull() != seed) text = seed.toString() }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { t ->
                            text = t.filter(Char::isDigit).take(10)
                            text.toLongOrNull()?.takeIf { it <= SEED_MAX }?.let { onChange(o.copy(seed = it)) }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = edge.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (lastSeed != null && lastSeed != seed) {
                        TextButton(onClick = { onChange(o.copy(seed = lastSeed)) }, Modifier.padding(start = 8.dp)) {
                            Text("Use the last picture's seed ($lastSeed)")
                        }
                    }
                }
                Hint(
                    if (o.seed == null) "A new seed each time, so every result is different."
                    else "Same seed and prompt give the same result. Change a few words to vary just that."
                )
            }
            g.steps?.let { t ->
                val value = o.steps ?: t.default.toInt()
                fun set(v: Int) = onChange(o.copy(steps = v.coerceIn(t.min.toInt(), t.max.toInt()).takeIf { it != t.default.toInt() }))
                Stepper("Steps", "$value", o.steps != null, { set(value - 1) }, { set(value + 1) })
            }
            g.cfg?.let { t ->
                val value = o.cfg ?: t.default
                fun set(v: Double) = onChange(o.copy(cfg = (Math.round(v.coerceIn(t.min, t.max) * 10) / 10.0).takeIf { it != t.default }))
                Stepper("CFG", "%.1f".format(value), o.cfg != null, { set(value - 0.5) }, { set(value + 0.5) })
            }
            if (g.steps != null || g.cfg != null) {
                Hint("More steps add detail but take longer. Higher CFG follows the prompt more closely; too high looks harsh.")
            }
            g.negativeDefault?.let { default ->
                Label("Avoid")
                OutlinedTextField(
                    value = o.negative ?: default,
                    onValueChange = { onChange(o.copy(negative = it.takeIf { v -> v != default })) },
                    placeholder = { Text("Things to keep out of the picture", color = MaterialTheme.colorScheme.outline) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = edge.fillMaxWidth(),
                )
            }
        }
    }
}

/** A number with − and + beside it; the value turns amber once it differs from the workflow's. */
@Composable
private fun Stepper(label: String, value: String, changed: Boolean, onLess: () -> Unit, onMore: () -> Unit) {
    // The + button is pulled in by its inner padding, so its icon, not the touch target, sits on the edge.
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                if (changed) "Changed" else "Default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onLess) { Icon(Icons.Rounded.Remove, "Less $label") }
        Text(
            value,
            Modifier.widthIn(min = 44.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onMore) { Icon(Icons.Rounded.Add, "More $label") }
    }
}

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
)

@Composable
private fun Label(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
)

@Composable
private fun AspectChoice(aspect: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ratio = aspectOf(aspect)
    val tone = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.height(40.dp).clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The frame drawn to scale inside a 16dp box.
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(if (ratio >= 1f) 16.dp else 16.dp * ratio, if (ratio >= 1f) 16.dp / ratio else 16.dp)
                    .border(1.5.dp, tone, CutCornerShape(2.dp)),
            )
        }
        Text(aspect, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = tone)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text) },
    colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    ),
)
