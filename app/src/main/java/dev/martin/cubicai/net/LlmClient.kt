package dev.martin.cubicai.net

import dev.martin.cubicai.data.SearchMode
import dev.martin.cubicai.data.objects
import dev.martin.cubicai.data.str
import dev.martin.cubicai.data.strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.util.Base64
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface StreamEvent {
    data class Text(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class Citation(val url: String) : StreamEvent
    data class Status(val text: String) : StreamEvent
}

class ApiException(message: String) : Exception(message)

private val NOT_CHAT = listOf("tts", "live", "image", "audio", "transcribe", "robotics", "computer-use", "customtools", "omni", "nano-banana", "argon")

/** A message ready to send; images are already data URLs. */
data class ApiMessage(val role: String, val text: String, val images: List<String> = emptyList())

data class ModelInfo(
    val id: String,
    val vision: Boolean,
    val free: Boolean,
    val sizeBytes: Long = 0,
    val recommended: Boolean = false,
    /** Tokens of conversation the model sees at once (the PC's setting, or the provider's limit). */
    val contextTokens: Int = 0,
)

/**
 * Talks to any OpenAI-compatible endpoint. Plain chats use /chat/completions; when web search is on
 * for xAI we switch to /responses, which is where Grok's server-side web_search and x_search tools live.
 */
class LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // reasoning models can go quiet for a while before answering
        .build()

    private val jsonType = "application/json".toMediaType()
    private val pinnedClients = mutableMapOf<String, OkHttpClient>()

    private fun clientFor(pin: String?): OkHttpClient = if (pin == null) http else synchronized(pinnedClients) {
        pinnedClients.getOrPut(pin) { pinnedClient(pin) }
    }

    /**
     * Trusts exactly one certificate: the paired PC's self-signed one, matched by SHA-256.
     * Hostname checks are skipped because the pin already identifies the server (it's reached by LAN IP).
     */
    private fun pinnedClient(fingerprint: String): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Client certificates are not supported")

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("No server certificate")
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())) {
                    throw CertificateException("This isn't your paired PC (certificate mismatch). Pair again in Settings.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return http.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS) // short, so an unreachable home address falls back to Tailscale quickly
            .readTimeout(15, TimeUnit.MINUTES) // big local models can take a while to load
            .build()
    }

    /**
     * Runs [cancel] as soon as the calling coroutine is cancelled (Stop, new chat...). A plain completion handler
     * would wait for the blocking read to end by itself, so Stop did nothing until the whole reply had arrived.
     */
    @OptIn(InternalCoroutinesApi::class)
    private fun CoroutineScope.cancelOnStop(cancel: () -> Unit) =
        coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cancel() }

    /** The address that answered last, tried first next time (e.g. Tailscale while away from home). */
    @Volatile private var preferredUrl: String? = null

    /**
     * Sends a request to the first base URL that accepts the connection. Falls through to the next
     * one only when connecting fails, never after a response has started.
     */
    private fun executeFirst(baseUrls: List<String>, pin: String?, onCall: (Call) -> Unit, build: (String) -> Request): Response {
        val ordered = baseUrls.sortedByDescending { it == preferredUrl }
        var last: IOException? = null
        for (url in ordered) {
            val call = clientFor(pin).newCall(build(url))
            onCall(call)
            try {
                return call.execute().also { preferredUrl = url }
            } catch (e: IOException) {
                if (call.isCanceled()) throw e
                last = e
            }
        }
        throw last ?: IOException("No address to connect to")
    }

    suspend fun stream(
        baseUrls: List<String>,
        apiKey: String,
        model: String,
        messages: List<ApiMessage>,
        search: SearchMode,
        webSearch: Boolean,
        pin: String? = null,
        onEvent: (StreamEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val useResponses = webSearch && search == SearchMode.XAI_TOOLS
        val body = if (useResponses) responsesBody(model, messages)
        else chatBody(model, messages, webPlugin = webSearch && search == SearchMode.OPENROUTER_WEB)
            .apply { if (webSearch && search == SearchMode.LOCAL_PC) put("cubic_web_search", true) }

        val payload = body.toString().toRequestBody(jsonType)
        var call: Call? = null
        val cancelHandle = cancelOnStop { call?.cancel() }
        try {
            executeFirst(baseUrls, pin, { call = it; if (!isActive) it.cancel() }) { baseUrl ->
                Request.Builder()
                    .url(baseUrl + if (useResponses) "/responses" else "/chat/completions")
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .apply { if (search == SearchMode.OPENROUTER_WEB) header("X-Title", "CubicAI") } // OpenRouter's app label only
                    .header("Accept", "text/event-stream")
                    .post(payload)
                    .build()
            }.use { response ->
                if (!response.isSuccessful) throw ApiException(errorMessage(response.code, response.body.string()))
                val source = response.body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    if (useResponses) parseResponsesEvent(json, onEvent) else parseChatChunk(json, onEvent)
                }
            }
        } catch (e: IOException) {
            ensureActive() // a cancelled call surfaces as IOException; rethrow as cancellation instead
            throw networkError(e, pin)
        } finally {
            cancelHandle?.dispose()
        }
    }

    /**
     * Creates an image with Gemini's native generateContent API (the OpenAI-style images endpoint
     * doesn't cover these models). Optional [reference] images let the prompt edit or build on a photo.
     * Returns the image bytes and their mime type.
     */
    suspend fun generateImage(
        apiKey: String,
        model: String,
        prompt: String,
        reference: List<String> = emptyList(),
    ): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        reference.forEach { url ->
            val data = url.substringAfter("base64,")
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", url.substringAfter("data:").substringBefore(";")).put("data", data)))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE")))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val call = http.newCall(request)
        val handle = cancelOnStop { call.cancel() }
        try {
            call.execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw ApiException(
                        if (response.code == 429) "Image generation isn't included in the free Google plan. Turn on billing for this key at aistudio.google.com, or try again tomorrow."
                        else errorMessage(response.code, text)
                    )
                }
                val partsOut = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts").objects()
                val inline = partsOut.firstNotNullOfOrNull { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") }
                    ?: throw ApiException(
                        partsOut.firstNotNullOfOrNull { it.str("text") }?.take(300) ?: "The model didn't return an image. Try rephrasing."
                    )
                Base64.decode(inline.getString("data"), Base64.DEFAULT) to (inline.str("mimeType") ?: inline.str("mime_type") ?: "image/png")
            }
        } catch (e: IOException) {
            ensureActive()
            throw networkError(e, null)
        } finally {
            handle?.dispose()
        }
    }

    suspend fun listModels(baseUrls: List<String>, apiKey: String, pin: String? = null): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            executeFirst(baseUrls, pin, {}) { baseUrl ->
                Request.Builder()
                    .url("$baseUrl/models")
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
            }.use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw ApiException(errorMessage(response.code, text))
                val google = baseUrls.any { "generativelanguage.googleapis.com" in it }
                JSONObject(text).optJSONArray("data").objects().mapNotNull { o ->
                    val id = o.getString("id").removePrefix("models/")
                    // Google lists speech, live, image and robotics models too; only offer ones that can chat.
                    if (google && (!id.startsWith("gemini") || NOT_CHAT.any { it in id } || id.startsWith("gemini-2"))) return@mapNotNull null
                    val modalities = o.optJSONObject("architecture")?.optJSONArray("input_modalities").strings() +
                        o.optJSONArray("input_modalities").strings()
                    val pricing = o.optJSONObject("pricing")
                    val free = id.endsWith(":free") ||
                        (pricing?.optString("prompt") == "0" && pricing.optString("completion") == "0")
                    ModelInfo(
                        id,
                        vision = "image" in modalities || (google && id.startsWith("gemini")),
                        free = free,
                        sizeBytes = o.optLong("size"),
                        recommended = o.optBoolean("cubic_recommended"),
                        contextTokens = o.optInt("context_length").takeIf { it > 0 } ?: if (google) 1_048_576 else 0,
                    ).let { if (google && id == "gemini-3.5-flash") it.copy(recommended = true) else it }
                }.distinctBy { it.id } // the picker keys its list by id; a duplicate would crash it
                    .sortedWith(compareByDescending<ModelInfo> { it.recommended }.thenBy { it.id })
            }
        } catch (e: IOException) {
            throw networkError(e, pin)
        }
    }

    // --- Chat Completions -----------------------------------------------------------------------

    private fun chatBody(model: String, messages: List<ApiMessage>, webPlugin: Boolean) = JSONObject().apply {
        put("model", model)
        put("stream", true)
        put("messages", JSONArray(messages.map { m ->
            JSONObject().put("role", m.role).put("content", if (m.images.isEmpty()) m.text else JSONArray().apply {
                if (m.text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", m.text))
                m.images.forEach { url ->
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
                }
            })
        }))
        if (webPlugin) put("plugins", JSONArray().put(JSONObject().put("id", "web")))
    }

    private fun parseChatChunk(json: JSONObject, onEvent: (StreamEvent) -> Unit) {
        json.optJSONObject("error")?.let { throw ApiException(it.str("message") ?: "Provider error") }
        json.str("cubic_status")?.let { onEvent(StreamEvent.Status(it)) } // progress from the PC gateway
        json.optJSONArray("citations").strings().filter { it.startsWith("http") }.forEach { onEvent(StreamEvent.Citation(it)) }

        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
        (delta.str("reasoning_content") ?: delta.str("reasoning"))
            ?.takeIf { it.isNotEmpty() }?.let { onEvent(StreamEvent.Reasoning(it)) }
        delta.str("content")?.takeIf { it.isNotEmpty() }?.let { onEvent(StreamEvent.Text(it)) }
        delta.optJSONArray("annotations").objects().forEach { a ->
            a.optJSONObject("url_citation")?.str("url")?.let { onEvent(StreamEvent.Citation(it)) }
        }
    }

    // --- Responses API (xAI search tools) -------------------------------------------------------

    private fun responsesBody(model: String, messages: List<ApiMessage>) = JSONObject().apply {
        put("model", model)
        put("stream", true)
        put("input", JSONArray(messages.map { m ->
            JSONObject().put("role", m.role).put("content", if (m.images.isEmpty()) m.text else JSONArray().apply {
                if (m.text.isNotEmpty()) put(JSONObject().put("type", "input_text").put("text", m.text))
                m.images.forEach { url -> put(JSONObject().put("type", "input_image").put("image_url", url)) }
            })
        }))
        put("tools", JSONArray()
            .put(JSONObject().put("type", "web_search"))
            .put(JSONObject().put("type", "x_search")))
    }

    private fun parseResponsesEvent(json: JSONObject, onEvent: (StreamEvent) -> Unit) {
        val type = json.optString("type")
        when {
            type == "response.output_text.delta" ->
                json.str("delta")?.let { onEvent(StreamEvent.Text(it)) }

            type.startsWith("response.reasoning") && type.endsWith(".delta") ->
                json.str("delta")?.let { onEvent(StreamEvent.Reasoning(it)) }

            type == "response.output_text.annotation.added" ->
                json.optJSONObject("annotation")?.str("url")?.let { onEvent(StreamEvent.Citation(it)) }

            type == "response.output_item.added" -> {
                val item = json.optJSONObject("item") ?: return
                searchStatus(item.optString("type") + " " + item.optString("name"))?.let { onEvent(StreamEvent.Status(it)) }
            }

            type.contains("search") && (type.endsWith(".in_progress") || type.endsWith(".searching")) ->
                searchStatus(type)?.let { onEvent(StreamEvent.Status(it)) }

            type == "response.completed" -> {
                val response = json.optJSONObject("response") ?: return
                val citations = response.optJSONArray("citations")
                citations.strings().filter { it.startsWith("http") }.forEach { onEvent(StreamEvent.Citation(it)) }
                citations.objects().mapNotNull { it.str("url") }.forEach { onEvent(StreamEvent.Citation(it)) }
                response.optJSONArray("output").objects()
                    .flatMap { it.optJSONArray("content").objects() }
                    .flatMap { it.optJSONArray("annotations").objects() }
                    .mapNotNull { it.str("url") }
                    .forEach { onEvent(StreamEvent.Citation(it)) }
            }

            type == "error" ->
                throw ApiException(json.str("message") ?: json.optJSONObject("error")?.str("message") ?: "Provider error")

            type == "response.failed" ->
                throw ApiException(json.optJSONObject("response")?.optJSONObject("error")?.str("message") ?: "Request failed")
        }
    }

    private fun searchStatus(text: String): String? = when {
        text.contains("x_search") || text.contains("x_keyword") || text.contains("x_semantic") -> "Searching X…"
        text.contains("search") || text.contains("browse") -> "Searching the web…"
        else -> null
    }

    private fun networkError(e: IOException, pin: String?): ApiException {
        val detail = e.message ?: e.javaClass.simpleName
        return ApiException(
            if (pin != null) "Can't reach your PC ($detail). Check the server is on, and that you're on home Wi-Fi or Tailscale."
            else "Network error: $detail"
        )
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            // Google wraps some errors in a one-element array.
            val o = if (body.trimStart().startsWith("[")) JSONArray(body).getJSONObject(0) else JSONObject(body)
            o.optJSONObject("error")?.str("message") ?: o.str("error") ?: o.str("message")
        }.getOrNull()?.lineSequence()?.firstOrNull() ?: body.take(300)
        val hint = when (code) {
            401, 403 -> "Check your API key in Settings."
            402 -> "Your account is out of credits."
            404 -> "Model not found. Pick another model."
            429 -> "Rate limited or out of free quota. Wait a moment, pick another model, or check your plan."
            503 -> "The model is overloaded right now. Try again or pick another model."
            else -> null
        }
        return listOfNotNull("Error $code: $detail".trim(), hint).joinToString("\n")
    }
}
