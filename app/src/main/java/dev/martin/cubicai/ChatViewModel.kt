package dev.martin.cubicai

import android.app.Application
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.martin.cubicai.call.CallController
import dev.martin.cubicai.call.CallHost
import dev.martin.cubicai.data.Attachment
import dev.martin.cubicai.data.AttachmentProcessor
import dev.martin.cubicai.data.Chat
import dev.martin.cubicai.data.CloudJob
import dev.martin.cubicai.data.ChatStore
import dev.martin.cubicai.data.MediaRequest
import dev.martin.cubicai.data.Message
import dev.martin.cubicai.data.Pairing
import dev.martin.cubicai.data.Persona
import dev.martin.cubicai.data.Personas
import dev.martin.cubicai.data.newId
import dev.martin.cubicai.data.Provider
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.Role
import dev.martin.cubicai.data.Settings
import dev.martin.cubicai.data.SettingsStore
import dev.martin.cubicai.data.ToolCall
import dev.martin.cubicai.net.ApiException
import dev.martin.cubicai.net.ApiMessage
import dev.martin.cubicai.net.Generator
import dev.martin.cubicai.net.LanFinder
import dev.martin.cubicai.net.Links
import dev.martin.cubicai.net.LlmClient
import dev.martin.cubicai.net.MediaGoneException
import dev.martin.cubicai.net.MediaProgress
import dev.martin.cubicai.net.ModelInfo
import dev.martin.cubicai.net.StreamEvent
import dev.martin.cubicai.net.Tools
import dev.martin.cubicai.net.VideoGoneException
import dev.martin.cubicai.net.cancelMedia
import dev.martin.cubicai.net.downloadMedia
import dev.martin.cubicai.net.followMedia
import dev.martin.cubicai.net.mediaGenerators
import dev.martin.cubicai.net.saveOnPc
import dev.martin.cubicai.net.submitMedia
import dev.martin.cubicai.ui.saveToGallery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class UiState(
    val settings: Settings,
    /** "Test connection" is running (it can take a few seconds when the PC is unreachable). */
    val testingConnection: Boolean = false,
    val chats: List<Chat> = emptyList(),
    val current: Chat = Chat(),
    val draft: String = "",
    val pending: List<Attachment> = emptyList(),
    val attaching: Boolean = false,
    val streaming: Boolean = false,
    val status: String? = null,
    val notice: String? = null,
    /** Next message creates an image instead of a text reply (providers that support it). */
    val imageMode: Boolean = false,
    /** Next message makes a video (Grok Imagine) instead of a text reply. */
    val videoMode: Boolean = false,
    /** Context window of the selected model in tokens; 0 while unknown. */
    val contextLimit: Int = 0,
    /** What the PC's ComfyUI can make (loaded when the Create sheet opens). */
    val generators: List<Generator> = emptyList(),
    val loadingGenerators: Boolean = false,
    /** Why the last attempt to list them failed, shown in the Create sheet. */
    val generatorsError: String? = null,
    /** Next message is made by ComfyUI on the PC with these options, instead of getting a chat reply. */
    val create: CreateOptions? = null,
    /** Live progress of PC generations, by reply message id. */
    val mediaProgress: Map<String, MediaProgress> = emptyMap(),
)

data class CreateOptions(
    val generator: Generator,
    val aspect: String = generator.aspects.firstOrNull() ?: "",
    val seconds: Int = generator.defaultSeconds,
    val language: String = generator.languages.firstOrNull { it.startsWith("English") } ?: generator.languages.firstOrNull().orEmpty(),
    /** The PC's built-in voice to speak with when no recording is attached. */
    val speaker: String = generator.voices.firstOrNull().orEmpty(),
    val lyrics: String = "",
    /** More controls; null keeps the workflow's own value (and a random seed). */
    val seed: Long? = null,
    val steps: Int? = null,
    val cfg: Double? = null,
    val negative: String? = null,
) {
    val tuned get() = seed != null || steps != null || cfg != null || negative != null
}

/** Reconnect attempts while following a PC job before giving up (with growing pauses, about ten minutes). */
private const val MEDIA_RECONNECTS = 40

/** How many recent messages keep their photos in the request; older ones are summarised as a note. */
private const val MAX_IMAGE_MESSAGES = 6

/** Length of a Grok Imagine video, in seconds (billed per second). */
private const val VIDEO_SECONDS = 6

/** Things the chat model may make by itself per message from the user: one, and one better attempt if it's off. */
private const val MAX_CREATIONS_PER_TURN = 2

/** How to prompt each picture model, for the model writing the prompt (the PC's own note is used for others). */
private val PROMPT_GUIDES = mapOf(
    "z-image" to "photoreal and fast (about 25 s): photos, selfies, everyday scenes. Write plain descriptive sentences.",
    "qwen-uncensored" to "best quality, slower (about 1.5 min), and the only one that writes readable text: screenshots, " +
        "chat screens, signs, posters. Write plain sentences; every word that should appear goes in double quotes, " +
        "exactly as written (e.g. a weather app showing \"Tokyo\", \"19°C\", \"Light drizzle later\").",
    "flux3-klein" to "sharp detail (about 35 s). Plain sentences.",
    "flux2-klein" to "fastest (about 10 s), simpler pictures. Plain sentences.",
    "illustrious" to "anime and illustration only. Write comma-separated Danbooru tags (e.g. 1boy, short hair, hoodie, " +
        "classroom, smiling, looking at viewer), not sentences.",
)

/** Streamed tokens are applied to the screen at most this often; one update per token re-laid-out the reply too often. */
private const val STREAM_FRAME_MS = 50L

/** Text that arrived since the last screen update (written by the network thread, flushed on a short timer). */
private class TokenBuffer {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    var flushedAt = 0L
    var flushScheduled = false
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    private val settingsStore = SettingsStore(app)
    private val client = LlmClient()

