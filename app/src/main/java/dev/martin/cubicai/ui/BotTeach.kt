package dev.martin.cubicai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.KeyboardTab
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ChatViewModel
import org.json.JSONObject

/**
 * Teaching a Bot a task: its browser on the PC, shown as a picture you tap on (pinch to zoom), with an
 * address bar, typing and keys. Every step is written down; Done sends them to the Bot, which writes
 * them up as a skill for you to approve. A password you type is never written down.
 */
@Composable
fun TeachScreen(vm: ChatViewModel) {
    val state by vm.bots.state.collectAsStateWithLifecycle()
    val t = state.teaching ?: return
    val st = t.state
    var address by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.bots.watchTeach() }
    LaunchedEffect(st?.url) { st?.url?.takeIf { it.isNotBlank() && it != "about:blank" }?.let { address = it } }
    fun step(v: JSONObject) = vm.bots.teachStep(v)
    val close = { if (st?.steps.isNullOrEmpty()) vm.bots.cancelTeach() else confirmClose = true }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                // Top bar: close, who's being taught and how many steps, Done.
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Stop teaching") }
                    Column(Modifier.weight(1f)) {
                        Text("Teach ${t.botName}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FiberManualRecord, null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.error)
                            val n = st?.steps?.size ?: 0
                            Text(
                                "  Recording · $n step${if (n == 1) "" else "s"}",
                                Modifier.clickable(enabled = n > 0) { showSteps = !showSteps },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(onClick = { finishing = true }, enabled = !st?.steps.isNullOrEmpty() && !t.busy, shape = MaterialTheme.shapes.small) { Text("Done") }
                }
                if (t.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp)) else Box(Modifier.padding(top = 8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // The address bar.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            placeholder = { Text("Web address") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) step(JSONObject().put("do", "open").put("url", address.trim())) }),
                            shape = MaterialTheme.shapes.small,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { step(JSONObject().put("do", "open").put("url", address.trim())) }, enabled = address.isNotBlank() && !t.busy, shape = MaterialTheme.shapes.small) { Text("Go") }
                    }
                    BrowserPicture(t.picture, onTap = { x, y -> step(JSONObject().put("do", "tap").put("x", x.toDouble()).put("y", y.toDouble())) })
                    // Keys and scrolling.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        KeyButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { step(JSONObject().put("do", "back")) }
                        KeyButton(Icons.Rounded.KeyboardArrowUp, "Scroll up") { step(JSONObject().put("do", "scroll").put("direction", "up")) }
                        KeyButton(Icons.Rounded.KeyboardArrowDown, "Scroll down") { step(JSONObject().put("do", "scroll").put("direction", "down")) }
                        KeyButton(Icons.AutoMirrored.Rounded.KeyboardTab, "Tab") { step(JSONObject().put("do", "key").put("key", "Tab")) }
                        KeyButton(Icons.AutoMirrored.Rounded.Backspace, "Delete a character") { step(JSONObject().put("do", "key").put("key", "Backspace")) }
                        KeyButton(Icons.AutoMirrored.Rounded.KeyboardReturn, "Enter") { step(JSONObject().put("do", "key").put("key", "Enter")) }
                    }
                    // Typing goes into whatever was tapped last.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = typing,
                            onValueChange = { typing = it.take(2000) },
                            placeholder = { Text("Tap a field above, then type here") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (typing.isNotEmpty()) { step(JSONObject().put("do", "text").put("text", typing)); typing = "" } }),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { step(JSONObject().put("do", "text").put("text", typing)); typing = "" }, enabled = typing.isNotEmpty() && !t.busy) {
                            Icon(Icons.AutoMirrored.Rounded.Send, "Type it")
                        }
                    }
                    (t.error)?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    AnimatedVisibility(showSteps || (st?.steps?.size ?: 0) in 1..3) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Steps so far", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            st?.steps.orEmpty().forEachIndexed { i, s ->
                                Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (st?.steps.isNullOrEmpty()) Text(
                        "Show ${t.botName} how you do the task, step by step: open the page, tap where you'd click, type into fields. " +
                            "When you're done, it writes the steps up as a skill for you to check. Passwords you type are never written down.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (finishing) {
        var what by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { finishing = false },
            title = { Text("What's this task for?") },
            text = {
                Column {
                    Text(
                        "${t.botName} writes your ${st?.steps?.size ?: 0} steps up as a skill you can call later. Say what it's for and what changes each time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = what,
                        onValueChange = { what = it.take(2000) },
                        placeholder = { Text("e.g. Checking my power bill each month") },
                        minLines = 2,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = { Button(onClick = { finishing = false; vm.bots.finishTeach(what) {} }, shape = MaterialTheme.shapes.small) { Text("Send to ${t.botName}") } },
            dismissButton = { TextButton(onClick = { finishing = false }) { Text("Keep going") } },
        )
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("Stop teaching?") },
        text = { Text("The steps you've shown are dropped. The browser stays where it is.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; vm.bots.cancelTeach() }) { Text("Stop", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep going") } },
    )
}

@Composable
private fun KeyButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, shape = MaterialTheme.shapes.small) { Icon(icon, label) }
}

/** The browser's screenshot: tap to click there; pinch to zoom and drag to look around. */
@Composable
private fun BrowserPicture(picture: android.graphics.Bitmap?, onTap: (Float, Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val image = remember(picture) { picture?.asImageBitmap() }
    val ratio = picture?.let { it.width.toFloat() / it.height } ?: (1280f / 900f)
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(ratio).clipToBounds()
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, move, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    // Keep the picture covering the frame.
                    val maxX = size.width * (scale - 1) / 2
                    val maxY = size.height * (scale - 1) / 2
                    pan = Offset((pan.x + move.x).coerceIn(-maxX, maxX), (pan.y + move.y).coerceIn(-maxY, maxY))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    if (size.width == 0) return@detectTapGestures
                    // Where on the page that is, undoing the zoom (it scales around the centre).
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val x = ((at.x - cx - pan.x) / scale + cx) / size.width
                    val y = ((at.y - cy - pan.y) / scale + cy) / size.height
                    if (x in 0f..1f && y in 0f..1f) onTap(x, y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) Cube(28.dp, spinning = true)
        else Image(
            image,
            "The Bot's browser",
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
            contentScale = ContentScale.FillBounds,
        )
        if (scale > 1.05f) IconButton(onClick = { scale = 1f; pan = Offset.Zero }, Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Rounded.ZoomOutMap, "Fit", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
