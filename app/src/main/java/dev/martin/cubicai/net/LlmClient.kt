package dev.martin.cubicai.net

import dev.martin.cubicai.data.SearchMode
import dev.martin.cubicai.data.ToolCall
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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

sealed interface StreamEvent {
    data class Text(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class Citation(val url: String) : StreamEvent
    data class Status(val text: String) : StreamEvent
    /** Tokens the model read (the whole conversation) and wrote for this reply. */
    data class Usage(val prompt: Int, val output: Int) : StreamEvent
    /** The model asked for a tool; sent once the reply has finished, with the arguments complete. */
    data class Call(val call: ToolCall) : StreamEvent
}

open class ApiException(message: String) : Exception(message)

private val NOT_CHAT = listOf("tts", "live", "image", "audio", "transcribe", "robotics", "computer-use", "customtools", "omni", "nano-banana", "argon")

/**
 * A message ready to send; images are already data URLs. An assistant message may carry the tool calls it made,
 * and a "tool" message answers one of them ([toolCallId]).
 */
data class ApiMessage(
    val role: String,
    val text: String,
    val images: List<String> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** Tools offered to the model: OpenAI-style function definitions, and whether it may call them this turn. */
data class Tools(val functions: JSONArray, val allowed: Boolean = true)

data class ModelInfo(
    val id: String,
    val vision: Boolean,
    val free: Boolean,
    val sizeBytes: Long = 0,
    val recommended: Boolean = false,
    /** Tokens of conversation the model sees at once (the PC's setting, or the provider's limit). */
    val contextTokens: Int = 0,
    /** Whether it can call tools; null when the provider doesn't say. */
    val tools: Boolean? = null,
)

/**
 * Talks to any OpenAI-compatible endpoint. Plain chats use /chat/completions; when web search is on
 * for xAI we switch to /responses, which is where Grok's server-side web_search and x_search tools live.
 */
/** The networks the phone is on right now: home Wi-Fi/Ethernet ([lan]) and a VPN such as Tailscale ([vpn]). */
data class Links(val lan: Boolean, val vpn: Boolean) {
    fun canReach(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBeforeLast(':')
        if (host.startsWith("[")) return if (host.startsWith("[fd7a:115c:a1e0:", ignoreCase = true)) vpn else lan || vpn
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return true
        val (a, b) = octets
        return when {
            a == 100 && b in 64..127 -> vpn // Tailscale
            a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) -> lan || vpn // a VPN may route home too
            else -> true
        }
    }
}

class OffNetworkException(val links: Links) : IOException("No usable network for the PC")

/** The address answered with "connection refused": a machine is there, but nothing listens on the port. */
private fun IOException?.isRefusal() = generateSequence<Throwable>(this) { it.cause }
    .any { it is java.net.ConnectException && it.message.orEmpty().contains("refused", ignoreCase = true) }

class LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // reasoning models can go quiet for a while before answering
        .build()

    internal val jsonType = "application/json".toMediaType()
    private val pinnedClients = mutableMapOf<String, OkHttpClient>()

    internal fun clientFor(pin: String?): OkHttpClient = if (pin == null) http else synchronized(pinnedClients) {
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
    internal fun CoroutineScope.cancelOnStop(cancel: () -> Unit) =
        coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cancel() }

    /**
     * The PC's addresses in the order to try them, without those the phone can't reach now (for connections that
     * aren't plain requests, like a call's WebSocket). Throws [OffNetworkException] when none is left.
     */
    internal fun candidates(baseUrls: List<String>): List<String> {
        val now = links?.invoke()
        val ordered = baseUrls.map { moved[it] ?: it }.distinct().sortedByDescending { it == preferredUrl }
            .filter { now == null || now.canReach(it) }
        if (ordered.isEmpty() && now != null) throw OffNetworkException(now)
        return ordered
    }

    internal fun answered(url: String) {
        preferredUrl = url
    }

    /** The address that answered last, tried first next time (e.g. Tailscale while away from home). */
    @Volatile private var preferredUrl: String? = null