    /** Fires when the phone gets a working network again, so a job that lost its PC retries straight away. */
    private val networkBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val networkWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkBack.tryEmit(Unit)
        }
    }
    private val io = Dispatchers.IO.limitedParallelism(1) // serialises chat file writes
    private val modelCache = mutableMapOf<String, List<ModelInfo>>()

    private val _state = MutableStateFlow(settingsStore.load().let { UiState(settings = it, current = Chat(personaId = it.personaId)) })
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var runId = 0
    private var lastStreamSave = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: List<String>? = null

    /** A text reply is being written (not something being made on the PC). */
    @Volatile private var textReplying = false
    /** Call turns and the replies they speak, so one the user talked over can be cut where they did. */
    private val callReplies = mutableMapOf<Int, String>()
    /** In a call, the user was told once that something is still being made. */
    private var toldStillMaking = false

    /** A live voice call in the open chat (see call/CallController). */
    val call = CallController(app, client, viewModelScope, object : CallHost {
        override fun callSettings() = _state.value.settings
        override fun updateCallSettings(transform: (Settings) -> Settings) = updateSettings(transform)
        override fun callPersona() = personaOf(_state.value.current) ?: Personas.builtIn.first()
        override fun heard(text: String, frame: Bitmap?, cutTurn: Int?, heard: String) = heardInCall(text, frame, cutTurn, heard)
        override fun checkIn(quietSeconds: Int, frame: Bitmap?) = checkInFromCall(quietSeconds, frame)
        override val replying get() = textReplying
    })

    /** Full-size camera shots land here first; only the downscaled copy is kept. */
    private val cameraDir = File(app.cacheDir, "camera")

    init {
        viewModelScope.launch(Dispatchers.IO) { cameraDir.listFiles()?.forEach { it.delete() } } // leftovers from a crash
        runCatching { connectivity?.registerDefaultNetworkCallback(networkWatch) }
        // When the router gives the PC a new home address, find it again and keep the new one.
        val finder = LanFinder(app)
        client.links = {
            val nets = connectivity?.allNetworks.orEmpty().mapNotNull { connectivity?.getNetworkCapabilities(it) }
            Links(
                lan = nets.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                vpn = nets.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
            )
        }
        client.findPc = { fp, tried -> if (_state.value.settings.isPaired) finder.find(fp, tried) else null }
        // An IPv6 find means IPv4 isn't getting through right now: it updates that address, not the home one.
        client.onPcMoved = { url -> updateSettings { if (url.startsWith("https://[")) it.copy(localLan6Url = url) else it.copy(localUrl = url) } }
        viewModelScope.launch {
            val chats = withContext(io) { store.loadAll() }.map(::markInterrupted)
            _state.update { it.copy(chats = chats) }
        }
        refreshContextLimit()
        viewModelScope.launch { networkBack.collect { call.networkBack() } }
        // In a call: whether something is being made (it's said so), and an answer owed once it's done.
        viewModelScope.launch {
            _state.map { s -> s.current.messages.any { it.mediaPending } }.distinctUntilChanged().collect { call.setMaking(it) }
        }
        viewModelScope.launch {
            _state.map { it.streaming }.distinctUntilChanged().collect { streaming -> if (!streaming) answerWaitingCall() }
        }
        // While a reply is written or something is made, a foreground service keeps the app alive in the background.
        WorkService.onStopTapped = { viewModelScope.launch { stop() } }
        viewModelScope.launch {
            var working = false
            _state.map(::workText).distinctUntilChanged().collectLatest { text ->
                if (text != null) {
                    working = true
                    WorkService.update(app, text)
                } else if (working) {
                    // A picture and the model's look at it run back to back: wait a moment so the notice doesn't flicker.
                    delay(2500)
                    working = false
                    WorkService.update(app, null)
                    if (!visible) notifyDone()
                }
            }
        }
    }

    /** Whether the app is on screen; work that finishes while it isn't is announced with a notification. */
    @Volatile private var visible = true

    fun setVisible(shown: Boolean) {
        visible = shown
        if (shown) WorkService.clearDone(getApplication())
    }

    private fun workText(s: UiState): String? {
        if (!s.streaming || call.active) return null // a call has its own notification
        val making = s.current.messages.lastOrNull { it.mediaPending }?.media ?: return "Writing a reply"
        val kind = runCatching { JSONObject(making.params).optString("kind") }.getOrDefault("")
        return when {
            kind == "video" -> "Making a video on your PC"
            making.generator == "chatterbox" -> "Recording a voice message on your PC"
            kind == "audio" -> "Making music on your PC"
            else -> "Making a picture on your PC"
        }
    }

    private fun notifyDone() {
        val chat = _state.value.current
        val last = chat.messages.lastOrNull { it.role == Role.ASSISTANT }?.takeIf { !it.stopped } ?: return
        val text = when {
            last.error != null -> last.error
            last.media?.ask == true -> "Wants to make a video. Open the chat to say yes or no."
            last.content.isNotBlank() -> last.content.take(300)
            last.attachments.any { it.isVideo } -> "Sent a video"
            last.attachments.any { it.isAudio } -> if (last.media?.generator == "chatterbox") "Sent a voice message" else "Sent music"
            last.attachments.any { it.isImage } -> "Sent a picture"
            else -> return
        }
        WorkService.done(getApplication(), last.persona ?: chat.title.ifBlank { "CubicAI" }, text)
    }

    // --- Settings -------------------------------------------------------------------------------

    fun updateSettings(transform: (Settings) -> Settings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        settingsStore.save(_state.value.settings)
    }

    /** Picks the model for the open chat (it remembers it) and for new chats. */
    fun selectModel(provider: Provider, model: String) {
        // A picture or video model isn't a chat model: it becomes the one the Image or Video chip uses, and that mode goes on.
        val kind = provider.mediaKind(model)
        if (kind != null) {
            updateSettings { it.copy(providerId = provider.id, mediaModels = it.mediaModels + ("${provider.id}:$kind" to model)) }
            _state.update { it.copy(imageMode = kind == "image", videoMode = kind == "video", create = null) }
            return
        }
        updateSettings { it.copy(providerId = provider.id, models = it.models + (provider.id to model)) }
        if (!provider.images) _state.update { it.copy(imageMode = false) }
        if (!provider.videos) _state.update { it.copy(videoMode = false) }
        val chatId = _state.value.current.id
        mutateChat(chatId) { it.copy(providerId = provider.id, model = model) }
        _state.update { s -> if (s.current.id == chatId) s.copy(current = s.current.copy(providerId = provider.id, model = model)) else s }
        persist(chatId)
        refreshContextLimit()
    }

    /** Looks up how many tokens the selected model can hold, for the context meter. */
    private fun refreshContextLimit() {
        val settings = _state.value.settings
        val provider = settings.provider
        val model = settings.model(provider)
        _state.update { it.copy(contextLimit = modelCache[provider.id]?.firstOrNull { m -> m.id == model }?.contextTokens ?: 0) }
        if (model.isBlank() || modelCache.containsKey(provider.id)) return
        viewModelScope.launch {
            val limit = runCatching { listModels(provider) }.getOrNull()?.firstOrNull { it.id == model }?.contextTokens ?: return@launch
            // Only if the user hasn't switched model meanwhile.
            val now = _state.value.settings
            if (now.providerId == provider.id && now.model(provider) == model) _state.update { it.copy(contextLimit = limit) }
        }
    }

    fun toggleWebSearch() = updateSettings { it.copy(webSearch = !it.webSearch) }

    fun toggleImageMode() = _state.update { it.copy(imageMode = !it.imageMode, videoMode = false) }

    fun toggleVideoMode() = _state.update { it.copy(videoMode = !it.videoMode, imageMode = false) }

    // --- Create on the PC (ComfyUI) ------------------------------------------------------------

    fun loadGenerators() {
        val s = _state.value.settings
        if (!s.isPaired || _state.value.loadingGenerators) return
        _state.update { it.copy(loadingGenerators = true, generatorsError = null) }
        viewModelScope.launch {
            val result = runCatching { client.mediaGenerators(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint) }
            _state.update { st ->
                st.copy(
                    loadingGenerators = false,
                    generators = result.getOrDefault(st.generators),
                    generatorsError = result.exceptionOrNull()?.let { it.message ?: "Couldn't reach your PC" },
                    // A generator that disappeared from the PC (model removed) can't stay selected.
                    create = st.create?.takeIf { c -> result.getOrNull()?.any { it.id == c.generator.id } != false },
                )
            }
        }
    }

    fun setCreate(options: CreateOptions?) {
        _state.update { it.copy(create = options, imageMode = false, videoMode = false) }
        if (options != null) updateSettings {
            it.copy(lastCreate = listOf(options.generator.id, options.aspect, options.seconds, options.language, options.speaker).joinToString("|"))
        }
    }

    /** The last choice, if the PC still offers that generator. */
    fun lastCreate(): CreateOptions? {
        val parts = _state.value.settings.lastCreate.split("|")
        val g = _state.value.generators.firstOrNull { it.id == parts[0] } ?: return null
        val base = CreateOptions(g)
        return base.copy(
            aspect = parts.getOrNull(1)?.takeIf { it in g.aspects } ?: base.aspect,
            seconds = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..g.maxSeconds } ?: base.seconds,
            language = parts.getOrNull(3)?.takeIf { it in g.languages } ?: base.language,
            speaker = parts.getOrNull(4)?.takeIf { it in g.voices } ?: base.speaker,
        )
    }

    private fun createParams(o: CreateOptions, prompt: String) = JSONObject()
        .put("generator", o.generator.id)
        .put("kind", o.generator.kind) // for the progress card's shape; the PC ignores it
        .put("prompt", prompt)
        .apply {
            if (o.aspect.isNotEmpty()) put("aspect", o.aspect)
            if (o.seconds > 0) put("seconds", o.seconds)
            if (o.language.isNotEmpty()) put("language", o.language)
            if (o.speaker.isNotEmpty()) put("speaker", o.speaker) // used by the PC only when no recording is attached
            if (o.generator.lyrics) put("lyrics", o.lyrics)
            o.seed?.let { put("seed", it) }
            o.steps?.let { put("steps", it) }
            o.cfg?.let { put("cfg", it) }
            o.negative?.let { put("negative", it) }
        }

    /** The seed of the latest result in this chat, offered for making the same picture again. */
    fun lastSeed(): Long? = _state.value.current.messages.lastOrNull { it.media?.seed != null }?.media?.seed

    /**
     * The photo a job works on: the one sent with the prompt, or for a generator that can't work without one,
     * the latest picture in the chat (so "now make it night" edits the last result).
     */
    private fun sourceImage(history: List<Message>, generatorId: String): Attachment? {
        val prompt = history.lastOrNull { it.role == Role.USER }
        prompt?.attachments?.firstOrNull { it.isImage }?.let { return it }
        val required = _state.value.generators.firstOrNull { it.id == generatorId }?.image == "required"
        return if (required) history.lastOrNull { m -> m.attachments.any { it.isImage } }?.attachments?.lastOrNull { it.isImage } else null
    }

    /**
     * Starts a PC generation for the last user message, as a new reply. [call] is the chat model's request when it
     * asked for this itself, and [persona] the character it's from (for the label). With [ask] it waits for the
     * user's yes (see [answerVideo]).
     */
    private fun createMedia(
        generatorName: String,
        params: JSONObject,
        call: ToolCall? = null,
        persona: String? = null,
        ask: Boolean = false,
    ): Message {
        val snapshot = _state.value
        // Shapes the progress card like the photo being worked on (the app's own field; the PC ignores it).
        val source = if (params.optBoolean("auto")) params.optString("source").takeIf { it.isNotEmpty() }
        else sourceImage(snapshot.current.messages, params.getString("generator"))?.path
        source?.let { path -> sizeOf(path)?.let { (w, h) -> params.put("frame", "$w:$h") } }
        val reply = Message(
            role = Role.ASSISTANT,
            model = generatorName,
            persona = persona,
            media = MediaRequest(params.getString("generator"), params.toString(), call = call, ask = ask),
        )
        val chat = snapshot.current.copy(messages = snapshot.current.messages + reply, updatedAt = System.currentTimeMillis())
        _state.update { s -> s.copy(current = chat, chats = listOf(chat) + s.chats.filter { it.id != chat.id }) }
        persist(chat.id)
        if (!ask) watchMedia(chat.id, reply)
        return reply
    }

    private fun sizeOf(path: String): Pair<Int, Int>? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** A video made from [path]: the picture, and the shape of the generator's closest to it. */
    private fun withSource(params: JSONObject, path: String, aspects: List<String>): JSONObject {
        params.put("source", path)
        sizeOf(path)?.let { (w, h) ->
            params.put("frame", "$w:$h")
            aspects.minByOrNull { a ->
                val (aw, ah) = a.split(":").map { it.toDouble() }
                kotlin.math.abs(kotlin.math.ln(aw / ah) - kotlin.math.ln(w.toDouble() / h))
            }?.let { params.put("aspect", it) }
        }
        return params
    }

    /**
     * Submits (if not yet) and follows a PC job until its files are here. The job lives on the PC, so a
     * dropped connection is retried, and leaving the chat only stops watching; reopening it picks up again.
     */
    private fun watchMedia(chatId: String, reply: Message) {
        val request = reply.media ?: return
        val settings = _state.value.settings
        val baseUrls = settings.baseUrls(Providers.LOCAL)
        val token = settings.key(Providers.LOCAL)
        val pin = settings.localFingerprint
        _state.update { it.copy(streaming = true, status = null) }
        val run = ++runId
        val watchedAt = System.currentTimeMillis()
        job = viewModelScope.launch {
            var cancelled = false
            var made = false
            try {
                if (!settings.isPaired) throw ApiException("Pair with your PC first: Settings → Local PC.")
                var jobId = request.jobId
                if (jobId == null) {
                    setProgress(reply.id, MediaProgress("sending", "Sending to your PC"))
                    val all = (_state.value.chats.firstOrNull { it.id == chatId } ?: _state.value.current).messages
                    val history = all.take(all.indexOfFirst { it.id == reply.id }.coerceAtLeast(0))
                    val source = history.lastOrNull { it.role == Role.USER }
                    val params = JSONObject(request.params)
                    // The chat model's requests name their inputs (a face picture, a voice sample); local paths stay here.
                    val auto = params.optBoolean("auto")
                    val image = if (auto) params.optString("source").takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
                    else sourceImage(history, request.generator)?.let { File(it.path) }
                    val audio = if (auto) params.optString("voice").takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
                    else source?.attachments?.firstOrNull { it.isAudio }?.let { File(it.path) }
                    listOf("auto", "source", "voice", "frame", "picture", "for_video", "declined").forEach(params::remove)
                    val id = client.submitMedia(baseUrls, token, pin, params, image, audio)
                    jobId = id
                    updateMessage(chatId, reply.id) { it.copy(media = request.copy(jobId = id, startedAt = System.currentTimeMillis())) }
                    persist(chatId)
                }
                // Following the job and fetching its files both ride out a dropped or slow connection.
                suspend fun <T> reconnecting(block: suspend () -> T): T {
                    var failures = 0
                    while (true) {
                        try {
                            return block()
                        } catch (e: MediaGoneException) {
                            throw e
                        } catch (e: ApiException) {
                            if (++failures > MEDIA_RECONNECTS) throw ApiException("Lost contact with your PC. Tap Retry to check on it again.")
                            val last = _state.value.mediaProgress[reply.id]
                            setProgress(reply.id, (last ?: MediaProgress("running", "")).copy(phase = "Reconnecting to your PC", step = 0, steps = 0))
                            // Waits a growing while, but tries again as soon as the phone gets a network back.
                            withTimeoutOrNull(minOf(2000L * failures, 15_000L)) { networkBack.first(); delay(1500) }
                        }
                    }
                }
                val final = reconnecting { client.followMedia(baseUrls, token, pin, jobId) { setProgress(reply.id, it) } }
                when (final.state) {
                    "done" -> {
                        val files = final.files.map { f ->
                            val dest = File(store.attachmentDir, "${newId()}.${f.name.substringAfterLast('.', "bin")}")
                            reconnecting {
                                setProgress(reply.id, final.copy(phase = "Downloading"))
                                client.downloadMedia(baseUrls, token, pin, jobId, f.index, dest)
                            }
                            Attachment(f.kind, f.name, dest.path)
                        }
                        updateMessage(chatId, reply.id) {
                            // From sending it to the PC until it's here; a job picked up again counts from its start.
                            val began = it.media?.startedAt?.takeIf { t -> t > 0 } ?: watchedAt
                            it.copy(attachments = files, error = null, media = it.media?.copy(seed = final.seed), took = System.currentTimeMillis() - began)
                        }
                        made = true
                    }
                    "cancelled" -> updateMessage(chatId, reply.id) { it.copy(stopped = true, media = it.media?.copy(jobId = null)) }
                    else -> updateMessage(chatId, reply.id) {
                        it.copy(error = final.error ?: "Your PC couldn't make this.", media = it.media?.copy(jobId = null))
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: MediaGoneException) {
                updateMessage(chatId, reply.id) { it.copy(error = e.message, media = it.media?.copy(jobId = null)) }
            } catch (e: Exception) {
                updateMessage(chatId, reply.id) { it.copy(error = e.message ?: "Something went wrong") }
            } finally {
                if (!cancelled) setProgress(reply.id, null)
                if (runId == run) _state.update { it.copy(streaming = false, status = null) }
                persist(chatId)
                // Something the chat model asked for: it gets the result back and carries on the conversation.
                if (made && request.call != null && runId == run) afterCreation(chatId, reply.id, request.call)
                // The picture a video starts from: once it's here, the video is made from it.
                val forVideo = runCatching { JSONObject(request.params).optString("for_video") }.getOrDefault("")
                if (forVideo.isNotEmpty() && !cancelled) videoAfterPicture(chatId, reply.id, forVideo, made)
            }
        }
    }

    private fun afterCreation(chatId: String, replyId: String, call: ToolCall) {
        val chat = current(chatId) ?: return
        val reply = chat.messages.firstOrNull { it.id == replyId } ?: return
        val persona = personaOf(chat)
        // The first picture of the character becomes its face, so later ones look like the same person.
        val picture = reply.attachments.firstOrNull { it.isImage }
        val showsYou = runCatching { JSONObject(call.arguments).optBoolean("shows_you") }.getOrDefault(false)
        if (picture != null && showsYou && faceOf(chat, persona) == null) {
            setChatFace(chatId, copyCharacterFile(File(picture.path), "chat-$chatId-face"))
        }
        // A model that can see gets to look at its picture and carry on (or try once more). A voice message, or a
        // picture it can't see, is the reply itself: the words it wrote before asking for it already went with it.
        val s = _state.value.settings
        if (picture != null && seesCreations(s.provider, s.model(s.provider)) &&
            _state.value.current.id == chatId && chat.messages.lastOrNull()?.id == replyId
        ) complete()
    }

    /** The picture a video starts from is here (or failed): the video is made from it now. */
    private fun videoAfterPicture(chatId: String, pictureId: String, videoId: String, made: Boolean) {
        val chat = current(chatId) ?: return
        val video = chat.messages.firstOrNull { it.id == videoId }?.takeIf { it.mediaPending } ?: return
        val request = video.media ?: return
        val picture = chat.messages.firstOrNull { it.id == pictureId }?.attachments?.firstOrNull { it.isImage }
        if (!made || picture == null) {
            updateMessage(chatId, videoId) { it.copy(error = "The picture for this video couldn't be made.") }
            persist(chatId)
            return
        }
        // Like any picture of the character, the first one becomes its face.
        val showsYou = runCatching { JSONObject(JSONObject(request.params).optString("picture")).optBoolean("shows_you") }.getOrDefault(false)
        if (showsYou && faceOf(chat, personaOf(chat)) == null) setChatFace(chatId, copyCharacterFile(File(picture.path), "chat-$chatId-face"))
        val aspects = _state.value.generators.firstOrNull { it.id == request.generator }?.aspects.orEmpty()
        val ready = request.copy(params = withSource(JSONObject(request.params), picture.path, aspects).toString())
        updateMessage(chatId, videoId) { it.copy(media = ready) }
        persist(chatId)
        if (_state.value.current.id == chatId) watchMedia(chatId, video.copy(media = ready))
    }

    /**
     * The user's answer to a video the chat model wants to make. Yes makes the picture it starts from (or takes the
     * latest one), then the video; no leaves it unmade, and the model is told.
     */
    fun answerVideo(message: Message, yes: Boolean) {
        val chatId = _state.value.current.id
        val media = message.media?.takeIf { it.ask } ?: return
        if (!yes) {
            updateMessage(chatId, message.id, ::declined)
            persist(chatId)
            return
        }
        if (_state.value.streaming) return
        val chat = current(chatId) ?: return
        val params = JSONObject(media.params)
        val args = runCatching { JSONObject(params.optString("picture")) }.getOrDefault(JSONObject())
        val aspects = _state.value.generators.firstOrNull { it.id == media.generator }?.aspects.orEmpty()
        val lastPicture = chat.messages.takeWhile { it.id != message.id }
            .lastOrNull { m -> m.attachments.any { it.isImage } }?.attachments?.lastOrNull { it.isImage }?.path
        if (args.optString("start_from") == "last_picture" && lastPicture != null) {
            val ready = media.copy(ask = false, params = withSource(params, lastPicture, aspects).toString())
            updateMessage(chatId, message.id) { it.copy(media = ready) }
            persist(chatId)
            watchMedia(chatId, message.copy(media = ready))
            return
        }
        val plan = pictureJob(chat, personaOf(chat) ?: Personas.builtIn.first(), args)
        if (plan == null) {
            updateMessage(chatId, message.id) { it.copy(media = media.copy(ask = false), error = "Your PC has no picture model to start the video from.") }
            persist(chatId)
            return
        }
        val (gen, pictureParams) = plan
        pictureParams.put("auto", true).put("for_video", message.id)
        pictureParams.optString("source").takeIf { it.isNotEmpty() }?.let { path -> sizeOf(path)?.let { (w, h) -> pictureParams.put("frame", "$w:$h") } }
        val picture = Message(role = Role.ASSISTANT, model = gen.name, persona = message.persona, media = MediaRequest(gen.id, pictureParams.toString()))
        // The picture goes just before the video, which waits for it.
        mutateChat(chatId) { c ->
            c.copy(messages = c.messages.flatMap { m -> if (m.id == message.id) listOf(picture, m.copy(media = media.copy(ask = false))) else listOf(m) })
        }
        persist(chatId)
        watchMedia(chatId, picture)
    }

    private fun declined(m: Message) = m.copy(
        stopped = true,
        media = m.media?.copy(ask = false, params = JSONObject(m.media.params).put("declined", true).toString()),
    )

    private fun setProgress(messageId: String, progress: MediaProgress?) = _state.update {
        it.copy(mediaProgress = if (progress == null) it.mediaProgress - messageId else it.mediaProgress + (messageId to progress))
    }

    /** Picks a PC job back up when its chat is opened again (after switching chats or restarting the app). */
    private fun resumeMedia(chat: Chat) {
        if (_state.value.streaming) return
        chat.messages.lastOrNull { it.role == Role.ASSISTANT }?.takeIf { it.cloudPending }?.let { return followVideo(chat.id, it) }
        // A video waits behind the picture it's made from, so that may be the one still on the PC.
        val pending = chat.messages.takeLastWhile { it.role == Role.ASSISTANT }.filter { it.mediaPending }
        val next = pending.firstOrNull { it.media?.jobId != null } ?: pending.firstOrNull { m ->
            m.media?.call != null && runCatching { JSONObject(m.media.params).has("source") }.getOrDefault(false)
        } ?: return
        watchMedia(chat.id, next)
    }

    /** Saves a result to the phone's gallery and, if it was made on the PC, keeps a copy there too. */
    fun saveMedia(message: Message) {
        val files = message.attachments.filter { it.isMedia }
        if (files.isEmpty()) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { files.all { saveToGallery(context, it) } }
            if (!saved) {
                _state.update { it.copy(notice = "Couldn't save to your phone") }
                return@launch
            }
            val where = when {
                files.all { it.isImage } -> "Pictures/CubicAI"
                files.all { it.isVideo } -> "Movies/CubicAI"
                files.all { it.isAudio } -> "Music/CubicAI"
                else -> "your phone's CubicAI folders"
            }
            val s = _state.value.settings
            if (message.media == null || !s.isPaired) {
                _state.update { it.copy(notice = "Saved to $where") }
                return@launch
            }
            val pc = runCatching {
                files.forEach { a ->
                    val mime = mimeFor(a)
                    client.saveOnPc(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint, a.kind, a.name, File(a.path), mime)
                }
            }
            _state.update {
                it.copy(notice = if (pc.isSuccess) "Saved to $where and your PC" else "Saved to $where. Couldn't reach your PC to keep a copy there.")
            }
        }
    }

    suspend fun listModels(provider: Provider): List<ModelInfo> {
        modelCache[provider.id]?.let { return it }
        val s = _state.value.settings
        val baseUrls = s.baseUrls(provider)
        if (baseUrls.isEmpty()) {
            throw ApiException(if (provider == Providers.LOCAL) "Pair with your PC first: Settings → Local PC." else "Set a base URL in Settings first.")
        }
        return client.listModels(baseUrls, s.key(provider), s.pin(provider), onRemoteUrl = ::learnRemoteUrl, onLan6Url = ::learnLan6Url).also {
            modelCache[provider.id] = it
            refreshContextLimit() // the open chat's meter may have been waiting on this list
        }
    }

    /**
     * The PC says where it is on Tailscale, so turning remote access on there needs no re-pairing. An address is
     * only ever added or updated: forgetting it while remote access is off would leave a phone that's away from
     * home unable to reconnect once it's switched back on (it then gets a clear "turned away" message instead).
     */
    private fun learnRemoteUrl(url: String) {
        if (url.isEmpty()) return
        // Called from the network thread; settings are saved on the main thread like every other change.
        viewModelScope.launch {
            if (url != _state.value.settings.localRemoteUrl && _state.value.settings.isPaired) updateSettings { it.copy(localRemoteUrl = url) }
        }
    }

    private fun learnLan6Url(url: String) {
        if (url.isEmpty()) return
        viewModelScope.launch {
            if (url != _state.value.settings.localLan6Url && _state.value.settings.isPaired) updateSettings { it.copy(localLan6Url = url) }
        }
    }

    // --- Personas -------------------------------------------------------------------------------

    /** Sets the persona for the open chat, and for new chats from now on. */
    fun selectPersona(id: String) {
        updateSettings { it.copy(personaId = id) }
        val chatId = _state.value.current.id
        mutateChat(chatId) { it.copy(personaId = id) }
        _state.update { s -> if (s.current.id == chatId) s.copy(current = s.current.copy(personaId = id)) else s }
        persist(chatId)
        call.personaChanged()
    }

    fun savePersona(persona: Persona) {
        val p = if (persona.id.isBlank()) persona.copy(id = newId()) else persona
        updateSettings { s ->
            val exists = s.customPersonas.any { it.id == p.id }
            s.copy(customPersonas = if (exists) s.customPersonas.map { if (it.id == p.id) p else it } else s.customPersonas + p)
        }
        selectPersona(p.id)
    }

    /** Saves a behaviour's changes without switching to it. */
    private fun updatePersona(p: Persona) = updateSettings { s -> s.copy(customPersonas = s.customPersonas.map { if (it.id == p.id) p else it }) }

    fun deletePersona(id: String) {
        _state.value.settings.customPersonas.firstOrNull { it.id == id }?.let { p -> discardCharacterFile(p.facePath); discardCharacterFile(p.voicePath) }
        updateSettings { s ->
            s.copy(
                customPersonas = s.customPersonas.filterNot { it.id == id },
                personaId = if (s.personaId == id) Personas.NONE else s.personaId,
            )
        }
        if (_state.value.current.personaId == id) selectPersona(Personas.NONE)
    }

    fun setAboutMe(text: String) = updateSettings { it.copy(aboutMe = text) }

    /** Thinking shown open or folded from now on, as the user last left it. */
    fun setThoughtsOpen(open: Boolean) = updateSettings { it.copy(thoughtsOpen = open) }

    /** Pairs with the PC gateway from a scanned/pasted `cubicai://pair` link and switches to it. */
    fun pair(link: String): Boolean {
        val pairing = Pairing.parse(link) ?: run {
            _state.update { it.copy(notice = "That isn't a CubicAI pairing code. Open CubicAI Server on your PC → Pair.") }
            return false
        }
        updateSettings {
            it.copy(
                providerId = Providers.LOCAL.id,
                localUrl = pairing.url,
                localRemoteUrl = pairing.remoteUrl,
                localLan6Url = "",
                localFingerprint = pairing.fingerprint,
                apiKeys = it.apiKeys + (Providers.LOCAL.id to pairing.token),
            )
        }
        modelCache.remove(Providers.LOCAL.id)
        viewModelScope.launch {
            val models = runCatching { listModels(Providers.LOCAL) }
            val message = models.fold(
                onSuccess = { list ->
                    // Start with a model that can see photos, since that's what the camera button needs.
                    val current = _state.value.settings.models[Providers.LOCAL.id].orEmpty()
                    if (current.isBlank() || list.none { it.id == current }) {
                        (list.firstOrNull { it.recommended } ?: list.firstOrNull { it.vision } ?: list.firstOrNull())?.let { selectModel(Providers.LOCAL, it.id) }
                    }
                    val remote = if (pairing.remoteUrl.isNotEmpty()) " (home Wi-Fi + Tailscale)" else " (home Wi-Fi)"
                    "Paired with your PC$remote: ${list.size} models"
                },
                onFailure = { "Paired, but can't reach the PC yet: ${it.message}" },
            )
            _state.update { it.copy(notice = message) }
        }
        return true
    }

    fun unpair() {
        updateSettings {
            it.copy(localUrl = "", localRemoteUrl = "", localLan6Url = "", localFingerprint = "", apiKeys = it.apiKeys + (Providers.LOCAL.id to ""))
        }
        modelCache.remove(Providers.LOCAL.id)
    }

    fun testLocalConnection() {
        if (_state.value.testingConnection) return
        modelCache.remove(Providers.LOCAL.id)
        _state.update { it.copy(testingConnection = true) }
        viewModelScope.launch {
            val result = runCatching { listModels(Providers.LOCAL) }
            _state.update {
                it.copy(
                    testingConnection = false,
                    notice = result.fold({ list -> "Connected · ${list.size} models" }, { e -> e.message ?: "Connection failed" }),
                )
            }
        }
    }

    fun clearModelCache() = modelCache.clear()

    // --- Chats ----------------------------------------------------------------------------------

    fun newChat() {
        job?.cancel()
        discard(_state.value.pending)
        _state.update { it.copy(current = Chat(personaId = it.settings.personaId), pending = emptyList(), draft = "") }
    }

    fun openChat(id: String) {
        if (_state.value.current.id == id) return
        job?.cancel()
        if (_state.value.chats.any { it.id == id }) discard(_state.value.pending) // unsent files would be orphaned
        _state.update { s -> s.chats.firstOrNull { it.id == id }?.let { s.copy(current = it, pending = emptyList()) } ?: s }
        // Each chat keeps its own model: switch back to it (the PC loads it on the next message if needed).
        val chat = _state.value.current
        val provider = chat.providerId?.let { id -> Providers.all.firstOrNull { it.id == id } }
        if (provider != null && !chat.model.isNullOrBlank()) {
            updateSettings { it.copy(providerId = provider.id, models = it.models + (provider.id to chat.model)) }
            refreshContextLimit()
        }
        _state.update { it.copy(streaming = false, status = null) } // the old chat's run was just cancelled
        resumeMedia(chat)
    }

    fun deleteChat(id: String) {
        val chat = _state.value.chats.firstOrNull { it.id == id } ?: return
        if (_state.value.current.id == id) job?.cancel()
        cancelPcJobs(chat)
        _state.update { s ->
            s.copy(chats = s.chats - chat, current = if (s.current.id == id) Chat(personaId = s.settings.personaId) else s.current)
        }
        viewModelScope.launch(io) { store.delete(chat) }
    }

    fun deleteAllChats() {
        job?.cancel()
        _state.value.chats.forEach(::cancelPcJobs)
        _state.update { it.copy(chats = emptyList(), current = Chat(personaId = it.settings.personaId), pending = emptyList()) }
        viewModelScope.launch(io) { store.deleteAll() }
    }

    /**
     * A deleted chat's jobs still on the PC are stopped and dropped too (running ones, and finished ones whose
     * download failed), so nothing it asked for is left there.
     */
    private fun cancelPcJobs(chat: Chat) {
        val ids = chat.messages.filter { it.attachments.isEmpty() }.mapNotNull { it.media?.jobId }
        if (ids.isEmpty()) return
        val s = _state.value.settings
        viewModelScope.launch { ids.forEach { client.cancelMedia(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint, it) } }
    }

    // --- Composer -------------------------------------------------------------------------------

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun showNotice(text: String) = _state.update { it.copy(notice = text) }

    /** Suggestion cards send straight away, unless the user has already typed or attached something. */
    fun sendSuggestion(prompt: String) {
        val s = _state.value
        if (s.draft.isBlank() && s.pending.isEmpty()) send(prompt)
        else setDraft((s.draft + " " + prompt).trim())
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(attaching = true) }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri -> runCatching { AttachmentProcessor.process(getApplication(), uri, store.attachmentDir) } }
                    .also { uris.forEach(::deleteIfCameraShot) }
            }
            val added = results.flatMap { it.getOrDefault(emptyList()) }
            val error = results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { it.message ?: "Couldn't attach file" }
            _state.update { it.copy(attaching = false, pending = it.pending + added, notice = error) }
        }
    }

    fun removePending(attachment: Attachment) {
        discard(listOf(attachment))
        _state.update { it.copy(pending = it.pending - attachment) }
    }

    private fun discard(attachments: List<Attachment>) {
        if (attachments.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { attachments.forEach { File(it.path).delete() } }
    }

    private fun deleteIfCameraShot(uri: Uri) {
        if (uri.authority == "${getApplication<Application>().packageName}.files") {
            uri.lastPathSegment?.let { File(cameraDir, it).delete() }
        }
    }

    fun send(text: String = _state.value.draft) {
        val s = _state.value
        if (s.streaming || s.attaching || (text.isBlank() && s.pending.isEmpty())) return
        val message = Message(role = Role.USER, content = text.trim(), attachments = s.pending)
        val chat = s.current.let { c ->
            c.copy(
                title = if (c.messages.isEmpty()) titleFor(message) else c.title,
                // A video still waiting for a yes is a no once the user carries on without answering.
                messages = c.messages.map { m -> if (m.media?.ask == true) declined(m) else m } + message,
            )
        }
        _state.update { it.copy(current = chat, pending = emptyList(), draft = "") }
        val create = s.create
        when {
            create != null && s.settings.provider == Providers.LOCAL -> createMedia(create.generator.name, createParams(create, text.trim()))
            s.imageMode && s.settings.provider.images -> createImage()
            s.videoMode && s.settings.provider.videos -> createVideo()
            else -> complete()
        }
    }

    fun editMessage(id: String, text: String) {
        val c = _state.value.current
        val index = c.messages.indexOfFirst { it.id == id }
        if (_state.value.streaming || index < 0) return
        val edited = c.messages[index].copy(content = text.trim())
        val reply = c.messages.getOrNull(index + 1)
        discard(c.messages.drop(index + 1).flatMap { it.attachments }) // later turns are gone; so are their files
        _state.update { it.copy(current = c.copy(messages = c.messages.take(index) + edited)) }
        // A prompt that made something on the PC makes it again, with the new wording.
        val media = reply?.media
        if (media != null) {
            createMedia(reply.model ?: media.generator, JSONObject(media.params).put("prompt", edited.content).apply { remove("seed") })
        } else complete()
    }

    fun regenerate() {
        val c = _state.value.current
        if (_state.value.streaming) return
        val lastReply = c.messages.lastOrNull()?.takeIf { it.role == Role.ASSISTANT }
        // A Grok video we lost touch with is collected, not paid for again.
        if (lastReply?.cloudJob != null && lastReply.attachments.isEmpty() && lastReply.error != null) {
            updateMessage(c.id, lastReply.id) { it.copy(error = null) }
            followVideo(c.id, lastReply.copy(error = null))
            return
        }
        lastReply?.media?.let { media ->
            // The job is still on the PC (lost touch with it, or its download failed): collect it before making
            // it anew. A job that failed, was stopped or is gone has no jobId any more.
            if (media.jobId != null && lastReply.attachments.isEmpty() && lastReply.error != null) {
                updateMessage(c.id, lastReply.id) { it.copy(error = null) }
                watchMedia(c.id, lastReply.copy(error = null))
                return
            }
            discard(lastReply.attachments)
            _state.update { it.copy(current = c.copy(messages = c.messages.dropLast(1))) }
            val params = JSONObject(media.params).apply { remove("seed") }
            // Try again on a video that never got its picture is the user's yes; one that has it animates it again.
            val needsPicture = params.has("picture") && !params.has("source")
            val again = createMedia(lastReply.model ?: media.generator, params, media.call, lastReply.persona, ask = needsPicture)
            if (needsPicture) answerVideo(again, yes = true)
            return
        }
        // The words after something the model made are written again; what it made stays.
        if (lastReply != null && c.messages.getOrNull(c.messages.size - 2)?.media?.call != null) {
            _state.update { it.copy(current = c.copy(messages = c.messages.dropLast(1))) }
            complete()
            return
        }
        val provider = _state.value.settings.provider
        // A failed or unwanted picture is redone as a picture, not as text.
        val lastModel = c.messages.lastOrNull { it.role == Role.ASSISTANT }?.model.orEmpty()
        val settings = _state.value.settings
        val wasImage = provider.images && (lastModel == settings.imageModel(provider) || provider.mediaKind(lastModel) == "image")
        val wasVideo = provider.videos && (lastModel == settings.videoModel(provider) || provider.mediaKind(lastModel) == "video")
        val trimmed = c.messages.dropLastWhile { it.role == Role.ASSISTANT }
        if (trimmed.isEmpty()) return
        discard(c.messages.drop(trimmed.size).flatMap { it.attachments }) // e.g. a created image being redone
        _state.update { it.copy(current = c.copy(messages = trimmed)) }
        when {
            wasImage -> createImage()
            wasVideo -> createVideo()
            else -> complete()
        }
    }

    fun stop() {
        job?.cancel()
        val c = _state.value.current
        c.messages.lastOrNull { it.role == Role.ASSISTANT }?.takeIf { it.cloudPending }?.let { v ->
            // xAI can't be told to stop; the video is just given up.
            updateMessage(c.id, v.id) { it.copy(stopped = true, cloudJob = null) }
            _state.update { it.copy(streaming = false, status = null) }
            persist(c.id)
        }
        // A video's picture and the video waiting for it go together.
        val pending = c.messages.takeLastWhile { it.role == Role.ASSISTANT }.filter { it.mediaPending }
        if (pending.isEmpty()) return
        for (p in pending) {
            setProgress(p.id, null)
            updateMessage(c.id, p.id) { it.copy(stopped = true, media = it.media?.copy(jobId = null)) }
            p.media?.jobId?.let { id ->
                val s = _state.value.settings
                viewModelScope.launch { client.cancelMedia(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint, id) }
            }
        }
        persist(c.id)
    }

    // --- Voice calls ----------------------------------------------------------------------------

    /**
     * Starts a call in the open chat. The PC listens and speaks; the chat's own model answers (the PC's, or a cloud
     * one if that's what the chat uses).
     */
    fun startCall() {
        val s = _state.value.settings
        if (!s.isPaired) {
            showNotice("Calls need your PC to listen and speak: pair it in Settings → Local PC.")
            return
        }
        if (s.model(s.provider).isBlank()) {
            showNotice("Pick a model first (tap the model name at the top).")
            return
        }
        stopSpeaking()
        _state.update { it.copy(create = null, imageMode = false) }
        call.start()
    }

    fun endCall() = call.end()

    /** What the user said in the call: it goes into the chat and is answered (after cutting the reply they talked over). */
    private fun heardInCall(text: String, frame: Bitmap?, cutTurn: Int?, heard: String) {
        val chatId = _state.value.current.id
        viewModelScope.launch {
            val cutId = cutTurn?.let { callReplies[it] }
            if (cutId != null) {
                if (textReplying) job?.cancelAndJoin()
                mutateChat(chatId) { c ->
                    val reply = c.messages.firstOrNull { it.id == cutId }
                    when {
                        reply == null || reply.media != null -> c
                        heard.isBlank() && reply.attachments.isEmpty() -> c.copy(messages = c.messages.filter { it.id != cutId })
                        // Only what was heard stays: the model then knows where it was interrupted.
                        else -> c.copy(messages = c.messages.map { if (it.id == cutId) it.copy(content = "$heard —", stopped = false, error = null) else it })
                    }
                }
            }
            val shot = frame?.let { bitmap ->
                withContext(Dispatchers.IO) {
                    runCatching { AttachmentProcessor.frame(bitmap, if (call.state.value.screen) "Screen" else "Camera", store.attachmentDir) }.getOrNull()
                }
            }
            if (_state.value.current.id != chatId || !call.active) return@launch
            val message = Message(role = Role.USER, content = text, attachments = listOfNotNull(shot), spoken = true)
            // A video waiting for a yes: a spoken yes makes it, anything else is a no (as when typing).
            val ask = current(chatId)?.messages?.lastOrNull { it.media?.ask == true }
            val yes = ask != null && SPOKEN_YES.containsMatchIn(text)
            mutateChat(chatId) { c ->
                c.copy(
                    title = if (c.messages.isEmpty()) titleFor(message) else c.title,
                    messages = c.messages.map { m -> if (m.media?.ask == true && !yes) declined(m) else m } + message,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            _state.update { s -> if (s.chats.any { it.id == chatId }) s else s.copy(chats = listOf(s.current) + s.chats) }
            persist(chatId)
            if (yes && ask != null) {
                answerVideo(ask, yes = true)
                call.say("Okay, making the video now. It takes a few minutes.")
                toldStillMaking = true
            } else if (_state.value.streaming) {
                // Something is being made on the PC: the answer comes when it's done (answerWaitingCall).
                if (!toldStillMaking) call.say("One moment, I'm still making that.")
                toldStillMaking = true
            } else complete()
        }
    }

    /** The user has been quiet: the model may say something (with what the camera or screen shows now). */
    private fun checkInFromCall(quietSeconds: Int, frame: Bitmap?) {
        if (_state.value.streaming) return
        viewModelScope.launch {
            val image = frame?.let { withContext(Dispatchers.IO) { jpegDataUrl(it) } }
            if (!_state.value.streaming && call.active) complete(CheckIn(quietSeconds, image))
        }
    }

    /** In a call, what was said while something was being made is answered once it's done. */
    private fun answerWaitingCall() {
        if (!call.active) return
        viewModelScope.launch {
            delay(300) // a look at the new picture may be starting instead (afterCreation)
            val s = _state.value
            val last = s.current.messages.lastOrNull()
            if (!s.streaming) toldStillMaking = false
            if (call.active && !s.streaming && last?.role == Role.USER && last.spoken) complete()
        }
    }

    private fun jpegDataUrl(bitmap: Bitmap): String? = runCatching {
        val scale = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = java.io.ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    /** A check-in: the user said nothing for [seconds]; [image] is what their camera or screen shows. */
    private data class CheckIn(val seconds: Int, val image: String?)

    private fun checkInMessages(c: CheckIn?): List<ApiMessage> = if (c == null) emptyList() else listOf(
        ApiMessage(
            Role.USER,
            "(Note from the app, not said by the user: they've been quiet for ${c.seconds} seconds. Say something short " +
                "to pick the conversation back up, in character: a question, a thought, a little nudge. Don't remark on the silence." +
                (if (c.image != null) " Below is what their camera or screen shows right now; it may be what's on their mind." else "") + ")",
            listOfNotNull(c.image),
        ),
    )

    /** What the model is told about being in a call; [sees] is "camera" or "screen" when one is shared. */
    private fun callGuidance(sees: String?): String = buildString {
        append("You're in a live voice call with the user. What you write is spoken aloud by a voice, and what they say ")
        append("reaches you transcribed from speech (it can have small mistakes: go with the most likely meaning). ")
        append("Talk the way people talk on a call: natural and warm, usually one to three sentences, longer only when they ")
        append("ask for detail or a story. No Markdown, lists, headings, emoji, code or links, and no actions in asterisks. ")
        append("Write numbers, dates and symbols the way they're said. Ask one question at a time. ")
        append("A reply of yours that ends in \"—\" was cut off there because the user started talking.")
        when (sees) {
            "camera" -> append(" Their camera is on: the photo with each of their messages is what it sees right now. " +
                "Talk about what you see when it's relevant, like someone on a video call would.")
            "screen" -> append(" They're sharing their screen: the picture with each of their messages is their screen right now.")
        }
    }

    // --- Completion -----------------------------------------------------------------------------

    private fun complete(checkIn: CheckIn? = null) {
        val snapshot = _state.value
        val settings = snapshot.settings
        val provider = settings.provider
        val model = settings.model(provider)
        val history = snapshot.current.messages
        val persona = settings.personas.firstOrNull { it.id == snapshot.current.personaId } ?: Personas.builtIn.first()
        val inCall = call.active
        val reply = Message(role = Role.ASSISTANT, model = model, persona = persona.name.takeIf { persona.id != Personas.NONE }, spoken = inCall)
        val turn = if (inCall) call.beginTurn() else 0
        if (inCall) {
            callReplies[turn] = reply.id
            callReplies.keys.filter { it < turn - 20 }.forEach(callReplies::remove)
        }
        val voiceContext = if (inCall) call.state.value.let { if (it.screen) "screen" else if (it.camera) "camera" else "" } else null
        textReplying = true
        val chat = snapshot.current.copy(
            messages = history + reply,
            updatedAt = System.currentTimeMillis(),
            providerId = provider.id,
            model = model,
        )
        _state.update { s ->
            s.copy(
                current = chat,
                chats = listOf(chat) + s.chats.filter { it.id != chat.id },
                streaming = true,
                status = null,
            )
        }
        persist(chat.id)

        val run = ++runId
        val buffer = TokenBuffer()
        var call: ToolCall? = null
        val startedAt = System.currentTimeMillis()
        job = viewModelScope.launch {
            var cancelled = false
            var failure: String? = null
            try {
                val baseUrls = settings.baseUrls(provider)
                val key = settings.key(provider)
                when {
                    provider == Providers.LOCAL && !settings.isPaired -> throw ApiException("Pair with your PC first: Settings → Local PC.")
                    baseUrls.isEmpty() -> throw ApiException("Set the base URL for the custom provider in Settings.")
                    key.isBlank() && provider != Providers.CUSTOM -> throw ApiException("Add your ${provider.name} API key in Settings to start chatting.")
                    model.isBlank() -> throw ApiException("Pick a model first (tap the model name at the top).")
                }
                // The model can make pictures and voice messages on the PC itself, when it's paired and the model takes tools.
                // In a call the model makes things only when the user allows it there, and no voice messages (it's talking).
                val makes = !inCall || settings.callMakesThings
                val gens = if (settings.isPaired && modelTakesTools(provider, model) && makes) creationGenerators() else emptyList()
                val tools = createTool(gens, persona, voiceMessages = !inCall)?.let { Tools(it, allowed = creationsThisTurn(history) < MAX_CREATIONS_PER_TURN) }
                val sees = seesCreations(provider, model)
                val extra = checkInMessages(checkIn)
                val messages = withContext(Dispatchers.IO) { buildApiMessages(settings, persona, history, tools != null, sees, voiceContext) } + extra
                client.stream(
                    // A check-in only keeps the call going: searching the web for it would just make the voice late.
                    baseUrls, key, model, messages, provider.search, settings.webSearch && checkIn == null, settings.pin(provider), tools,
                    withoutTools = { buildApiMessages(settings, persona, history, tools = false, sees = sees, call = voiceContext) + extra },
                    think = !inCall,
                ) { event ->
                    if (event is StreamEvent.Call) {
                        if (event.call.name == CREATE_TOOL && call == null) call = event.call
                    } else {
                        onStreamEvent(chat.id, reply.id, event, buffer)
                        if (inCall && event is StreamEvent.Text) this@ChatViewModel.call.onReplyText(turn, event.text)
                    }
                }
                // Small models sometimes only say they're sending a picture ("sending a selfie now!") without calling
                // the tool. Then they're asked once, briefly, to actually send it; only the call is kept from that.
                // (Not after something it just made: there "the selfie I sent" is about that one.)
                if (call == null && tools?.allowed == true && history.lastOrNull()?.media?.call == null) {
                    flush(chat.id, reply.id, buffer)
                    val said = current(chat.id)?.messages?.firstOrNull { it.id == reply.id }?.content.orEmpty()
                    if (ANNOUNCED.containsMatchIn(said)) {
                        _state.update { it.copy(status = "Sending it") }
                        val nudge = messages + ApiMessage(Role.ASSISTANT, said) + ApiMessage(
                            "system",
                            "You said you're sending something but didn't call the create tool, so nothing was sent. Call create now to send it.",
                        )
                        runCatching {
                            client.stream(baseUrls, key, model, nudge, provider.search, webSearch = false, pin = settings.pin(provider), tools = tools) { event ->
                                if (event is StreamEvent.Call && event.call.name == CREATE_TOOL && call == null) call = event.call
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                call = null
                failure = e.message ?: "Something went wrong"
                updateMessage(chat.id, reply.id) { it.copy(error = failure) }
            } finally {
                flush(chat.id, reply.id, buffer)
                val asked = call.takeIf { !cancelled }
                val reached = current(chat.id)?.messages?.firstOrNull { it.id == reply.id }
                val words = reached?.content?.trim().orEmpty()
                // A reply that is only the request to make something leaves no empty bubble behind, and neither does a
                // look at the model's own picture that ends in nothing to say (or only a stage direction like "(waits)").
                val afterCreation = history.lastOrNull()?.media?.call != null
                val nothing = reached != null && !cancelled && reached.error == null &&
                    ((asked != null && words.isEmpty()) || (afterCreation && asked == null && STAGE_DIRECTION.matches(words)))
                if (nothing) mutateChat(chat.id) { c -> c.copy(messages = c.messages.filter { it.id != reply.id }) }
                else updateMessage(chat.id, reply.id) { m ->
                    when {
                        cancelled -> m.copy(stopped = true)
                        m.content.isEmpty() && m.error == null && asked == null -> m.copy(error = "The model returned an empty response.")
                        m.error == null -> m.copy(took = System.currentTimeMillis() - startedAt)
                        else -> m
                    }
                }
                if (runId == run) {
                    textReplying = false
                    _state.update { it.copy(streaming = false, status = null) }
                }
                persist(chat.id)
                if (inCall) this@ChatViewModel.call.endTurn(turn, failure)
                if (asked != null && runId == run) createFromTool(chat.id, asked, persona)
            }
        }
    }

    private fun current(chatId: String): Chat? = _state.value.let { s -> if (s.current.id == chatId) s.current else s.chats.firstOrNull { it.id == chatId } }

    // --- The chat model making things on the PC --------------------------------------------------

    /** What the PC can make, loaded once (a PC that didn't answer is asked again after a minute at most). */
    private var generatorsTriedAt = 0L

    private suspend fun creationGenerators(): List<Generator> {
        _state.value.generators.takeIf { it.isNotEmpty() }?.let { return it }
        if (System.currentTimeMillis() - generatorsTriedAt < 60_000) return emptyList()
        generatorsTriedAt = System.currentTimeMillis()
        val s = _state.value.settings
        val list = runCatching { client.mediaGenerators(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint) }.getOrNull()
        if (!list.isNullOrEmpty()) _state.update { it.copy(generators = list) }
        return list.orEmpty()
    }

    /** Unknown counts as yes: a model that refuses tools is asked again without them. */
    private fun modelTakesTools(provider: Provider, model: String) =
        modelCache[provider.id]?.firstOrNull { it.id == model }?.tools != false

    /**
     * Whether the model is shown what it made: local models that see images, and Grok. Other cloud models
     * aren't sent the pictures (the user's choice), so they only hear that the picture was sent.
     */
    private fun seesCreations(provider: Provider, model: String) = when (provider) {
        Providers.LOCAL -> modelCache[provider.id]?.firstOrNull { it.id == model }?.vision == true
        Providers.XAI -> modelCache[provider.id]?.firstOrNull { it.id == model }?.vision == true || Regex("grok-[4-9]").containsMatchIn(model)
        else -> false
    }

    /** Things the model made by itself since the user's last message. */
    private fun creationsThisTurn(history: List<Message>) =
        history.takeLastWhile { it.role != Role.USER }.count { it.media?.call != null }

    /** The `create` tool: what the PC can make, and how to prompt each picture model. Null when it can make nothing. */
    private fun createTool(gens: List<Generator>, persona: Persona, voiceMessages: Boolean = true): JSONArray? {
        val pictures = gens.filter { it.kind == "image" && it.mode == "create" }
        val voice = gens.firstOrNull { it.mode == "speech" }?.takeIf { voiceMessages }
        val music = gens.firstOrNull { it.mode == "music" }
        val canEdit = gens.any { it.kind == "image" && it.mode == "edit" }
        // A video starts from a picture, so it needs a way to make one.
        val video = videoGenerator(gens)?.takeIf { pictures.isNotEmpty() || canEdit }
        val kinds = listOfNotNull(
            "picture".takeIf { pictures.isNotEmpty() },
            "voice".takeIf { voice != null },
            "video".takeIf { video != null },
            "music".takeIf { music != null },
        )
        if (kinds.isEmpty()) return null
        val names = mapOf("picture" to "a picture", "voice" to "a voice message", "video" to "a short video", "music" to "music")
        val examples = mapOf(
            "picture" to "a photo, selfie, picture, drawing, screenshot",
            "voice" to "a voice message or voice note",
            "video" to "a video or clip",
            "music" to "a song or some music",
        )
        val description = buildString {
            append("Make ${kinds.map { names.getValue(it) }.joinToString(", ").replace(Regex(", ([^,]+)$"), " or $1")} ")
            append("on the user's own PC and send it into this chat. ")
            append("Use it when the user asks for one (${kinds.joinToString(", ") { examples.getValue(it) }})")
            if (persona.sendsOnItsOwn) append(", or on your own when it fits the moment")
            append(". You write the prompt yourself from the conversation, keeping what was said consistent: who and where, ")
            append("what they wear and do, the time of day and the light. You may write a short line before calling it. ")
            append("Saying you're sending something doesn't send it: call this to actually make and send it. ")
            if (video != null) append("A video is a picture brought to life for a few seconds: describe the picture in prompt " +
                "(or start from the last picture) and the movement in motion. The user is asked before it's made, since it takes minutes. ")
            append("Once it's sent you'll be told, and can carry on the conversation.")
        }
        val kindNotes = mapOf(
            "picture" to "picture",
            "voice" to "voice: a voice message spoken in your voice",
            "video" to "video: a few seconds of a picture coming to life",
            "music" to "music: a piece of music or a song",
        )
        val props = JSONObject()
        if (kinds.size > 1) props.put("kind", JSONObject().put("type", "string")
            .put("enum", JSONArray(kinds))
            .put("description", kinds.joinToString("; ") { kindNotes.getValue(it) }))
        props.put("prompt", JSONObject().put("type", "string").put("description",
            "For a picture: a detailed description of what is visible (subject, appearance, clothing, pose, setting, " +
                "framing such as 'selfie, phone at arm's length', lighting, style), written for the picture model. " +
                "Any text that should be readable in it (a screen, a message, a sign) is spelled out in double quotes, in the chat's language. " +
                (if (video != null) "For a video: the picture it starts from, written the same way. " else "") +
                (if (voice != null) "For a voice message: exactly the words to say, nothing else, one to three sentences. " else "") +
                (if (music != null) "For music: the style (genre, instruments, mood, tempo, kind of voice), not the lyrics." else "")))
        if (video != null) props.put("motion", JSONObject().put("type", "string").put("description",
            "Video only: what moves and how, in one or two sentences (she turns to the camera and laughs, waves roll in, " +
                "the camera slowly pushes in)."))
        if (video != null || music != null) props.put("seconds", JSONObject().put("type", "integer").put("description",
            listOfNotNull(
                video?.let { "Video: 1-${it.maxSeconds} seconds (default ${it.defaultSeconds})." },
                music?.let { "Music: 10-${it.maxSeconds} seconds (default 60)." },
            ).joinToString(" ")))
        if (music != null && music.lyrics) props.put("lyrics", JSONObject().put("type", "string").put("description",
            "Music only: the song's lyrics with [verse] and [chorus] lines, in the song's language. Leave out for instrumental music."))
        if (pictures.isNotEmpty()) {
            props.put("generator", JSONObject().put("type", "string").put("enum", JSONArray(pictures.map { it.id }))
                .put("description", "Picture model:\n" + pictures.joinToString("\n") { g -> "${g.id}: ${PROMPT_GUIDES[g.id] ?: g.note}" }))
            if (pictures.any { it.negativeDefault != null }) props.put("negative", JSONObject().put("type", "string")
                .put("description", "Optional: what to keep out of the picture (for qwen-uncensored and illustrious)."))
            props.put("aspect", JSONObject().put("type", "string")
                .put("enum", JSONArray(pictures.flatMap { it.aspects }.distinct()))
                .put("description", "Shape: 3:4 or 9:16 for selfies and phone photos, 9:16 for screenshots, 16:9 for wide scenes."))
            props.put("shows_you", JSONObject().put("type", "boolean")
                .put("description", "true when the picture shows you yourself (a selfie or any photo you appear in). You then look the same in every picture."))
            if (canEdit) props.put("start_from", JSONObject().put("type", "string").put("enum", JSONArray(listOf("nothing", "last_picture")))
                .put("description", "last_picture changes the latest picture in the chat (the user's or yours) and keeps the rest of it; " +
                    "write the prompt as the change to make. nothing makes a new picture."))
        }
        if (voice != null && voice.languages.isNotEmpty()) props.put("language", JSONObject().put("type", "string")
            .put("enum", JSONArray(voice.languages)).put("description", "Voice messages only: the language of the words."))
        if (voice != null && voice.voices.isNotEmpty()) props.put("speaker", JSONObject().put("type", "string")
            .put("enum", JSONArray(voice.voices)).put("description", "Voice messages only: whether you speak with a woman's or a man's voice. Always the same one for you."))
        val required = JSONArray().put("prompt")
        return JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", CREATE_TOOL)
            .put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", required))))
    }

    /** The character's face picture: this chat's own, else the behaviour's. */
    private fun faceOf(chat: Chat, persona: Persona?): String? =
        listOfNotNull(chat.facePath, persona?.facePath).firstOrNull { File(it).isFile }

    private fun voiceOf(chat: Chat, persona: Persona?): String? =
        listOfNotNull(chat.voicePath, persona?.voicePath).firstOrNull { File(it).isFile }

    /** The words of a voice message without what can't be said aloud: emoji and *stage directions*. */
    private fun spoken(text: String) = text
        .replace(Regex("\\*[^*]*\\*"), " ")
        .replace(Regex("[\\p{So}\\x{1F3FB}-\\x{1F3FF}\\x{FE0F}\\x{200D}]"), "")
        .replace(Regex("\\s+"), " ").trim()
        .ifEmpty { text }

    /** A woman's or a man's voice, from how the behaviour talks about the character, for when the model didn't say. */
    private fun speakerOf(persona: Persona, voices: List<String>): String {
        val text = "${persona.instructions} ${persona.looks}".lowercase()
        fun count(words: String) = Regex("\\b($words)\\b").findAll(text).count()
        val woman = count("she|her|hers|woman|girl|female|lady|wife|girlfriend|mother|sister|daughter")
        val man = count("he|him|his|man|boy|male|guy|husband|boyfriend|father|brother|son")
        return (if (man > woman) "man" else "woman").takeIf { it in voices } ?: voices.firstOrNull().orEmpty()
    }

    private fun personaOf(chat: Chat) = _state.value.settings.personas.firstOrNull { it.id == chat.personaId }

    /** Turns the model's `create` call into a job on the PC, as a reply of its own. */
    private fun createFromTool(chatId: String, call: ToolCall, persona: Persona) {
        val chat = current(chatId) ?: return
        val args = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())
        val gens = _state.value.generators
        val prompt = args.optString("prompt").trim()
        val voiceGen = gens.firstOrNull { it.mode == "speech" }
        val pictures = gens.filter { it.kind == "image" && it.mode == "create" }
        val kind = args.optString("kind").ifEmpty { if (pictures.isEmpty() && voiceGen != null) "voice" else "picture" }
        val label = persona.name.takeIf { persona.id != Personas.NONE }
        fun missing(what: String) = _state.update { it.copy(notice = "Your PC has no $what model to make this with.") }
        val gen: Generator
        val params: JSONObject
        var ask = false
        when (kind) {
            "voice" -> {
                gen = voiceGen ?: return missing("voice")
                val asked = args.optString("language")
                val language = gen.languages.firstOrNull { asked.isNotBlank() && it.startsWith(asked, ignoreCase = true) }
                val speaker = args.optString("speaker").takeIf { it in gen.voices } ?: speakerOf(persona, gen.voices)
                val base = CreateOptions(gen, speaker = speaker)
                params = createParams(if (language != null) base.copy(language = language) else base, spoken(prompt))
                voiceOf(chat, persona)?.let { params.put("voice", it) }
            }
            "music" -> {
                gen = gens.firstOrNull { it.mode == "music" } ?: return missing("music")
                val seconds = args.optInt("seconds").takeIf { it > 0 }?.coerceIn(10, gen.maxSeconds) ?: minOf(60, gen.maxSeconds)
                params = createParams(CreateOptions(gen, seconds = seconds, lyrics = songLyrics(args.optString("lyrics"))), prompt)
            }
            // A picture brought to life: made only after the user says yes, since it takes minutes (see answerVideo).
            "video" -> {
                gen = videoGenerator(gens) ?: return missing("video")
                val seconds = args.optInt("seconds").takeIf { it > 0 }?.coerceIn(1, gen.maxSeconds) ?: gen.defaultSeconds
                val motion = args.optString("motion").trim().ifEmpty { prompt }
                params = createParams(CreateOptions(gen, seconds = seconds), motion).put("picture", args.toString())
                ask = true
            }
            else -> {
                val (picture, pictureParams) = pictureJob(chat, persona, args) ?: return missing("picture")
                gen = picture
                params = pictureParams
            }
        }
        params.put("auto", true)
        createMedia(gen.name, params, call, label, ask = ask)
    }

    /**
     * ACE-Step reads section labels only as [verse], [chorus] and so on; models often write "(Verse 1)" or
     * "Chorus:", which would be sung as words.
     */
    private fun songLyrics(text: String): String {
        val label = Regex("""^\s*[\[(]?\s*(verse|chorus|pre-chorus|bridge|intro|outro|hook|interlude)\s*\d*\s*[\])]?\s*:?\s*$""", RegexOption.IGNORE_CASE)
        return text.lines().joinToString("\n") { line ->
            label.find(line)?.let { "[${it.groupValues[1].lowercase()}]" } ?: line
        }.trim()
    }

    /** Videos from the chat bring a picture to life: Wan 14B, or another video model that takes a picture. */
    private fun videoGenerator(gens: List<Generator>) =
        gens.firstOrNull { it.id == "wan-14b" } ?: gens.firstOrNull { it.kind == "video" && it.image != "none" }

    /** The picture job for the model's request: an edit of the character's face or the last picture, or a new one. */
    private fun pictureJob(chat: Chat, persona: Persona, args: JSONObject): Pair<Generator, JSONObject>? {
        val gens = _state.value.generators
        val prompt = args.optString("prompt").trim()
        val pictures = gens.filter { it.kind == "image" && it.mode == "create" }
        val face = faceOf(chat, persona)
        val editGen = gens.firstOrNull { it.id == "qwen-edit" } ?: gens.firstOrNull { it.kind == "image" && it.mode == "edit" }
        val lastPicture = chat.messages.lastOrNull { m -> m.attachments.any { it.isImage } }?.attachments?.lastOrNull { it.isImage }?.path
        // A picture of the character starts from its face, so it looks like the same person every time.
        val source = when {
            editGen == null -> null
            args.optString("start_from") == "last_picture" && lastPicture != null -> lastPicture
            args.optBoolean("shows_you") && face != null -> face
            else -> null
        }
        if (source != null && editGen != null) {
            val text = if (source == face) "A new photo of the same person as in the picture, with exactly the same face, hair, " +
                "skin tone and build. $prompt" else prompt
            return editGen to createParams(CreateOptions(editGen), text).put("source", source)
        }
        val gen = pictures.firstOrNull { it.id == args.optString("generator") } ?: pictures.firstOrNull { it.id == "z-image" }
            ?: pictures.firstOrNull() ?: return null
        val aspect = args.optString("aspect").takeIf { it in gen.aspects } ?: gen.aspects.firstOrNull { it == "3:4" } ?: gen.aspects.firstOrNull().orEmpty()
        // The workflow's own "avoid" list stays; the model's additions go after it.
        val negative = args.optString("negative").trim().takeIf { it.isNotEmpty() && gen.negativeDefault != null }
            ?.let { listOf(gen.negativeDefault.orEmpty(), it).filter(String::isNotBlank).joinToString(", ") }
        return gen to createParams(CreateOptions(gen, aspect = aspect, negative = negative), prompt)
    }

    /** "Edit and make again": the model's request is made again with the prompt the user changed, as a new reply. */
    fun remakeWithPrompt(message: Message, prompt: String) {
        val call = message.media?.call ?: return
        if (_state.value.streaming || prompt.isBlank()) return
        val args = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject()).put("prompt", prompt.trim())
        val chat = _state.value.current
        createFromTool(chat.id, ToolCall("call_${newId().take(8)}", call.name, args.toString()), personaOf(chat) ?: Personas.builtIn.first())
        // Asking for it again is the yes a video would otherwise wait for.
        _state.value.current.messages.lastOrNull()?.takeIf { it.media?.ask == true }?.let { answerVideo(it, yes = true) }
    }

    /** "Use as face": this picture becomes the character's face in this chat. */
    fun useAsFace(message: Message) {
        val picture = message.attachments.firstOrNull { it.isImage } ?: return
        val chatId = _state.value.current.id
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { copyCharacterFile(File(picture.path), "chat-$chatId-face") }
            setChatFace(chatId, path)
            _state.update { it.copy(notice = "This picture is now the face in this chat") }
        }
    }

    private fun setChatFace(chatId: String, path: String?) {
        current(chatId)?.facePath?.takeIf { it != path }?.let { File(it).delete() }
        mutateChat(chatId) { it.copy(facePath = path) }
        persist(chatId)
    }

    /** Sets (or with null clears) this chat's own face picture or voice sample from a file the user picked. */
    fun setChatCharacterFile(face: Boolean, uri: Uri?) {
        val chatId = _state.value.current.id
        viewModelScope.launch {
            val path = uri?.let { importCharacterFile(it, "chat-$chatId-" + if (face) "face" else "voice") } ?: if (uri != null) return@launch else null
            if (face) setChatFace(chatId, path) else {
                current(chatId)?.voicePath?.takeIf { it != path }?.let { File(it).delete() }
                mutateChat(chatId) { it.copy(voicePath = path) }
                persist(chatId)
            }
        }
    }

    /** Makes this chat's face or voice the behaviour's own, so new chats with it start from it. */
    fun saveCharacterToBehaviour(face: Boolean) {
        val chat = _state.value.current
        val persona = personaOf(chat)?.takeIf { !it.builtIn } ?: return
        val from = (if (face) chat.facePath else chat.voicePath)?.let(::File)?.takeIf { it.isFile } ?: return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { copyCharacterFile(from, "persona-${persona.id}-" + if (face) "face" else "voice") }
            val old = if (face) persona.facePath else persona.voicePath
            old?.let { File(it).delete() }
            updatePersona(if (face) persona.copy(facePath = path) else persona.copy(voicePath = path))
            _state.update { it.copy(notice = "Saved to ${persona.name}") }
        }
    }

    /** Copies a picked picture or sound into the characters folder (pictures downscaled like attachments). */
    suspend fun importCharacterFile(uri: Uri, prefix: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val imported = AttachmentProcessor.process(getApplication(), uri, store.characterDir).first()
            val ok = if (prefix.endsWith("face")) imported.isImage else imported.isAudio
            if (!ok) {
                File(imported.path).delete()
                throw IllegalArgumentException(if (prefix.endsWith("face")) "Pick a picture" else "Pick a sound file")
            }
            val dest = File(store.characterDir, "$prefix-${newId()}.${imported.path.substringAfterLast('.')}")
            File(imported.path).renameTo(dest)
            dest.path
        }.onFailure { e -> _state.update { it.copy(notice = e.message ?: "Couldn't use that file") } }.getOrNull()
    }

    fun discardCharacterFile(path: String?) {
        if (path != null && path.startsWith(store.characterDir.path)) viewModelScope.launch(Dispatchers.IO) { File(path).delete() }
    }

    private fun copyCharacterFile(from: File, prefix: String): String {
        val dest = File(store.characterDir, "$prefix-${newId()}.${from.extension.ifBlank { "jpg" }}")
        from.copyTo(dest, overwrite = true)
        return dest.path
    }

    /** Creates an image from the last message (and any photos attached to it) and adds it as the reply. */
    private fun createImage() {
        val snapshot = _state.value
        val settings = snapshot.settings
        val provider = settings.provider
        val history = snapshot.current.messages
        val last = history.last()
        val reply = Message(role = Role.ASSISTANT, model = settings.imageModel(provider))
        val chat = snapshot.current.copy(
            messages = history + reply,
            updatedAt = System.currentTimeMillis(),
            providerId = provider.id,
            model = settings.model(provider),
        )
        _state.update { s ->
            s.copy(current = chat, chats = listOf(chat) + s.chats.filter { it.id != chat.id }, streaming = true, status = "Creating your image…")
        }
        persist(chat.id)

        val run = ++runId
        job = viewModelScope.launch {
            var cancelled = false
            try {
                val key = settings.key(provider)
                if (key.isBlank()) throw ApiException("Add your ${provider.name} API key in Settings to create images.")
                val refs = withContext(Dispatchers.IO) { last.attachments.filter { it.isImage }.mapNotNull(AttachmentProcessor::dataUrl) }
                val (bytes, mime) = client.generateImage(provider, key, settings.imageModel(provider), last.content.ifBlank { "Create an image" }, refs)
                val file = File(store.attachmentDir, "${newId()}.${when { "jpeg" in mime -> "jpg"; "webp" in mime -> "webp"; else -> "png" }}")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                updateMessage(chat.id, reply.id) { it.copy(attachments = listOf(Attachment(Attachment.KIND_IMAGE, "Created image", file.path))) }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                updateMessage(chat.id, reply.id) { it.copy(error = e.message ?: "Couldn't create the image") }
            } finally {
                if (cancelled) updateMessage(chat.id, reply.id) { it.copy(stopped = true) }
                if (runId == run) _state.update { it.copy(streaming = false, status = null) }
                persist(chat.id)
            }
        }
    }

    /** Makes a video from the last message (bringing its photo to life, if it has one) and adds it as the reply. */
    private fun createVideo() {
        val snapshot = _state.value
        val settings = snapshot.settings
        val provider = settings.provider
        val history = snapshot.current.messages
        val last = history.last()
        val model = settings.videoModel(provider)
        val reply = Message(role = Role.ASSISTANT, model = model)
        val chat = snapshot.current.copy(
            messages = history + reply,
            updatedAt = System.currentTimeMillis(),
            providerId = provider.id,
            model = settings.model(provider),
        )
        _state.update { s ->
            s.copy(current = chat, chats = listOf(chat) + s.chats.filter { it.id != chat.id }, streaming = true, status = "Making your video…")
        }
        persist(chat.id)

        val run = ++runId
        job = viewModelScope.launch {
            try {
                val key = settings.key(Providers.XAI)
                if (key.isBlank()) throw ApiException("Add your ${provider.name} API key in Settings to make videos.")
                val photo = withContext(Dispatchers.IO) { last.attachments.firstOrNull { it.isImage }?.let(AttachmentProcessor::dataUrl) }
                val id = client.startXaiVideo(key, model, last.content, photo, seconds = VIDEO_SECONDS, aspect = if (photo == null) "9:16" else null)
                // Saved straight away: from here on the video is xAI's to finish, whatever happens to the app.
                val started = CloudJob(id, System.currentTimeMillis())
                updateMessage(chat.id, reply.id) { it.copy(cloudJob = started) }
                persist(chat.id)
                if (runId == run) followVideo(chat.id, reply.copy(cloudJob = started))
            } catch (e: CancellationException) {
                updateMessage(chat.id, reply.id) { it.copy(stopped = true) }
                persist(chat.id)
                if (runId == run) _state.update { it.copy(streaming = false, status = null) }
                throw e
            } catch (e: Exception) {
                updateMessage(chat.id, reply.id) { it.copy(error = e.message ?: "Couldn't make the video") }
                persist(chat.id)
                if (runId == run) _state.update { it.copy(streaming = false, status = null) }
            }
        }
    }

    /**
     * Collects a Grok video xAI is making (or has made). Leaving the chat only stops watching, so reopening it, or
     * Retry after an error that wasn't xAI's last word, picks it up again; Stop gives it up.
     */
    private fun followVideo(chatId: String, reply: Message) {
        val cloud = reply.cloudJob ?: return
        val key = _state.value.settings.key(Providers.XAI)
        _state.update { it.copy(streaming = true, status = "Making your video…") }
        val run = ++runId
        job = viewModelScope.launch {
            try {
                if (key.isBlank()) throw ApiException("Add your xAI API key in Settings to collect this video.")
                val bytes = client.awaitXaiVideo(key, cloud.id) { pct ->
                    if (runId == run) _state.update { it.copy(status = "Making your video… $pct%") }
                }
                val file = File(store.attachmentDir, "${newId()}.mp4")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                updateMessage(chatId, reply.id) {
                    it.copy(
                        attachments = listOf(Attachment(Attachment.KIND_VIDEO, "Created video", file.path)),
                        took = System.currentTimeMillis() - cloud.startedAt,
                        cloudJob = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e // still on xAI's side: followed again when the chat is opened
            } catch (e: VideoGoneException) {
                updateMessage(chatId, reply.id) { it.copy(error = e.message, cloudJob = null) }
            } catch (e: Exception) {
                updateMessage(chatId, reply.id) { it.copy(error = e.message ?: "Couldn't collect the video. Retry to try again.") }
            } finally {
                if (runId == run) _state.update { it.copy(streaming = false, status = null) }
                persist(chatId)
            }
        }
    }

    private fun onStreamEvent(chatId: String, messageId: String, event: StreamEvent, buffer: TokenBuffer) {
        when (event) {
            is StreamEvent.Text -> synchronized(buffer) { buffer.text.append(event.text) }
            is StreamEvent.Reasoning -> synchronized(buffer) { buffer.reasoning.append(event.text) }
            is StreamEvent.Citation -> {
                flush(chatId, messageId, buffer)
                updateMessage(chatId, messageId) { if (event.url in it.citations) it else it.copy(citations = it.citations + event.url) }
            }
            is StreamEvent.Status -> {
                flush(chatId, messageId, buffer)
                _state.update { it.copy(status = event.text) }
                this@ChatViewModel.call.replyStatus(event.text)
            }
            is StreamEvent.Usage -> updateMessage(chatId, messageId) { it.copy(promptTokens = event.prompt, outputTokens = event.output) }
            is StreamEvent.Call -> Unit // handled where the reply is streamed
        }
        val now = System.currentTimeMillis()
        synchronized(buffer) {
            val wait = STREAM_FRAME_MS - (now - buffer.flushedAt)
            if (wait <= 0) flush(chatId, messageId, buffer)
            else if (!buffer.flushScheduled) {
                // Tokens that arrive just after an update still show within one frame period, even if the stream pauses.
                buffer.flushScheduled = true
                viewModelScope.launch { delay(wait); flush(chatId, messageId, buffer) }
            }
        }
        // Save the partial reply now and then, so a killed app keeps most of it.
        if (now - lastStreamSave > 3000) {
            lastStreamSave = now
            persist(chatId)
        }
    }

    /** Applies the buffered tokens to the reply in one state update (under the lock, so chunks can't swap order). */
    private fun flush(chatId: String, messageId: String, buffer: TokenBuffer) = synchronized(buffer) {
        buffer.flushedAt = System.currentTimeMillis()
        buffer.flushScheduled = false
        if (buffer.text.isNotEmpty() || buffer.reasoning.isNotEmpty()) {
            val text = buffer.text.toString()
            val reasoning = buffer.reasoning.toString()
            buffer.text.clear()
            buffer.reasoning.clear()
            updateMessage(chatId, messageId) { it.copy(content = it.content + text, reasoning = it.reasoning + reasoning) }
            if (_state.value.status != null) _state.update { it.copy(status = null) }
        }
    }

    /**
     * The conversation as the provider gets it. With [tools], things the model made itself go back as its tool
     * calls and their results, and when it [sees] pictures, the latest of them are shown to it; without, they're
     * short notes like anything made from the Create sheet.
     */
    private fun buildApiMessages(
        settings: Settings,
        persona: Persona,
        history: List<Message>,
        tools: Boolean,
        sees: Boolean,
        /** In a call: "" or what's shared ("camera", "screen"); null outside calls. */
        call: String? = null,
    ): List<ApiMessage> {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm z"))
        // The "None" persona sends no system prompt at all: the model's own default behaviour.
        val system = if (persona.id == Personas.NONE) null else buildString {
            append(persona.instructions.trim())
            if (persona.looks.isNotBlank()) append("\n\nYour appearance (pictures of you must match it):\n").append(persona.looks.trim())
            if (tools && persona.sendsOnItsOwn) append(
                "\n\nYou can send a picture or a voice message on your own, without being asked, when it fits the moment " +
                    "and who you are, the way a real person texting would. Only when it adds something, not every message.",
            )
            if (settings.aboutMe.isNotBlank()) append("\n\nAbout the user (use when relevant):\n").append(settings.aboutMe.trim())
            append("\n\nCurrent date and time: ").append(now)
        }
        val byModel = { m: Message -> tools && m.media?.call != null }
        // Drop failed/empty replies. Things made on the PC stay as a short note so the model knows they exist.
        val kept = history.mapNotNull { m ->
            when {
                byModel(m) -> m
                tools && m.media?.params?.contains("\"for_video\"") == true -> null
                m.role == Role.USER || m.content.isNotEmpty() -> m
                m.media != null && m.attachments.isNotEmpty() -> {
                    val what = m.attachments.first().let { if (it.isVideo) "a video" else if (it.isAudio) "a sound clip" else "an image" }
                    Message(id = m.id, role = m.role, content = "[Made $what with ${m.model ?: "ComfyUI"} on the user's PC]")
                }
                else -> null
            }
        }
        // The whole conversation is resent every turn, so the model remembers earlier photos and files.
        // Photos from older messages are swapped for a note so a long chat can't overflow the model's context;
        // the model's own earlier replies about them still carry what it saw.
        val withImages = kept.filter { m -> m.attachments.any { it.isImage } && (m.role == Role.USER || (byModel(m) && sees)) }
            .takeLast(MAX_IMAGE_MESSAGES).map { it.id }.toSet()
        val turns = mutableListOf<ApiMessage>()
        for (m in kept) {
            val call = m.media?.call
            if (byModel(m) && call != null) {
                // The call goes on the words the model wrote just before it, as one assistant turn.
                val prev = turns.lastOrNull()
                if (prev != null && prev.role == Role.ASSISTANT && prev.toolCalls.isEmpty() && prev.toolCallId == null) {
                    turns[turns.lastIndex] = prev.copy(toolCalls = listOf(call))
                } else turns += ApiMessage(Role.ASSISTANT, "", toolCalls = listOf(call))
                val picture = m.attachments.firstOrNull { it.isImage }
                turns += ApiMessage("tool", creationResult(m, shown = picture != null && m.id in withImages), toolCallId = call.id)
                if (picture != null && m.id in withImages) modelImage(picture)?.let { url ->
                    // Most chat formats only take pictures from the user, so it goes as a note the model can't mistake for theirs.
                    turns += ApiMessage(
                        Role.USER,
                        "(Note from the app, not written by the user: below is the picture YOU sent to the user. You made and sent it; " +
                            "the user didn't. Check it shows what you meant, then reply to the user as yourself.)",
                        listOf(url),
                    )
                }
                continue
            }
            val files = m.attachments.filter { it.kind == Attachment.KIND_TEXT }.joinToString("") { a ->
                "\n\n<file name=\"${a.name}\">\n${AttachmentProcessor.readText(a)}\n</file>"
            }
            val photos = m.attachments.filter { it.isImage }
            turns += if (m.id in withImages || photos.isEmpty()) {
                ApiMessage(m.role, m.content + files, photos.mapNotNull(AttachmentProcessor::dataUrl))
            } else {
                ApiMessage(m.role, m.content + files + "\n\n[${photos.size} earlier photo(s) not resent]", emptyList())
            }
        }
        // A call adds how to talk on it, even with "None" (the voice can't read Markdown out).
        val prompt = listOfNotNull(system, call?.let { callGuidance(it.ifEmpty { null }) }).joinToString("\n\n").ifEmpty { null }
        return listOfNotNull(prompt?.let { ApiMessage("system", it) }) + turns
    }

    /** What the model is told about something it asked for. */
    private fun creationResult(m: Message, shown: Boolean): String {
        val speech = _state.value.generators.firstOrNull { it.id == m.media?.generator }?.mode == "speech" || m.media?.generator == "chatterbox"
        val what = m.attachments.firstOrNull()?.let {
            if (it.isAudio) (if (speech) "voice message" else "music") else if (it.isVideo) "video" else "picture"
        }
        val declined = m.media?.params?.let { p -> runCatching { JSONObject(p).optBoolean("declined") }.getOrDefault(false) } == true
        return when {
            m.media?.ask == true -> "The user is being asked whether to make this video now (it takes a few minutes on their PC). " +
                "Don't ask for it again; carry on the conversation."
            declined -> "The user said not now to the video, so it wasn't made. Don't make it again unless they ask; carry on."
            what == null && m.stopped -> "The user stopped it before it was made."
            what == null -> "It couldn't be made: ${m.error ?: "unknown error"}"
            what == "picture" && shown -> "Sent the picture to the user (made with ${m.model}); they're looking at it now. " +
                "If it clearly doesn't show what you meant, you may call create once more with a better prompt. " +
                "Otherwise carry on the conversation naturally, consistent with what the picture shows; don't describe it at length."
            what == "picture" -> "Sent the picture to the user (made with ${m.model} from your prompt). Carry on the conversation naturally."
            what == "video" -> "Sent the video to the user: the picture from your prompt, brought to life. Carry on the conversation naturally."
            what == "music" -> "Sent the music to the user. Carry on the conversation naturally."
            // Without this a model answers every later message with another voice message, copying its own last turns.
            else -> "Sent the $what to the user. Carry on the conversation naturally; your next messages are text again, " +
                "unless the user asks for another $what or the moment really calls for one."
        }
    }

    /** A picture for the model to look at: a JPEG no wider than 1024 px, whatever the PC made it as. */
    private fun modelImage(a: Attachment): String? = runCatching {
        if (a.path.endsWith(".jpg", true) || a.path.endsWith(".jpeg", true)) return AttachmentProcessor.dataUrl(a)
        val bitmap = android.graphics.BitmapFactory.decodeFile(a.path) ?: return null
        val scale = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
        val sized = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = java.io.ByteArrayOutputStream()
        sized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    private fun updateMessage(chatId: String, messageId: String, transform: (Message) -> Message) =
        mutateChat(chatId) { c -> c.copy(messages = c.messages.map { if (it.id == messageId) transform(it) else it }) }

    /** Applies a change to a chat wherever it lives: the open chat, the history list, or both. */
    private fun mutateChat(chatId: String, transform: (Chat) -> Chat) = _state.update { s ->
        val base = if (s.current.id == chatId) s.current else s.chats.firstOrNull { it.id == chatId } ?: return@update s
        val updated = transform(base)
        s.copy(
            current = if (s.current.id == chatId) updated else s.current,
            chats = s.chats.map { if (it.id == chatId) updated else it },
        )
    }

    private fun persist(chatId: String) {
        val s = _state.value
        val chat = (if (s.current.id == chatId) s.current else s.chats.firstOrNull { it.id == chatId }) ?: return
        if (chat.messages.isEmpty()) return
        viewModelScope.launch(io) { store.save(chat) }
    }

    /** A reply saved before it got any text (app killed mid-stream) gets an error card, so it can be retried. */
    private fun markInterrupted(chat: Chat): Chat {
        val last = chat.messages.lastOrNull() ?: return chat
        if (last.role != Role.ASSISTANT || last.content.isNotEmpty() || last.error != null || last.stopped || last.attachments.isNotEmpty()) return chat
        if (last.media?.jobId != null) return chat // still on the PC: followed again when the chat is opened
        if (last.cloudJob != null) return chat // still on xAI's side, likewise
        return chat.copy(messages = chat.messages.dropLast(1) + last.copy(error = "The reply was interrupted before it finished."))
    }

    private fun titleFor(message: Message): String {
        val text = message.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: message.attachments.firstOrNull()?.name
            ?: "New chat"
        return if (text.length > 48) text.take(47).trimEnd() + "…" else text
    }

    // --- Read aloud -----------------------------------------------------------------------------

    fun speak(markdown: String) {
        val chunks = plainText(markdown).split(Regex("\n{2,}")).filter { it.isNotBlank() }
            .flatMap { it.chunked(TextToSpeech.getMaxSpeechInputLength() - 1) }
        if (ttsReady) {
            say(chunks)
            return
        }
        pendingSpeech = chunks
        if (tts == null) {
            tts = TextToSpeech(getApplication()) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) pendingSpeech?.let(::say) else _state.update { it.copy(notice = "Text-to-speech isn't available") }
                pendingSpeech = null
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    private fun say(chunks: List<String>) {
        val engine = tts ?: return
        chunks.forEachIndexed { i, chunk ->
            engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "cubic-$i")
        }
    }

    private fun plainText(markdown: String) = markdown
        .replace(Regex("```[\\s\\S]*?```"), " (code omitted) ")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("[*_`#>|~]"), "")

    override fun onCleared() {
        call.end()
        tts?.shutdown()
        runCatching { connectivity?.unregisterNetworkCallback(networkWatch) }
        // The app was closed: its work stops with it (a job on the PC is picked up again when it's opened).
        WorkService.update(getApplication(), null)
        WorkService.onStopTapped = null
    }
}

private const val CREATE_TOOL = "create"

/** A reply announcing a picture or voice message ("sending you a selfie", "here's a pic"), in English. */
private val ANNOUNCED = Regex(
    """\b(send(ing)?|sent|here'?s|here is|snap(ping)?|tak(e|ing)|attach(ing)?)\b[^.!?\n]{0,40}\b(pics?|pictures?|photos?|selfies?|screenshots?|images?|voice (message|note)s?|vm|recording)\b""",
    RegexOption.IGNORE_CASE,
)

/** A spoken yes to the video the model offered. */
private val SPOKEN_YES = Regex("""^\W*(yes|yeah|yep|yup|sure|ok(ay)?|do it|go ahead|make it|please)\b""", RegexOption.IGNORE_CASE)

/** A reply that is nothing but "(…)" or "[…]", or nothing at all, after the model looked at its own picture. */
private val STAGE_DIRECTION = Regex("""^(\s*|[(\[*][^)\]]*[)\]*]\s*)$""")

private fun mimeFor(a: Attachment) = when (a.path.substringAfterLast('.').lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "flac" -> "audio/flac"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    else -> "application/octet-stream"
}
