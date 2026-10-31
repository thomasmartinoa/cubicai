package dev.martin.cubicai.net

import android.util.Base64
import dev.martin.cubicai.data.objects
import dev.martin.cubicai.data.str
import dev.martin.cubicai.data.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** Something the PC's ComfyUI can make, as listed by the gateway. */
data class Generator(
    val id: String,
    val name: String,
    /** image, video or audio: what comes back. */
    val kind: String,
    /** create, edit, tool, animate, music or speech: how it's used. */
    val mode: String,
    val note: String,
    /** none, optional or required: whether it takes a photo. */
    val image: String,
    val audio: String,
    val prompt: Boolean,
    val lyrics: Boolean,
    val aspects: List<String>,
    val defaultSeconds: Int,
    val maxSeconds: Int,
    val languages: List<String>,
    /** Built-in voices for speech without a recording of its own ("woman", "man"). */
    val voices: List<String> = emptyList(),
    /** Whether a fixed seed can be given (to repeat a result or vary only the prompt). */
    val seed: Boolean = false,
    /** Sampling steps and CFG, only where tuning them helps; the defaults are the workflow's own. */
    val steps: Tune? = null,
    val cfg: Tune? = null,
    /** The workflow's negative prompt ("what to avoid"), when it has one. */
    val negativeDefault: String? = null,
)

data class Tune(val default: Double, val min: Double, val max: Double)

data class MediaFile(val index: Int, val name: String, val mime: String, val kind: String)

/** Where a job on the PC has got to. [preview] is the latest in-progress frame, when ComfyUI sends one. */
data class MediaProgress(
    val state: String,
    val phase: String,
    val step: Int = 0,
    val steps: Int = 0,
    val progress: Float = 0f,
    val files: List<MediaFile> = emptyList(),
    val error: String? = null,
    val preview: ByteArray? = null,
    /** The seed the PC used, so the same picture can be made again with a changed prompt. */
    val seed: Long? = null,
) {
    val finished get() = state == "done" || state == "error" || state == "cancelled"
}

/** The PC no longer has the job (it restarted, or the result was already collected and deleted). */
class MediaGoneException : ApiException("Your PC lost track of this one (it restarted, or the result waited over an hour). Tap Retry to make it again.")

private fun Generator(o: JSONObject) = Generator(
    id = o.getString("id"),
    name = o.optString("name"),
    kind = o.optString("kind"),
    mode = o.optString("mode"),
    note = o.optString("note"),
    image = o.optString("image", "none"),
    audio = o.optString("audio", "none"),
    prompt = o.optBoolean("prompt", true),
    lyrics = o.optBoolean("lyrics"),
    aspects = o.optJSONArray("aspects").strings(),
    defaultSeconds = o.optJSONObject("seconds")?.optInt("default") ?: 0,
    maxSeconds = o.optJSONObject("seconds")?.optInt("max") ?: 0,
    languages = o.optJSONArray("languages").strings(),
    voices = o.optJSONArray("voices").strings(),
    seed = o.optBoolean("seed"),
    steps = o.optJSONObject("steps")?.let(::Tune),
    cfg = o.optJSONObject("cfg")?.let(::Tune),
    negativeDefault = if (o.has("negative_default")) o.optString("negative_default") else null,
)

private fun Tune(o: JSONObject) = Tune(o.optDouble("default"), o.optDouble("min"), o.optDouble("max"))

private fun progressFrom(o: JSONObject, last: MediaProgress?) = MediaProgress(
    state = o.optString("state"),
    phase = o.optString("phase"),
    step = o.optInt("step"),
    steps = o.optInt("steps"),
    progress = o.optDouble("progress", 0.0).toFloat(),
    files = o.optJSONArray("files").objects().map {
        MediaFile(it.getInt("index"), it.optString("name"), it.optString("mime"), it.optString("kind"))
    },
    error = o.optJSONObject("error")?.str("message") ?: o.str("error"),
    // Previews come only when there's a new one; keep showing the last until then.
    preview = o.str("preview")?.let { Base64.decode(it, Base64.DEFAULT) } ?: last?.preview?.takeIf { o.optString("state") != "done" },
    seed = if (o.has("seed")) o.optLong("seed") else null,
)