    /** Reports the phone's networks; null tries every address. */
    @Volatile var links: (() -> Links)? = null

    /**
     * Looks for the paired PC on the home network when none of its addresses answers (see [LanFinder]), passing
     * over the addresses that were just tried.
     */
    @Volatile var findPc: ((fingerprint: String, tried: Set<String>) -> String?)? = null

    /** Told the PC's new home address ("https://ip:port") after [findPc] found it, so it can be stored. */
    @Volatile var onPcMoved: ((String) -> Unit)? = null

    /** Home base URLs that turned out to have moved, mapped to where the PC is now (for calls already under way). */
    private val moved = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var lastSearch = 0L

    /**
     * Sends a request to the first base URL that accepts the connection. Falls through to the next
     * one only when connecting fails, never after a response has started.
     */
    internal fun executeFirst(baseUrls: List<String>, pin: String?, onCall: (Call) -> Unit, build: (String) -> Request): Response {
        // An address the phone has no way to reach right now (Tailscale while it's off, home while not on Wi-Fi)
        // would only cost its connect timeout, so it's skipped; with nothing left, the error says why.
        val now = links?.invoke()
        val all = baseUrls.map { moved[it] ?: it }.distinct().sortedByDescending { it == preferredUrl }
        val ordered = all.filter { now == null || now.canReach(it) }
        if (ordered.isEmpty() && now != null) throw OffNetworkException(now)
        var last: IOException? = null
        for (url in ordered) {
            val call = clientFor(pin).newCall(build(url))
            onCall(call)
            try {
                return call.execute().also { preferredUrl = url }
            } catch (e: IOException) {
                if (call.isCanceled()) throw e
                // Keep the error from an address that answered (a refusal says more than the other one's timeout).
                if (last !is SSLException || e is SSLException) last = e
            }
        }
        // Nothing answered: the router may have given the PC a new address. Look for it on the home network
        // (at most every 30 s, so being away from home doesn't add the search to every request).
        val finder = findPc
        val home = baseUrls.firstOrNull()
        if (pin != null && finder != null && home != null && last !is SSLException && now?.lan != false &&
            System.currentTimeMillis() - lastSearch > 30_000
        ) {
            lastSearch = System.currentTimeMillis()
            // Compared by origin: the base URLs carry the API path ("/v1"), found addresses don't.
            val found = finder(pin, ordered.map { it.substringAfter("://").substringBefore('/').let { h -> "https://$h" } }.toSet())
            val suffix = home.substringAfter("://").substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
            val url = found?.let { it + suffix }
            if (url != null && url !in ordered) {
                val call = clientFor(pin).newCall(build(url))
                onCall(call)
                try {
                    return call.execute().also {
                        moved[home] = url
                        preferredUrl = url
                        onPcMoved?.invoke(found)
                    }
                } catch (e: IOException) {
                    if (call.isCanceled()) throw e
                }
            }
        }
        // Nothing answered and an address was skipped (Tailscale is off): saying so helps more than a timeout.
        // A refusal is clearer still: something is at the address but the server isn't running.
        if (now != null && ordered.size < all.size && last !is SSLException && !last.isRefusal()) throw OffNetworkException(now)
        if (now != null && now.vpn && !now.lan && last !is SSLException && !last.isRefusal()) throw OffNetworkException(now)
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
        tools: Tools? = null,
        /** Builds the messages again for a retry without tools (tool calls in the history become plain notes). */
        withoutTools: (() -> List<ApiMessage>)? = null,
        /** False asks the PC to skip a model's thinking (a voice call wants the answer straight away). */
        think: Boolean = true,
        onEvent: (StreamEvent) -> Unit,
    ) {
        try {
            streamOnce(baseUrls, apiKey, model, messages, search, webSearch, pin, tools, think, onEvent)
        } catch (e: ToolsRefused) {
            // Some models (or OpenRouter routes) don't take tools at all: answer without them rather than fail.
            streamOnce(baseUrls, apiKey, model, withoutTools?.invoke() ?: messages, search, webSearch, pin, null, think, onEvent)
        }
    }

