package dev.martin.cubicai.net

import dev.martin.cubicai.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** The PC answered but wouldn't take the call (voice not set up, pairing revoked): trying again won't help. */
class CallRefused(message: String) : ApiException(message)

/** A voice the PC can speak with. */
data class VoiceInfo(val id: String, val name: String, val about: String, val gender: String, val accent: String)

/**
 * The live connection of a voice call: the microphone goes to the PC as 16 kHz PCM, and what the PC hears
 * (`speech`, `partial`, `final`) and says (`seg` followed by 24 kHz PCM) comes back. See server/cubicai_voice.py.
 */
class VoiceLink(private val client: LlmClient) {
    interface Listener {
        fun onEvent(event: JSONObject)
        fun onAudio(pcm: ByteArray)
        /** The connection ended; [error] is null when we hung up ourselves. */
        fun onClosed(error: String?)
    }

    @Volatile private var socket: WebSocket? = null

    /** Opens the call on the first of the PC's addresses that takes it. Throws [ApiException] when none does. */
    suspend fun connect(baseUrls: List<String>, token: String, pin: String, listener: Listener) {
        val urls = try {
            client.candidates(baseUrls)
        } catch (e: OffNetworkException) {
            throw client.networkError(e, pin)
        }
        val http = client.clientFor(pin).newBuilder()
            .pingInterval(15, TimeUnit.SECONDS) // the PC drops a call it hasn't heard from in 90 s
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        var last: IOException? = null
        for (url in urls) {
            val request = Request.Builder().url("$url/voice/call").header("Authorization", "Bearer $token").build()
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

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onAudio(bytes.toByteArray())

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (socket === webSocket) listener.onClosed(if (code == 1000) null else "The call to your PC ended.")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!opened) {
                            // The PC answered but turned the call down (not set up, not paired): its words say why.
                            val failure = if (response != null) {
                                val body = runCatching { response.body.string() }.getOrDefault("")
                                CallRefused(client.errorMessage(response.code, body, pc = true))
                            } else t
                            if (cont.isActive) cont.resume(Result.failure(failure))
                        } else if (socket === webSocket) {
                            listener.onClosed(client.networkError(t as? IOException ?: IOException(t), pin).message)
                        }
                    }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
            val ws = outcome.getOrElse { e ->
                if (e is ApiException) throw e
                last = e as? IOException ?: IOException(e)
                null
            } ?: continue
            client.answered(url)
            socket = ws
            return
        }
        throw client.networkError(last ?: IOException("No address to connect to"), pin)
    }

    fun send(event: JSONObject) {
        socket?.send(event.toString())
    }

    /** Microphone audio. Dropped rather than queued when the network can't keep up: late audio is useless. */
    fun sendAudio(pcm: ByteArray, length: Int) {
        val ws = socket ?: return
        if (ws.queueSize() < 256 * 1024) ws.send(pcm.toByteString(0, length))
    }

    fun close() {
        val ws = socket
        socket = null
        ws?.close(1000, null)
    }

    /** The PC's voices, or why there are none (voice calls not set up there). */
    suspend fun voices(baseUrls: List<String>, token: String, pin: String): Pair<List<VoiceInfo>, String?> = withContext(Dispatchers.IO) {
        try {
            client.executeFirst(baseUrls, pin, {}) { url -> Request.Builder().url("$url/voice/voices").header("Authorization", "Bearer $token").build() }
                .use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) throw ApiException(client.errorMessage(response.code, text, pc = true))
                    val json = JSONObject(text)
                    val list = json.optJSONArray("voices")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                            VoiceInfo(it.optString("id"), it.optString("name"), it.optString("about"), it.optString("gender"), it.optString("accent"))
                        }
                    }.orEmpty()
                    list to (if (json.optBoolean("ready")) null else "Voice calls need ${json.str("missing") ?: "setting up"} on your PC.")
                }
        } catch (e: IOException) {
            throw client.networkError(e, pin)
        }
    }

    /** A few seconds of [voice] at [speed], as 16-bit PCM at 24 kHz. */
    suspend fun preview(baseUrls: List<String>, token: String, pin: String, voice: String, speed: Float): ByteArray = withContext(Dispatchers.IO) {
        try {
            client.executeFirst(baseUrls, pin, {}) { url ->
                Request.Builder().url("$url/voice/preview?voice=$voice&speed=$speed").header("Authorization", "Bearer $token").build()
            }.use { response ->
                if (!response.isSuccessful) throw ApiException(client.errorMessage(response.code, response.body.string(), pc = true))
                response.body.bytes()
            }
        } catch (e: IOException) {
            throw client.networkError(e, pin)
        }
    }
}
