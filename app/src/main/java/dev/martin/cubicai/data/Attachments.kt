package dev.martin.cubicai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File

/** Turns picked/shared/captured content into attachments the model can read. */
object AttachmentProcessor {
    private const val MAX_IMAGE_EDGE = 1600
    private const val PDF_PAGE_WIDTH = 1400
    private const val MAX_PDF_PAGES = 10
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 150_000

    fun process(context: Context, uri: Uri, outDir: File): List<Attachment> {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "file"
        return when {
            mime.startsWith("image/") -> listOf(image(context, uri, name, outDir))
            mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> pdf(context, uri, name, outDir)
            mime.startsWith("audio/") -> listOf(audio(context, uri, name, outDir))
            else -> listOf(text(context, uri, name, outDir))
        }
    }

    fun dataUrl(attachment: Attachment): String? = runCatching {
        "data:image/jpeg;base64," + Base64.encodeToString(File(attachment.path).readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    fun readText(attachment: Attachment): String = runCatching { File(attachment.path).readText() }.getOrDefault("")

    private fun image(context: Context, uri: Uri, name: String, outDir: File): Attachment {
        // ImageDecoder applies EXIF rotation, so camera photos come out upright.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_IMAGE_EDGE.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return saveJpeg(bitmap, name, outDir)
    }

    /** Sound files are kept as they are: a voice sample for speech made on the PC. Chat models don't get them. */
    private fun audio(context: Context, uri: Uri, name: String, outDir: File): Attachment {
        val ext = name.substringAfterLast('.', "m4a").lowercase().take(5)
        val file = File(outDir, "${newId()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            file.outputStream().use { out ->
                if (input.copyTo(out) > MAX_FILE_BYTES) {
                    file.delete()
                    throw IllegalArgumentException("$name is too large (max 8 MB)")
                }
            }
        }
        return Attachment(Attachment.KIND_AUDIO, name, file.absolutePath)
    }

    /** PDFs are rendered page by page into images so vision models can read them, layout included. */
    private fun pdf(context: Context, uri: Uri, name: String, outDir: File): List<Attachment> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Can't open \"$name\"")
        return PdfRenderer(pfd).use { renderer ->
            (0 until minOf(renderer.pageCount, MAX_PDF_PAGES)).map { i ->
                renderer.openPage(i).use { page ->
                    val scale = PDF_PAGE_WIDTH.toFloat() / page.width
                    val bitmap = Bitmap.createBitmap(PDF_PAGE_WIDTH, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    saveJpeg(bitmap, "$name · p${i + 1}", outDir)
                }
            }
        }
    }

    private fun text(context: Context, uri: Uri, name: String, outDir: File): Attachment {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't open \"$name\"")
        require(bytes.size <= MAX_FILE_BYTES) { "\"$name\" is too large (max 8 MB)" }
        val sample = bytes.copyOf(minOf(bytes.size, 8192))
        require(sample.none { it == 0.toByte() }) {
            "Can't read \"$name\": only images, PDFs and text/code files are supported"
        }
        val file = File(outDir, newId() + ".txt")
        file.writeText(String(bytes, Charsets.UTF_8).take(MAX_TEXT_CHARS))
        return Attachment(Attachment.KIND_TEXT, name, file.absolutePath)
    }

    private fun saveJpeg(source: Bitmap, name: String, outDir: File): Attachment {
        val bitmap = if (source.hasAlpha()) {
            // JPEG has no alpha; flatten onto white so transparent PNGs don't turn black.
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(source, 0f, 0f, null)
            }
        } else source
        val file = File(outDir, newId() + ".jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return Attachment(Attachment.KIND_IMAGE, name, file.absolutePath)
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
