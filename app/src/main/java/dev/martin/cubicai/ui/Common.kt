package dev.martin.cubicai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CubicAI", text))
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

/** Copies an image into Pictures/CubicAI. Only ever called from a tap on "Save", never automatically. */
fun saveToGallery(context: Context, path: String): Boolean = runCatching {
    val source = File(path)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "CubicAI-${System.currentTimeMillis()}.${source.extension.ifBlank { "png" }}")
        put(MediaStore.Images.Media.MIME_TYPE, if (source.extension == "jpg") "image/jpeg" else "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CubicAI")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    context.contentResolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
}.isSuccess

fun shortModelName(model: String) = model.substringAfterLast('/').removeSuffix(":free")

fun domainOf(url: String) = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url

private fun decodeSampled(path: String, maxSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Image from app-private storage, decoded off the main thread at roughly the size it's shown. */
@Composable
fun LocalImage(
    path: String,
    modifier: Modifier = Modifier,
    maxSize: Int = 512,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val image by produceState<ImageBitmap?>(null, path, maxSize) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, maxSize)?.asImageBitmap() }
    }
    val bitmap = image
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

@Composable
fun ImageViewer(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            LocalImage(path, Modifier.fillMaxSize(), maxSize = 2048, contentScale = ContentScale.Fit)
            // Tapping anywhere closes it too, but a visible button says so.
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, "Close", tint = Color.White)
            }
        }
    }
}

/** Dropdown with a hairline edge and a raised tone, so it stands apart from the page (in dark mode it otherwise blends in). */
@Composable
fun CubicMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    shape = MaterialTheme.shapes.small,
    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    content = content,
)
