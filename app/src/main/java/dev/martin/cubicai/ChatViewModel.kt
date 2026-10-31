package dev.martin.cubicai

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.martin.cubicai.data.Attachment
import dev.martin.cubicai.data.AttachmentProcessor
import dev.martin.cubicai.data.Chat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        client.findPc = { fp -> if (_state.value.settings.isPaired) finder.find(fp) else null }
        // An IPv6 find means IPv4 isn't getting through right now: it updates that address, not the home one.
        client.onPcMoved = { url -> updateSettings { if (url.startsWith("https://[")) it.copy(localLan6Url = url) else it.copy(localUrl = url) } }
        viewModelScope.launch {
            val chats = withContext(io) { store.loadAll() }.map(::markInterrupted)
            _state.update { it.copy(chats = chats) }
        }
        refreshContextLimit()
    }

    // --- Settings -------------------------------------------------------------------------------

    fun updateSettings(transform: (Settings) -> Settings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        settingsStore.save(_state.value.settings)
    }

    /** Picks the model for the open chat (it remembers it) and for new chats. */
    fun selectModel(provider: Provider, model: String) {
        updateSettings { it.copy(providerId = provider.id, models = it.models + (provider.id to model)) }
        if (!provider.images) _state.update { it.copy(imageMode = false) }
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

    fun toggleImageMode() = _state.update { it.copy(imageMode = !it.imageMode) }

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
        _state.update { it.copy(create = options, imageMode = false) }
        if (options != null) updateSettings {
            it.copy(lastCreate = listOf(options.generator.id, options.aspect, options.seconds, options.language).joinToString("|"))
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

    /** Starts a PC generation for the last user message, as a new reply. */
    private fun createMedia(generatorName: String, params: JSONObject) {
        val snapshot = _state.value
        // Shapes the progress card like the photo being worked on (the app's own field; the PC ignores it).
        sourceImage(snapshot.current.messages, params.getString("generator"))?.let { a ->
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(a.path, o)
            if (o.outWidth > 0 && o.outHeight > 0) params.put("frame", "${o.outWidth}:${o.outHeight}")
        }
        val reply = Message(
            role = Role.ASSISTANT,
            model = generatorName,
            media = MediaRequest(params.getString("generator"), params.toString()),
        )
        val chat = snapshot.current.copy(messages = snapshot.current.messages + reply, updatedAt = System.currentTimeMillis())
        _state.update { s -> s.copy(current = chat, chats = listOf(chat) + s.chats.filter { it.id != chat.id }) }
        persist(chat.id)
        watchMedia(chat.id, reply)
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
        job = viewModelScope.launch {
            var cancelled = false
            try {
                if (!settings.isPaired) throw ApiException("Pair with your PC first: Settings → Local PC.")
                var jobId = request.jobId
                if (jobId == null) {
                    setProgress(reply.id, MediaProgress("sending", "Sending to your PC"))
                    val all = (_state.value.chats.firstOrNull { it.id == chatId } ?: _state.value.current).messages
                    val history = all.take(all.indexOfFirst { it.id == reply.id }.coerceAtLeast(0))
                    val source = history.lastOrNull { it.role == Role.USER }
                    val image = sourceImage(history, request.generator)?.let { File(it.path) }
                    val audio = source?.attachments?.firstOrNull { it.isAudio }?.let { File(it.path) }
                    val id = client.submitMedia(baseUrls, token, pin, JSONObject(request.params), image, audio)
                    jobId = id
                    updateMessage(chatId, reply.id) { it.copy(media = request.copy(jobId = id)) }
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
                            it.copy(attachments = files, error = null, media = it.media?.copy(seed = final.seed))
                        }
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
            }
        }
    }

    private fun setProgress(messageId: String, progress: MediaProgress?) = _state.update {
        it.copy(mediaProgress = if (progress == null) it.mediaProgress - messageId else it.mediaProgress + (messageId to progress))
    }

    /** Picks a PC job back up when its chat is opened again (after switching chats or restarting the app). */
    private fun resumeMedia(chat: Chat) {
        val last = chat.messages.lastOrNull() ?: return
        if (last.mediaPending && last.media?.jobId != null && !_state.value.streaming) watchMedia(chat.id, last)
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
    }

    fun savePersona(persona: Persona) {
        val p = if (persona.id.isBlank()) persona.copy(id = newId()) else persona
        updateSettings { s ->
            val exists = s.customPersonas.any { it.id == p.id }
            s.copy(customPersonas = if (exists) s.customPersonas.map { if (it.id == p.id) p else it } else s.customPersonas + p)
        }
        selectPersona(p.id)
    }

    fun deletePersona(id: String) {
        updateSettings { s ->
            s.copy(
                customPersonas = s.customPersonas.filterNot { it.id == id },
                personaId = if (s.personaId == id) Personas.NONE else s.personaId,
            )
        }
        if (_state.value.current.personaId == id) selectPersona(Personas.NONE)
    }

    fun setAboutMe(text: String) = updateSettings { it.copy(aboutMe = text) }

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

    /** A deleted chat's unfinished PC jobs are stopped too, so nothing it asked for is left on the PC. */
    private fun cancelPcJobs(chat: Chat) {
        val ids = chat.messages.filter { it.mediaPending }.mapNotNull { it.media?.jobId }
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
                messages = c.messages + message,
            )
        }
        _state.update { it.copy(current = chat, pending = emptyList(), draft = "") }
        val create = s.create
        when {
            create != null && s.settings.provider == Providers.LOCAL -> createMedia(create.generator.name, createParams(create, text.trim()))
            s.imageMode && s.settings.provider.images -> createImage()
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
            createMedia(lastReply.model ?: media.generator, params)
            return
        }
        val provider = _state.value.settings.provider
        // A failed or unwanted picture is redone as a picture, not as text.
        val wasImage = provider.images && c.messages.lastOrNull { it.role == Role.ASSISTANT }?.model == provider.imageModel
        val trimmed = c.messages.dropLastWhile { it.role == Role.ASSISTANT }
        if (trimmed.isEmpty()) return
        discard(c.messages.drop(trimmed.size).flatMap { it.attachments }) // e.g. a created image being redone
        _state.update { it.copy(current = c.copy(messages = trimmed)) }
        if (wasImage) createImage() else complete()
    }

    fun stop() {
        job?.cancel()
        val c = _state.value.current
        val pending = c.messages.lastOrNull()?.takeIf { it.mediaPending } ?: return
        setProgress(pending.id, null)
        updateMessage(c.id, pending.id) { it.copy(stopped = true, media = it.media?.copy(jobId = null)) }
        persist(c.id)
        pending.media?.jobId?.let { id ->
            val s = _state.value.settings
            viewModelScope.launch { client.cancelMedia(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint, id) }
        }
    }

    // --- Completion -----------------------------------------------------------------------------

    private fun complete() {
        val snapshot = _state.value
        val settings = snapshot.settings
        val provider = settings.provider
        val model = settings.model(provider)
        val history = snapshot.current.messages
        val persona = settings.personas.firstOrNull { it.id == snapshot.current.personaId } ?: Personas.builtIn.first()
        val reply = Message(role = Role.ASSISTANT, model = model, persona = persona.name.takeIf { persona.id != Personas.NONE })
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
        job = viewModelScope.launch {
            var cancelled = false
            try {
                val baseUrls = settings.baseUrls(provider)
                val key = settings.key(provider)
                when {
                    provider == Providers.LOCAL && !settings.isPaired -> throw ApiException("Pair with your PC first: Settings → Local PC.")
                    baseUrls.isEmpty() -> throw ApiException("Set the base URL for the custom provider in Settings.")
                    key.isBlank() && provider != Providers.CUSTOM -> throw ApiException("Add your ${provider.name} API key in Settings to start chatting.")
                    model.isBlank() -> throw ApiException("Pick a model first (tap the model name at the top).")
                }
                val messages = withContext(Dispatchers.IO) { buildApiMessages(settings, persona, history) }
                client.stream(baseUrls, key, model, messages, provider.search, settings.webSearch, settings.pin(provider)) { event ->
                    onStreamEvent(chat.id, reply.id, event, buffer)
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                updateMessage(chat.id, reply.id) { it.copy(error = e.message ?: "Something went wrong") }
            } finally {
                flush(chat.id, reply.id, buffer)
                updateMessage(chat.id, reply.id) { m ->
                    when {
                        cancelled -> m.copy(stopped = true)
                        m.content.isEmpty() && m.error == null -> m.copy(error = "The model returned an empty response.")
                        else -> m
                    }
                }
                if (runId == run) _state.update { it.copy(streaming = false, status = null) }
                persist(chat.id)
            }
        }
    }

    /** Creates an image from the last message (and any photos attached to it) and adds it as the reply. */
    private fun createImage() {
        val snapshot = _state.value
        val settings = snapshot.settings
        val provider = settings.provider
        val history = snapshot.current.messages
        val last = history.last()
        val reply = Message(role = Role.ASSISTANT, model = provider.imageModel)
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
                val (bytes, mime) = client.generateImage(key, provider.imageModel, last.content.ifBlank { "Create an image" }, refs)
                val file = File(store.attachmentDir, "${newId()}.${if ("jpeg" in mime) "jpg" else "png"}")
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
            }
            is StreamEvent.Usage -> updateMessage(chatId, messageId) { it.copy(promptTokens = event.prompt, outputTokens = event.output) }
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

    private fun buildApiMessages(settings: Settings, persona: Persona, history: List<Message>): List<ApiMessage> {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm z"))
        // The "None" persona sends no system prompt at all: the model's own default behaviour.
        val system = if (persona.id == Personas.NONE) null else buildString {
            append(persona.instructions.trim())
            if (settings.aboutMe.isNotBlank()) append("\n\nAbout the user (use when relevant):\n").append(settings.aboutMe.trim())
            append("\n\nCurrent date and time: ").append(now)
        }
        // Drop failed/empty replies. Things made on the PC stay as a short note so the model knows they exist.
        val kept = history.mapNotNull { m ->
            when {
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
        val withImages = kept.filter { m -> m.attachments.any { it.isImage } }.takeLast(MAX_IMAGE_MESSAGES).map { it.id }.toSet()
        val turns = kept.map { m ->
            val files = m.attachments.filter { it.kind == Attachment.KIND_TEXT }.joinToString("") { a ->
                "\n\n<file name=\"${a.name}\">\n${AttachmentProcessor.readText(a)}\n</file>"
            }
            val photos = m.attachments.filter { it.isImage }
            if (m.id in withImages || photos.isEmpty()) {
                ApiMessage(m.role, m.content + files, photos.mapNotNull(AttachmentProcessor::dataUrl))
            } else {
                ApiMessage(m.role, m.content + files + "\n\n[${photos.size} earlier photo(s) not resent]", emptyList())
            }
        }
        return listOfNotNull(system?.let { ApiMessage("system", it) }) + turns
    }

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
        tts?.shutdown()
        runCatching { connectivity?.unregisterNetworkCallback(networkWatch) }
    }
}

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
