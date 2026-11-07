package dev.martin.cubicai.net

import android.util.Base64
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.objects
import dev.martin.cubicai.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** The realtime voice model a Grok call talks to (`grok-voice-latest` follows xAI's newest). */
const val GROK_VOICE_MODEL = "grok-voice-latest"

/** The voice used when a behaviour has none chosen for Grok: by its PC voice's gender (Kokoro ids: "af_…" / "am_…"). */
fun grokDefaultVoice(kokoroVoice: String): String = if (kokoroVoice.getOrNull(1) == 'm') "rex" else "eve"

/**
 * A call with Grok's own realtime voice (xAI's Voice Agent API): the phone streams the microphone to xAI as 16 kHz
 * PCM, and Grok listens (server-side turn detection), answers and speaks back 24 kHz PCM, all on one WebSocket.
 * Events are xAI's (OpenAI Realtime style) JSON; the caller reads them. No PC involved, so it works anywhere.
 */
class GrokVoice(private val client: LlmClient) {
    interface Listener {
        fun onEvent(event: JSONObject)
        /** The connection ended; [error] is null when we hung up ourselves. */
        fun onClosed(error: String?)
    }

    @Volatile private var socket: WebSocket? = null

    suspend fun connect(apiKey: String, listener: Listener) {
        if (apiKey.isBlank()) throw CallRefused("Add your xAI API key in Settings to call Grok.")
        val http = client.clientFor(null).newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val url = Providers.XAI.baseUrl.replaceFirst("https://", "wss://") + "/realtime?model=$GROK_VOICE_MODEL"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()
        val outcome = suspendCancellableCoroutine { cont ->
            var opened = false
            val ws = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    if (cont.isActive) cont.resume(Result.success(webSocket))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { JSONObject(text) }.getOrNull()?.let(listener::onEvent)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) {
                        socket = null
                        listener.onClosed(if (code == 1000) null else "Grok hung up${reason.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened) {
                        val error: Exception = when (response?.code) {
                            401, 403 -> CallRefused("xAI didn't accept your API key for voice. Check it in Settings → Grok.")
                            404 -> CallRefused("Your xAI key can't use Grok's voice ($GROK_VOICE_MODEL).")
                            429 -> ApiException("xAI is busy or your credits ran out. Try again in a moment.")
                            null -> client.networkError(t as? IOException ?: IOException(t), null)
                            else -> ApiException("xAI didn't take the call (error ${response.code}).")
                        }
                        if (cont.isActive) cont.resume(Result.failure(error))
                    } else if (socket === webSocket) {
                        socket = null
                        listener.onClosed("the connection to Grok dropped")
                    }
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
        socket = outcome.getOrThrow()
    }

    fun send(event: JSONObject) {
        socket?.send(event.toString())
    }

    fun sendAudio(pcm: ByteArray, length: Int) {
        val s = socket ?: return
        s.send(
            JSONObject().put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(pcm, 0, length, Base64.NO_WRAP))
                .toString(),
        )
    }

    fun close() {
        socket?.let {
            socket = null
            it.close(1000, null)
        }
    }

    /** Grok's voices (`GET /tts/voices`). */
    suspend fun voices(apiKey: String): List<VoiceInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ApiException("Add your xAI API key in Settings to hear Grok's voices.")
        val request = Request.Builder().url("${Providers.XAI.baseUrl}/tts/voices").header("Authorization", "Bearer $apiKey").build()
        client.clientFor(null).newCall(request).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw ApiException("xAI didn't list its voices (error ${r.code}).")
            grokVoices(JSONObject(text))
        }
    }

    /** A short sample of [voice] as 16-bit PCM at 24 kHz (`POST /tts`). */
    suspend fun preview(apiKey: String, voice: String, speed: Float): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("text", "Hi, this is how I sound. What would you like to talk about?")
            .put("voice_id", voice)
            .put("language", "en")
            .put("speed", speed.toDouble().coerceIn(0.7, 1.5))
            .put("output_format", JSONObject().put("codec", "pcm").put("sample_rate", 24000))
        val request = Request.Builder().url("${Providers.XAI.baseUrl}/tts")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.clientFor(null).newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("xAI couldn't play this voice (error ${r.code}).")
            r.body.bytes()
        }
    }
}

/** The voice list from `GET /tts/voices`, grouped under one heading; xAI may or may not say more than the name. */
internal fun grokVoices(json: JSONObject): List<VoiceInfo> = json.optJSONArray("voices").objects().mapNotNull { v ->
    val id = v.str("voice_id") ?: v.str("id") ?: return@mapNotNull null
    VoiceInfo(
        id = id,
        name = v.str("name")?.takeIf { it.isNotBlank() } ?: id.replaceFirstChar { it.uppercase() },
        about = v.str("description") ?: v.str("tone") ?: "Grok voice",
        gender = v.str("gender")?.replaceFirstChar { it.uppercase() } ?: "",
        accent = "Grok's voices",
    )
}

/**
 * The first `session.update` of a Grok call. Only fields xAI documents are here, so a mistake can't make xAI
 * keep its defaults for everything; [grokTurnUpdate] carries the rest separately.
 */
internal fun grokSession(instructions: String, voice: String, speed: Float, search: Boolean, checkInMs: Int?): JSONObject {
    val turn = JSONObject().put("type", "server_vad")
    if (checkInMs != null) turn.put("idle_timeout_ms", checkInMs)
    val session = JSONObject()
        .put("instructions", instructions)
        .put("voice", voice)
        .put("turn_detection", turn)
        .put(
            "audio",
            JSONObject()
                .put("input", JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 16000)))
                .put("output", JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000)).put("speed", speed.toDouble().coerceIn(0.7, 1.5))),
        )
    if (search) session.put("tools", JSONArray().put(JSONObject().put("type", "web_search")).put(JSONObject().put("type", "x_search")))
    return JSONObject().put("type", "session.update").put("session", session)
}

/** How long a pause ends the user's turn: OpenAI's field name, which xAI may not take (it then just says so). */
internal fun grokTurnUpdate(patienceMs: Int, checkInMs: Int?): JSONObject {
    val turn = JSONObject().put("type", "server_vad").put("silence_duration_ms", patienceMs)
    if (checkInMs != null) turn.put("idle_timeout_ms", checkInMs)
    return JSONObject().put("type", "session.update").put("session", JSONObject().put("turn_detection", turn))
}