    private class ToolsRefused(message: String) : ApiException(message)

    private suspend fun streamOnce(
        baseUrls: List<String>,
        apiKey: String,
        model: String,
        messages: List<ApiMessage>,
        search: SearchMode,
        webSearch: Boolean,
        pin: String?,
        tools: Tools?,
        think: Boolean,
        onEvent: (StreamEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val useResponses = webSearch && search == SearchMode.XAI_TOOLS
        val body = if (useResponses) responsesBody(model, messages, tools)
        else chatBody(model, messages, webPlugin = webSearch && search == SearchMode.OPENROUTER_WEB, tools = tools)
            .apply { if (webSearch && search == SearchMode.LOCAL_PC) put("cubic_web_search", true) }
            .apply { if (!think && search == SearchMode.LOCAL_PC) put("cubic_think", false) }
        val calls = sortedMapOf<Int, Array<String>>() // index → id, name, arguments (arguments arrive in pieces)

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
                if (!response.isSuccessful) {
                    val text = response.body.string()
                    val message = errorMessage(response.code, text, pc = pin != null)
                    if (tools != null && response.code in 400..499 && text.contains("tool", ignoreCase = true)) throw ToolsRefused(message)
                    throw ApiException(message)
                }
                val source = response.body.source()
                // A stream that just stops (server restarted, Wi-Fi dropped) must not pass for a finished reply.
                var finished = false
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") { finished = true; break }
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    if (useResponses) {
                        if (json.optString("type") == "response.completed") finished = true
                        parseResponsesEvent(json, calls, onEvent)
                    } else {
                        if (json.optJSONArray("choices")?.optJSONObject(0)?.isNull("finish_reason") == false) finished = true
                        parseChatChunk(json, calls, onEvent)
                    }
                }
                if (!finished) {
                    throw ApiException((if (pin != null) "The connection to your PC" else "The connection") + " dropped before the reply finished.")
                }
                calls.values.filter { it[1].isNotEmpty() }.forEachIndexed { i, (id, name, args) ->
                    onEvent(StreamEvent.Call(ToolCall(id.ifEmpty { "call_$i" }, name, args.ifBlank { "{}" })))
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

    /** [onRemoteUrl] gets the PC's Tailscale address ("" while remote access is off), if the PC reports it. */
    suspend fun listModels(
        baseUrls: List<String>,
        apiKey: String,
        pin: String? = null,
        onRemoteUrl: (String) -> Unit = {},
        onLan6Url: (String) -> Unit = {},
    ): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            executeFirst(baseUrls, pin, {}) { baseUrl ->
                Request.Builder()
                    .url("$baseUrl/models")
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
            }.use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw ApiException(errorMessage(response.code, text, pc = pin != null))
                val google = baseUrls.any { "generativelanguage.googleapis.com" in it }
                val root = JSONObject(text)
                if (pin != null && root.has("cubic_remote")) onRemoteUrl(root.optString("cubic_remote").takeIf { it.startsWith("https://") }.orEmpty())
                if (pin != null) root.str("cubic_lan6")?.takeIf { it.startsWith("https://[") }?.let(onLan6Url)
                root.optJSONArray("data").objects().mapNotNull { o ->
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
                        tools = o.optJSONArray("supported_parameters")?.let { "tools" in it.strings() },
                    ).let { if (google && id == "gemini-3.5-flash") it.copy(recommended = true) else it }
                }.distinctBy { it.id } // the picker keys its list by id; a duplicate would crash it
                    .sortedWith(compareByDescending<ModelInfo> { it.recommended }.thenBy { it.id })
            }
        } catch (e: IOException) {
            throw networkError(e, pin)
        }
    }

    // --- Chat Completions -----------------------------------------------------------------------

    private fun chatBody(model: String, messages: List<ApiMessage>, webPlugin: Boolean, tools: Tools?) = JSONObject().apply {
        put("model", model)
        put("stream", true)
        put("stream_options", JSONObject().put("include_usage", true)) // token counts in the last chunk
        put("messages", JSONArray(messages.map { m ->
            JSONObject().put("role", m.role).put("content", if (m.images.isEmpty()) m.text else JSONArray().apply {
                if (m.text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", m.text))
                m.images.forEach { url ->
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
                }
            }).apply {
                if (m.toolCalls.isNotEmpty()) put("tool_calls", JSONArray(m.toolCalls.map { c ->
                    JSONObject().put("id", c.id).put("type", "function")
                        .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
                }))
                m.toolCallId?.let { put("tool_call_id", it) }
            }
        }))
        if (webPlugin) put("plugins", JSONArray().put(JSONObject().put("id", "web")))
        if (tools != null) {
            put("tools", tools.functions)
            if (!tools.allowed) put("tool_choice", "none")
        }
    }

    private fun parseChatChunk(json: JSONObject, calls: MutableMap<Int, Array<String>>, onEvent: (StreamEvent) -> Unit) {
        json.optJSONObject("error")?.let { throw ApiException(it.str("message") ?: "Provider error") }
        json.str("cubic_status")?.let { onEvent(StreamEvent.Status(it)) } // progress from the PC gateway
        json.optJSONArray("citations").strings().filter { it.startsWith("http") }.forEach { onEvent(StreamEvent.Citation(it)) }
        json.optJSONObject("usage")?.let { u ->
            val prompt = u.optInt("prompt_tokens")
            if (prompt > 0) onEvent(StreamEvent.Usage(prompt, u.optInt("completion_tokens")))
        }

        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
        (delta.str("reasoning_content") ?: delta.str("reasoning"))
            ?.takeIf { it.isNotEmpty() }?.let { onEvent(StreamEvent.Reasoning(it)) }
        delta.str("content")?.takeIf { it.isNotEmpty() }?.let { onEvent(StreamEvent.Text(it)) }
        // Tool calls come in pieces keyed by index: the id and name first, then the arguments a chunk at a time.
        delta.optJSONArray("tool_calls").objects().forEach { c ->
            val slot = calls.getOrPut(c.optInt("index", calls.size)) { arrayOf("", "", "") }
            c.str("id")?.let { slot[0] = it }
            c.optJSONObject("function")?.let { f ->
                f.str("name")?.let { slot[1] = it }
                f.str("arguments")?.let { slot[2] += it }
            }
        }
        delta.optJSONArray("annotations").objects().forEach { a ->
            a.optJSONObject("url_citation")?.str("url")?.let { onEvent(StreamEvent.Citation(it)) }
        }
    }

    // --- Responses API (xAI search tools) -------------------------------------------------------

    private fun responsesBody(model: String, messages: List<ApiMessage>, tools: Tools?) = JSONObject().apply {
        put("model", model)
        put("stream", true)
        // Tool calls and their results are items of their own here, not fields of a message.
        put("input", JSONArray(messages.flatMap { m ->
            when {
                m.toolCallId != null -> listOf(
                    JSONObject().put("type", "function_call_output").put("call_id", m.toolCallId).put("output", m.text),
                )
                else -> listOfNotNull(
                    if (m.text.isEmpty() && m.images.isEmpty() && m.toolCalls.isNotEmpty()) null
                    else JSONObject().put("role", m.role).put("content", if (m.images.isEmpty()) m.text else JSONArray().apply {
                        if (m.text.isNotEmpty()) put(JSONObject().put("type", "input_text").put("text", m.text))
                        m.images.forEach { url -> put(JSONObject().put("type", "input_image").put("image_url", url)) }
                    }),
                ) + m.toolCalls.map { c ->
                    JSONObject().put("type", "function_call").put("call_id", c.id).put("name", c.name).put("arguments", c.arguments)
                }
            }
        }))
        put("tools", JSONArray()
            .put(JSONObject().put("type", "web_search"))
            .put(JSONObject().put("type", "x_search"))
            .apply {
                // The same functions, in the Responses API's flatter shape.
                tools?.functions.objects().mapNotNull { it.optJSONObject("function") }.forEach { f ->
                    put(JSONObject().put("type", "function").put("name", f.optString("name"))
                        .put("description", f.optString("description")).put("parameters", f.optJSONObject("parameters")))
                }
            })
        if (tools != null && !tools.allowed) put("tool_choice", "none")
    }

    private fun parseResponsesEvent(json: JSONObject, calls: MutableMap<Int, Array<String>>, onEvent: (StreamEvent) -> Unit) {
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
                if (item.optString("type") != "function_call") {
                    searchStatus(item.optString("type") + " " + item.optString("name"))?.let { onEvent(StreamEvent.Status(it)) }
                }
            }

            type == "response.output_item.done" -> {
                val item = json.optJSONObject("item") ?: return
                if (item.optString("type") == "function_call") {
                    calls[calls.size] = arrayOf(item.optString("call_id"), item.optString("name"), item.optString("arguments"))
                }
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
                response.optJSONObject("usage")?.let { u ->
                    val prompt = u.optInt("input_tokens")
                    if (prompt > 0) onEvent(StreamEvent.Usage(prompt, u.optInt("output_tokens")))
                }
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

    internal fun networkError(e: IOException, pin: String?): ApiException {
        if (pin == null) return ApiException(
            when (e) {
                is UnknownHostException -> "No internet connection."
                is SocketTimeoutException -> "The server didn't answer in time. Try again."
                else -> "Network error: ${e.message ?: e.javaClass.simpleName}"
            }
        )
        if (e is OffNetworkException) return ApiException(
            if (e.links.vpn) "Can't reach your PC through Tailscale. Right after leaving Wi-Fi it can take a minute to reconnect; try again shortly."
            else if (e.links.lan) "Can't reach your PC from this network. Turn on Tailscale, or connect to your home Wi-Fi."
            else "You're not on your home Wi-Fi and Tailscale is off. Turn on Tailscale to reach your PC from anywhere."
        )
        val mismatch = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<CertificateException>().firstOrNull()
        return ApiException(
            when {
                mismatch != null -> mismatch.message ?: "This isn't your paired PC. Pair again in Settings."
                // The PC drops connections it doesn't allow (Tailscale while remote access is off) before TLS.
                e is SSLException -> "Your PC turned the connection away. If you're away from home, turn on remote access in CubicAI Server."
                e.isRefusal() -> "Your PC is there but CubicAI Server isn't answering. Start it from the CubicAI Server panel, then retry."
                else -> "Can't reach your PC. Check that CubicAI Server is running, and that you're on home Wi-Fi or Tailscale."
            }
        )
    }

    /** [pc]: the paired PC, whose 401 means the pairing was revoked rather than a wrong API key. */
    internal fun errorMessage(code: Int, body: String, pc: Boolean = false): String {
        val detail = runCatching {
            // Google wraps some errors in a one-element array.
            val o = if (body.trimStart().startsWith("[")) JSONArray(body).getJSONObject(0) else JSONObject(body)
            o.optJSONObject("error")?.str("message") ?: o.str("error") ?: o.str("message")
        }.getOrNull()?.lineSequence()?.firstOrNull() ?: body.take(300)
        val hint = when (code) {
            401 if pc -> "Your PC no longer accepts this phone's pairing. Pair again in Settings."
            403 if pc -> null // the PC explains itself
            401, 403 -> "Check your API key in Settings."
            402 -> "Your account is out of credits."
            404 -> "Model not found. Pick another model."
            429 -> "Rate limited or out of free quota. Wait a moment, pick another model, or check your plan."
            503 -> "The model is overloaded right now. Try again or pick another model."
            else -> null
        }?.takeUnless { detail.contains("try again", ignoreCase = true) } // the provider already said it
        if (pc && code == 401) return hint!! // the PC's own "invalid token" adds nothing
        return listOfNotNull("Error $code: $detail".trim(), hint).joinToString("\n")
    }
}
