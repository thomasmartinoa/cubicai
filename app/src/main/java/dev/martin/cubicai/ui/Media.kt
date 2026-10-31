package dev.martin.cubicai.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import dev.martin.cubicai.data.Attachment
import dev.martin.cubicai.net.MediaProgress
import kotlinx.coroutines.delay
import java.io.File

/**
 * The one frame for pictures and videos in replies: exactly their shape, full width unless that would make
 * them taller than 420dp, and left-aligned like the text. The progress card uses it too, so a result lands
 * where its preview was instead of jumping.
 */
fun Modifier.mediaFrame(ratio: Float) = widthIn(max = 420.dp * ratio).fillMaxWidth().aspectRatio(ratio)

/** Width / height of an image file, from its header only; square if it can't be read. */
fun imageRatio(path: String): Float {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 1f
}

/** "3:4" → 0.75; anything unknown is square. */
fun aspectOf(aspect: String?): Float {
    val (w, h) = aspect?.split(':')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 } ?: return 1f
    return if (w > 0 && h > 0) w / h else 1f
}

/**
 * A job running on the PC: the latest preview frame (ComfyUI sends one every second or so while it samples)
 * over a slow shimmer, the step it's on and a progress bar. Sounds have no preview, just the bar.
 */
@Composable
fun MediaProgressCard(progress: MediaProgress?, kind: String, aspect: String?) {
    val fraction by animateFloatAsState(progress?.progress ?: 0f, tween(260), label = "media")
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (kind != "audio") {
            val ratio = aspectOf(aspect)
            Box(
                Modifier.mediaFrame(ratio)
                    .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Shimmer(Modifier.fillMaxSize())
                val bitmap = remember(progress?.preview) {
                    progress?.preview?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
                }
                Crossfade(bitmap, animationSpec = tween(260), label = "preview") { frame ->
                    if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        Bar(fraction, Modifier.fillMaxWidth().height(6.dp))
    }
}

@Composable
private fun Shimmer(modifier: Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "shimmer",
    )
    Box(modifier.background(Brush.linearGradient(listOf(base, light, base), start = androidx.compose.ui.geometry.Offset(t * 2000f - 1000f, 0f),
        end = androidx.compose.ui.geometry.Offset(t * 2000f, 600f))))
}

/** Track with a plain fill, the same build as the context meter's. */
@Composable
private fun Bar(fraction: Float, modifier: Modifier, cut: Dp = 2.dp, onSeek: ((Float) -> Unit)? = null) {
    Box(
        modifier.clip(CutCornerShape(cut)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (onSeek != null) Modifier.pointerInput(Unit) { detectTapGestures { onSeek(it.x / size.width) } } else Modifier),
    ) {
        if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.02f, 1f)).background(MaterialTheme.colorScheme.primary))
    }
}

/** A video made on the PC: first frame until tapped, then plays on a loop; tap again to pause. */
@Composable
fun VideoPlayer(path: String, modifier: Modifier = Modifier) {
    val ratio = remember(path) {
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(path)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation % 180 == 0) w / h else h / w
            }
        }.getOrDefault(16f / 9f)
    }
    val player = remember(path) { MediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(path) { onDispose { player.release() } }
    Box(
        modifier.mediaFrame(ratio).clip(MaterialTheme.shapes.medium)
            .background(Color.Black)
            .clickable(enabled = ready) {
                if (player.isPlaying) player.pause() else player.start()
                playing = player.isPlaying
            },
    ) {
        AndroidView(factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        runCatching {
                            player.setSurface(Surface(texture))
                            player.setDataSource(path)
                            player.isLooping = true
                            player.setOnPreparedListener { it.seekTo(1); ready = true }
                            player.prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        playing = false
                        return true
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        }, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(!playing, Modifier.align(Alignment.Center), enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            Surface(shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.55f)) {
                Icon(Icons.Rounded.PlayArrow, "Play", Modifier.padding(10.dp).size(32.dp), tint = Color.White)
            }
        }
    }
}

/** A sound made on the PC: play/pause, a bar you can tap to jump, and the time. */
@Composable
fun AudioPlayer(attachment: Attachment, title: String, modifier: Modifier = Modifier) {
    val player = remember(attachment.path) { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    DisposableEffect(attachment.path) {
        runCatching {
            player.setDataSource(attachment.path)
            player.setOnPreparedListener { duration = it.duration; ready = true }
            player.setOnCompletionListener { playing = false; position = 0; it.seekTo(0) }
            player.prepareAsync()
        }
        onDispose { player.release() }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(200)
        }
    }
    var shown by remember { mutableFloatStateOf(0f) }
    shown = if (duration > 0) position.toFloat() / duration else 0f
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = {
                    if (player.isPlaying) player.pause() else player.start()
                    playing = player.isPlaying
                },
                enabled = ready,
                shape = MaterialTheme.shapes.small,
            ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        title, Modifier.padding(start = 6.dp).weight(1f), style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${clock(position)} / ${clock(duration)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 24dp of height around a 6dp track, so the bar is easy to tap.
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    Bar(shown, Modifier.fillMaxWidth().height(6.dp), onSeek = { f ->
                        if (duration > 0) {
                            position = (f.coerceIn(0f, 1f) * duration).toInt()
                            player.seekTo(position)
                        }
                    })
                }
            }
        }
    }
}

private fun clock(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Hands a result to another app (a one-off read grant), e.g. to send a video in a messenger. */
fun shareFile(context: Context, attachment: Attachment) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(attachment.path))
    val mime = context.contentResolver.getType(uri) ?: when {
        attachment.isVideo -> "video/*"
        attachment.isAudio -> "audio/*"
        else -> "image/*"
    }
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