private suspend fun <T> LlmClient.pcCall(
    baseUrls: List<String>,
    token: String,
    pin: String,
    build: Request.Builder.(String) -> Unit,
    read: (okhttp3.Response) -> T,
): T = withContext(Dispatchers.IO) {
    var call: Call? = null
    val cancelHandle = cancelOnStop { call?.cancel() }
    try {
        executeFirst(baseUrls, pin, { call = it; if (!isActive) it.cancel() }) { base ->
            Request.Builder().header("Authorization", "Bearer $token").apply { build(base) }.build()
        }.use { response ->
            if (response.code == 404 && response.request.url.encodedPath.contains("/media/jobs/")) throw MediaGoneException()
            if (!response.isSuccessful) throw ApiException(errorMessage(response.code, response.body.string(), pc = true))
            read(response)
        }
    } catch (e: IOException) {
        ensureActive()
        throw networkError(e, pin)
    } finally {
        cancelHandle?.dispose()
    }
}

suspend fun LlmClient.mediaGenerators(baseUrls: List<String>, token: String, pin: String): List<Generator> =
    pcCall(baseUrls, token, pin, { url(it + "/media/generators") }) { r ->
        JSONObject(r.body.string()).optJSONArray("generators").objects().map(::Generator)
    }

/** Starts a job on the PC and returns its id. Photos and voice samples travel inside the request. */
suspend fun LlmClient.submitMedia(
    baseUrls: List<String>, token: String, pin: String, params: JSONObject, image: File?, audio: File?,
): String {
    val body = JSONObject(params.toString())
    image?.let { body.put("image", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
    audio?.let { body.put("audio", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
    val payload = body.toString().toRequestBody(jsonType)
    return pcCall(baseUrls, token, pin, { url(it + "/media/jobs").post(payload) }) { r -> JSONObject(r.body.string()).getString("id") }
}

/**
 * Follows a job's progress until it ends. Returns the final state; throws if the connection drops first
 * (the job carries on on the PC, so the caller can follow it again).
 */
suspend fun LlmClient.followMedia(
    baseUrls: List<String>, token: String, pin: String, jobId: String, onProgress: (MediaProgress) -> Unit,
): MediaProgress = pcCall(baseUrls, token, pin, { url("$it/media/jobs/$jobId/events").header("Accept", "text/event-stream") }) { r ->
    val source = r.body.source()
    var last: MediaProgress? = null
    while (true) {
        val line = source.readUtf8Line() ?: break
        if (!line.startsWith("data:")) continue
        val json = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: continue
        val progress = progressFrom(json, last)
        last = progress
        onProgress(progress)
        if (progress.finished) return@pcCall progress
    }
    throw IOException("progress stream ended early")
}

/** Downloads one result; the PC deletes its copy once every file of the job has been fetched. */
suspend fun LlmClient.downloadMedia(baseUrls: List<String>, token: String, pin: String, jobId: String, index: Int, dest: File) =
    pcCall(baseUrls, token, pin, { url("$it/media/jobs/$jobId/files/$index") }) { r ->
        val tmp = File(dest.path + ".part")
        try {
            r.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) throw IOException("couldn't store the file")
        } finally {
            tmp.delete() // a cut-off download leaves nothing behind; the retry starts over
        }
    }

suspend fun LlmClient.cancelMedia(baseUrls: List<String>, token: String, pin: String, jobId: String) {
    runCatching { pcCall(baseUrls, token, pin, { url("$it/media/jobs/$jobId").delete() }) { } }
}

/** Keeps a copy of a saved result on the PC, in its CubicAI folder. */
suspend fun LlmClient.saveOnPc(baseUrls: List<String>, token: String, pin: String, kind: String, name: String, file: File, mime: String) {
    val query = "kind=${URLEncoder.encode(kind, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}"
    pcCall(baseUrls, token, pin, { url("$it/media/save?$query").post(file.asRequestBody(mime.toMediaType())) }) { }
}
