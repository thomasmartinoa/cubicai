package dev.martin.cubicai

import android.app.Application
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.martin.cubicai.data.Attachment
import dev.martin.cubicai.data.AttachmentProcessor
import dev.martin.cubicai.data.Chat
import dev.martin.cubicai.data.ChatStore
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
import dev.martin.cubicai.net.LlmClient
import dev.martin.cubicai.net.ModelInfo
import dev.martin.cubicai.net.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class UiState(
    val settings: Settings,
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
)

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
        viewModelScope.launch {
            val chats = withContext(io) { store.loadAll() }.map(::markInterrupted)
            _state.update { it.copy(chats = chats) }
        }
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
    }

    fun toggleWebSearch() = updateSettings { it.copy(webSearch = !it.webSearch) }

    fun toggleImageMode() = _state.update { it.copy(imageMode = !it.imageMode) }

    suspend fun listModels(provider: Provider): List<ModelInfo> {
        modelCache[provider.id]?.let { return it }
        val s = _state.value.settings
        val baseUrls = s.baseUrls(provider)
        if (baseUrls.isEmpty()) {
            throw ApiException(if (provider == Providers.LOCAL) "Pair with your PC first: Settings → Local PC." else "Set a base URL in Settings first.")
        }
        return client.listModels(baseUrls, s.key(provider), s.pin(provider)).also { modelCache[provider.id] = it }
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

    /** Pairs with the PC gateway from a scanned/pasted `gk://pair` link and switches to it. */
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
            it.copy(localUrl = "", localRemoteUrl = "", localFingerprint = "", apiKeys = it.apiKeys + (Providers.LOCAL.id to ""))
        }
        modelCache.remove(Providers.LOCAL.id)
    }

    fun testLocalConnection() {
        modelCache.remove(Providers.LOCAL.id)
        viewModelScope.launch {
            val result = runCatching { listModels(Providers.LOCAL) }
            _state.update {
                it.copy(notice = result.fold({ list -> "Connected · ${list.size} models" }, { e -> e.message ?: "Connection failed" }))
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
        }
    }

    fun deleteChat(id: String) {
        val chat = _state.value.chats.firstOrNull { it.id == id } ?: return
        if (_state.value.current.id == id) job?.cancel()
        _state.update { s ->
            s.copy(chats = s.chats - chat, current = if (s.current.id == id) Chat(personaId = s.settings.personaId) else s.current)
        }
        viewModelScope.launch(io) { store.delete(chat) }
    }

    fun deleteAllChats() {
        job?.cancel()
        _state.update { it.copy(chats = emptyList(), current = Chat(personaId = it.settings.personaId), pending = emptyList()) }
        viewModelScope.launch(io) { store.deleteAll() }
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
        if (s.imageMode && s.settings.provider.images) createImage() else complete()
    }

    fun editMessage(id: String, text: String) {
        val c = _state.value.current
        val index = c.messages.indexOfFirst { it.id == id }
        if (_state.value.streaming || index < 0) return
        val edited = c.messages[index].copy(content = text.trim())
        discard(c.messages.drop(index + 1).flatMap { it.attachments }) // later turns are gone; so are their files
        _state.update { it.copy(current = c.copy(messages = c.messages.take(index) + edited)) }
        complete()
    }

    fun regenerate() {
        val c = _state.value.current
        if (_state.value.streaming) return
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
        val kept = history.filter { it.role == Role.USER || it.content.isNotEmpty() } // drop failed/empty replies
        // The whole conversation is resent every turn, so the model remembers earlier photos and files.
        // Photos from older messages are swapped for a note so a long chat can't overflow the model's context;
        // the model's own earlier replies about them still carry what it saw.
        val withImages = kept.filter { m -> m.attachments.any { it.isImage } }.takeLast(MAX_IMAGE_MESSAGES).map { it.id }.toSet()
        val turns = kept.map { m ->
            val files = m.attachments.filterNot { it.isImage }.joinToString("") { a ->
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
    }
}
