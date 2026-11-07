package dev.martin.cubicai.call

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import dev.martin.cubicai.data.CALL_AUTO
import dev.martin.cubicai.data.CALL_GROK
import dev.martin.cubicai.data.CALL_PC
import dev.martin.cubicai.data.Persona
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.Settings
import dev.martin.cubicai.net.CallRefused
import dev.martin.cubicai.net.GrokVoice
import dev.martin.cubicai.net.LlmClient
import dev.martin.cubicai.net.VoiceInfo
import dev.martin.cubicai.net.VoiceLink
import dev.martin.cubicai.net.grokSession
import dev.martin.cubicai.net.grokTurnUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class CallPhase(val label: String) {
    CONNECTING("Connecting"),
    LISTENING("Listening"),
    HEARING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
}

data class CallState(
    val active: Boolean = false,
    val phase: CallPhase = CallPhase.CONNECTING,
    /** What's going on when it isn't the usual (waking the voice up, reconnecting). */
    val status: String? = null,
    /** What the reply is busy with before it speaks ("Searching the web"), shown instead of Thinking. */
    val working: String? = null,
    /** Why the call ended by itself; shown until dismissed. */
    val error: String? = null,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val camera: Boolean = false,
    val backCamera: Boolean = true,
    val screen: Boolean = false,
    /** Something is being made on the PC (a picture asked for in the call). */
    val making: Boolean = false,
    val startedAt: Long = 0,
    /** Captions: what the user is saying or said last, and what the voice is saying. */
    val you: String = "",
    val them: String = "",
    val voices: List<VoiceInfo> = emptyList(),
    /** The chat is shown, with the call in a bar on top. */
    val minimized: Boolean = false,
    /** Grok's realtime voice is on the other end (listening, answering and speaking itself), not the PC. */
    val grok: Boolean = false,
)

/** What a call needs from the chat. */
interface CallHost {
    fun callSettings(): Settings
    fun updateCallSettings(transform: (Settings) -> Settings)
    fun callPersona(): Persona

    /**
     * The user said [text], with what the camera or screen shows ([frame]). When they talked over reply [cutTurn],
     * it stops and keeps only the words they heard ([heard]).
     */
    fun heard(text: String, frame: Bitmap?, cutTurn: Int?, heard: String)

    /** Nothing was said for [quietSeconds]: the model may pick the conversation up. */
    fun checkIn(quietSeconds: Int, frame: Bitmap?)

    /** A reply is being written. */
    val replying: Boolean

    /** The PC can be reached on this network now (home Wi-Fi, or Tailscale with remote access). */
    fun pcReachable(): Boolean

    // A Grok call: Grok answers by itself; the chat only keeps what was said.

    /** What Grok is told: the behaviour, how to talk on a call, and the chat so far. */
    fun grokInstructions(): String

    /** The user said [text] (Grok's transcript of it). */
    fun grokHeard(text: String)

    /** Grok starts a reply; its words follow in [grokReplyText]. */
    fun grokReplyStart()
    fun grokReplyText(delta: String)

    /** The reply is over; [cut] when the user talked over it. */
    fun grokReplyEnd(cut: Boolean)
}

/**
 * A live voice call. The PC listens and speaks (VoiceLink); the reply itself is written by the chat (CallHost),
 * which hands its words to [onReplyText] as they stream in. Or Grok's realtime voice does all three (GrokVoice),
 * away from home too. Everything said lands in the chat.
 */
class CallController(
    private val app: Application,
    client: LlmClient,
    private val scope: CoroutineScope,
    private val host: CallHost,
) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()
    val active get() = _state.value.active

    private val audio = CallAudio(app)
    private val link = VoiceLink(client)
    private val grok = GrokVoice(client)
    private var grokVoicesCache: List<VoiceInfo> = emptyList()
    /** Grok's reply being said: its turn (for the audio queue), and whether it's still coming in. */
    @Volatile private var grokTurn = 0
    @Volatile private var grokResponding = false
    /** The reply was talked over (its rest is dropped). */
    private var grokCut = false
    private var grokTurnSent = false
    val micLevel get() = audio.micLevel
    val voiceLevel get() = audio.voiceLevel

    /** The latest reply's turn (each reply, check-in and "one moment" is one). */
    @Volatile private var turn = 0
    private var checkIns = 0
    private var checkInJob: Job? = null
    private var connectJob: Job? = null
    @Volatile private var ready = false
    /** A reply was being written or said when the user started talking. */
    private var busyWhenHeard = false

    private val listener = object : VoiceLink.Listener {
        // Sentences and their audio stay in order on the socket's thread; everything else goes to the main thread.
        override fun onEvent(event: JSONObject) {
            when (event.optString("type")) {
                "seg" -> audio.sentence(event.optInt("turn"), event.optString("text"))
                "turn_end" -> audio.turnEnd(event.optInt("turn"))
                else -> scope.launch(Dispatchers.Main) { handle(event) }
            }
        }

        override fun onAudio(pcm: ByteArray) = audio.pcm(pcm)

        override fun onClosed(error: String?) {
            scope.launch(Dispatchers.Main) { if (active && error != null) reconnect(error) }
        }
    }

    private val grokListener = object : GrokVoice.Listener {
        // The audio queue is fed on the socket's thread, in order; the rest goes to the main thread.
        override fun onEvent(event: JSONObject) {
            when (event.optString("type")) {
                "response.created" -> {
                    grokTurn = ++turn
                    grokResponding = true
                    audio.sentence(grokTurn, "")
                }
                "response.output_audio.delta", "response.audio.delta" -> {
                    val pcm = runCatching { Base64.decode(event.optString("delta"), Base64.DEFAULT) }.getOrNull()
                    if (pcm != null && pcm.isNotEmpty()) audio.pcm(pcm)
                    return
                }
                "response.done" -> {
                    grokResponding = false
                    audio.turnEnd(grokTurn)
                }
            }
            scope.launch(Dispatchers.Main) { handleGrok(event) }
        }

        override fun onClosed(error: String?) {
            scope.launch(Dispatchers.Main) { if (active && error != null) reconnect(error) }
        }
    }

    private val isGrok get() = _state.value.grok

    /** Whether a call started now goes to Grok: by the setting, and for Auto, whether the PC can be reached. */
    fun wouldUseGrok(s: Settings = host.callSettings()): Boolean = when (s.callEngine) {
        CALL_GROK -> true
        CALL_PC -> false
        else -> s.key(Providers.XAI).isNotBlank() && (!s.isPaired || !host.pcReachable())
    }
    fun start() {
        if (active) return
        val s = host.callSettings()
        val useGrok = wouldUseGrok(s)
        if (useGrok && s.key(Providers.XAI).isBlank()) {
            _state.value = CallState(error = "Add your xAI API key in Settings → Grok to call Grok.")
            return
        }
        if (!useGrok && !s.isPaired) {
            _state.value = CallState(error = "Pair with your PC first: Settings → Local PC.")
            return
        }
        val speaker = s.callSpeaker && !audio.headsetConnected()
        _state.value = CallState(
            active = true, startedAt = System.currentTimeMillis(), speaker = speaker, grok = useGrok,
            status = if (useGrok) "Calling Grok…" else "Calling your PC…",
        )
        ready = false
        checkIns = 0
        CallService.onAction = { action ->
            scope.launch(Dispatchers.Main) {
                when (action) {
                    CallService.ACTION_MUTE -> toggleMute()
                    CallService.ACTION_END -> end()
                }
            }
        }
        CallService.onScreenEnded = { scope.launch(Dispatchers.Main) { _state.update { it.copy(screen = false) } } }
        CallService.start(app, title(), CallPhase.CONNECTING.label)
        audio.onMic = { pcm, n -> if (ready) if (isGrok) grok.sendAudio(pcm, n) else link.sendAudio(pcm, n) }
        audio.onSentence = { t, text -> scope.launch(Dispatchers.Main) { if (t == turn) setPhase(CallPhase.SPEAKING, them = text) } }
        audio.onTurnPlayed = { t ->
            scope.launch(Dispatchers.Main) {
                if (t == turn && !host.replying && _state.value.phase == CallPhase.SPEAKING) {
                    setPhase(CallPhase.LISTENING)
                    armCheckIn()
                }
            }
        }
        audio.onPlaying = { on -> if (!isGrok) link.send(JSONObject().put("type", "playing").put("on", on)) }
        audio.start(speaker)
        connect(first = true)
        if (!useGrok) loadVoices()
    }

    fun end() {
        if (!active) return
        checkInJob?.cancel()
        connectJob?.cancel()
        link.close()
        grok.close()
        grokResponding = false
        audio.stop()
        CallService.stopSharing(app)
        CallService.stop(app)
        CallService.onAction = null
        CallService.onScreenEnded = null
        CameraFrames.grab = null
        _state.value = CallState()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun connect(first: Boolean) {
        connectJob?.cancel()
        connectJob = scope.launch {
            var attempt = 0
            while (active) {
                try {
                    val s = host.callSettings()
                    if (isGrok) {
                        grokTurnSent = false
                        grok.connect(s.key(Providers.XAI), grokListener)
                        grok.send(grokConfig())
                    } else {
                        link.connect(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint, listener)
                        sendConfig("start")
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Auto: the PC isn't answering, so Grok takes the call instead.
                    val s = host.callSettings()
                    if (!isGrok && first && s.callEngine == CALL_AUTO && s.key(Providers.XAI).isNotBlank()) {
                        _state.update { it.copy(grok = true, status = "Your PC didn't answer. Calling Grok…") }
                        continue
                    }
                    attempt++
                    // The PC said no (not set up for calls): no point trying again. A network failure is retried.
                    if (e is CallRefused || (first && attempt >= 3) || attempt > 12) {
                        fail(e.message ?: if (isGrok) "Couldn't reach Grok." else "Couldn't reach your PC.")
                        return@launch
                    }
                    _state.update { it.copy(status = "${e.message} Trying again…", phase = CallPhase.CONNECTING) }
                    delay(minOf(1000L * attempt, 5000L))
                }
            }
        }
    }

    private fun reconnect(why: String) {
        ready = false
        if (isGrok) {
            grokResponding = false
            audio.flush(turn, turn)
        }
        _state.update { it.copy(phase = CallPhase.CONNECTING, status = if (it.grok) "Lost Grok, reconnecting… ($why)" else "Lost your PC, reconnecting… ($why)") }
        connect(first = false)
    }

    /** The phone's network came back: a call waiting to reconnect tries again straight away. */
    fun networkBack() {
        if (active && !ready && connectJob?.isActive == true) connect(first = false)
    }

    private fun fail(message: String) {
        end()
        _state.value = CallState(error = message)
    }

    private fun loadVoices() = scope.launch {
        val s = host.callSettings()
        runCatching { link.voices(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint) }
            .onSuccess { (voices, _) -> _state.update { it.copy(voices = voices) } }
    }

    /** Grok's session: who it is (and the chat so far), its voice, and when it speaks up by itself. */
    private fun grokConfig(): JSONObject {
        val s = host.callSettings()
        return grokSession(host.grokInstructions(), s.grokVoice(host.callPersona()), s.callSpeed, s.webSearch, grokCheckIn(s))
    }

    /** Check-ins: Grok speaks up after this long a silence (it re-arms after each reply by itself). */
    private fun grokCheckIn(s: Settings): Int? = if (s.callCheckIns && !_state.value.muted) s.callCheckInSeconds * 1000 else null

    private fun handleGrok(event: JSONObject) {
        if (!active || !isGrok) return
        when (val type = event.optString("type")) {
            "session.updated" -> if (!ready) {
                ready = true
                _state.update { it.copy(status = null) }
                if (_state.value.phase == CallPhase.CONNECTING) setPhase(CallPhase.LISTENING)
                if (!grokTurnSent) {
                    grokTurnSent = true
                    grok.send(grokTurnUpdate(host.callSettings().callPatience, grokCheckIn(host.callSettings())))
                }
            }
            "error" -> {
                val e = event.optJSONObject("error")
                val message = e?.optString("message").orEmpty().ifBlank { event.optString("message") }
                // The pause length is OpenAI's field name; if xAI doesn't take it, its own default stays.
                if ("silence_duration" in message || "no active response" in message.lowercase() || "cancel" in message.lowercase()) return
                android.util.Log.w("CubicAI", "Grok call: ${e?.optString("code")} $message")
                if (!ready) fail("Grok couldn't start the call: $message")
                else showStatus("Grok: $message")
            }
            "input_audio_buffer.speech_started" -> {
                if (_state.value.phase != CallPhase.HEARING && (grokResponding || audio.busy)) {
                    // Talked over: the rest of the reply isn't said.
                    grokCut = true
                    audio.flush(grokTurn, grokTurn)
                    if (grokResponding) grok.send(JSONObject().put("type", "response.cancel"))
                    if (!grokResponding) host.grokReplyEnd(cut = true)
                }
                setPhase(CallPhase.HEARING, you = "")
            }
            "input_audio_buffer.speech_stopped" -> if (_state.value.phase == CallPhase.HEARING) {
                setPhase(CallPhase.THINKING)
                // A noise xAI first took for speech gets no reply: back to listening when none comes.
                scope.launch {
                    delay(6000)
                    if (active && isGrok && !grokResponding && !audio.busy && _state.value.phase == CallPhase.THINKING) setPhase(CallPhase.LISTENING)
                }
            }
            "conversation.item.input_audio_transcription.updated", "conversation.item.input_audio_transcription.delta" -> {
                val text = event.optString("transcript").ifBlank { event.optString("delta") }
                if (text.isNotBlank() && _state.value.phase == CallPhase.HEARING) _state.update { it.copy(you = text) }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val text = event.optString("transcript").trim()
                if (text.isNotEmpty()) {
                    _state.update { it.copy(you = text) }
                    host.grokHeard(text)
                }
            }
            "response.created" -> {
                grokCut = false
                host.grokReplyStart()
                _state.update { it.copy(them = "", working = null) }
                if (_state.value.phase != CallPhase.HEARING) setPhase(CallPhase.THINKING)
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta", "response.output_text.delta", "response.text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isEmpty() || grokCut) return
                host.grokReplyText(delta)
                _state.update { it.copy(them = it.them + delta, working = null) }
                if (_state.value.phase == CallPhase.THINKING) setPhase(CallPhase.SPEAKING)
            }
            "response.done" -> {
                val status = event.optJSONObject("response")?.optString("status").orEmpty()
                host.grokReplyEnd(cut = grokCut || status == "cancelled")
                grokCut = false
                _state.update { it.copy(working = null) }
                // Nothing was said (or it was all cut): back to listening when it's quiet.
                scope.launch {
                    delay(1200)
                    if (active && !grokResponding && !audio.busy && _state.value.phase in listOf(CallPhase.THINKING, CallPhase.SPEAKING)) setPhase(CallPhase.LISTENING)
                }
            }
            else -> if ("search" in type && ("progress" in type || "searching" in type || "added" in type)) {
                _state.update { it.copy(working = if ("x_search" in type) "Searching X" else "Searching the web") }
            } else if (type == "response.output_item.added") {
                val item = event.optJSONObject("item")?.optString("type").orEmpty()
                if ("search" in item) _state.update { it.copy(working = if ("x_search" in item) "Searching X" else "Searching the web") }
            }
        }
    }

    private fun showStatus(text: String) {
        _state.update { it.copy(status = text) }
        scope.launch {
            delay(5000)
            if (_state.value.status == text) _state.update { it.copy(status = null) }
        }
    }

    private fun sendConfig(type: String) {
        if (isGrok) {
            if (type == "config") grok.send(grokConfig())
            return
        }
        val s = host.callSettings()
        link.send(
            JSONObject().put("type", type)
                .put("voice", s.callVoice(host.callPersona()))
                .put("speed", s.callSpeed.toDouble())
                .put("patience", s.callPatience),
        )
    }

    private fun handle(event: JSONObject) {
        if (!active) return
        when (event.optString("type")) {
            "status" -> _state.update { it.copy(status = event.optString("text")) }
            "ready" -> {
                ready = true
                _state.update { it.copy(status = null) }
                if (_state.value.phase == CallPhase.CONNECTING) setPhase(if (host.replying) CallPhase.THINKING else CallPhase.LISTENING)
                armCheckIn()
            }
            "speech" -> if (event.optBoolean("on")) {
                checkInJob?.cancel()
                if (_state.value.phase != CallPhase.HEARING) busyWhenHeard = host.replying || audio.busy
                // Maybe the user is talking over the voice: hold it until we know it's words, not a cough.
                audio.pause()
                setPhase(CallPhase.HEARING, you = "")
            }
            "partial" -> if (_state.value.phase == CallPhase.HEARING) _state.update { it.copy(you = event.optString("text")) }
            "final" -> heard(event.optString("text").trim())
        }
    }

    private fun heard(text: String) {
        // The voice itself, leaking past the phone's echo canceller: not the user talking.
        val echo = busyWhenHeard && overlap(text, audio.heardOf(turn)) >= 0.6f
        if (!text.any { it.isLetterOrDigit() } || FILLER.matches(text) || echo) {
            // Not words after all: let the voice carry on.
            audio.resume()
            val back = when {
                audio.heardOf(turn).isNotEmpty() && host.replying -> CallPhase.SPEAKING
                host.replying -> CallPhase.THINKING
                else -> CallPhase.LISTENING
            }
            setPhase(back)
            if (back == CallPhase.LISTENING) armCheckIn()
            return
        }
        checkIns = 0
        // Talked over the reply (still being written, or still being said): it stops where they cut in.
        val cut = if (busyWhenHeard || host.replying) turn else null
        busyWhenHeard = false
        val heardWords = if (cut != null) {
            link.send(JSONObject().put("type", "cancel"))
            audio.flush(turn, turn)
        } else ""
        _state.update { it.copy(you = text, them = "") }
        setPhase(CallPhase.THINKING)
        host.heard(text, frame(), cut, heardWords)
    }

    /** How many of [said]'s words are in [spoken] (0..1). */
    private fun overlap(said: String, spoken: String): Float {
        fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        val heardWords = words(said)
        if (heardWords.size < 2) return 0f
        val voice = words(spoken).toSet()
        return heardWords.count { it in voice }.toFloat() / heardWords.size
    }

    /** What the camera or the screen shows now, when one is on. */
    private fun frame(): Bitmap? = when {
        _state.value.screen -> CallService.screen?.frame()
        _state.value.camera -> runCatching { CameraFrames.grab?.invoke() }.getOrNull()
        else -> null
    }

    private fun armCheckIn() {
        checkInJob?.cancel()
        if (isGrok) return // Grok speaks up by itself (idle_timeout_ms)
        val s = host.callSettings()
        if (!s.callCheckIns || checkIns >= MAX_CHECK_INS || _state.value.muted) return
        val seconds = s.callCheckInSeconds
        checkInJob = scope.launch {
            delay(seconds * 1000L)
            if (active && ready && _state.value.phase == CallPhase.LISTENING && !host.replying && !_state.value.muted) {
                checkIns++
                setPhase(CallPhase.THINKING)
                host.checkIn(seconds, frame())
            }
        }
    }

    // --- The reply, from the chat ---

    /** A reply starts: its words go to the voice under the returned turn. */
    fun beginTurn(): Int {
        checkInJob?.cancel()
        val t = ++turn
        _state.update { it.copy(working = null) }
        if (_state.value.phase != CallPhase.HEARING) setPhase(CallPhase.THINKING)
        return t
    }

    fun onReplyText(t: Int, text: String) {
        if (!active || t != turn) return
        if (_state.value.working != null) _state.update { it.copy(working = null) }
        link.send(JSONObject().put("type", "say").put("turn", t).put("text", text))
    }

    /** The reply's progress before its words (a web search, loading the model on the PC). */
    fun replyStatus(text: String?) {
        if (!active) return
        // Long model names don't fit under the cube.
        val short = text?.trimEnd('.', '…')?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("Loading ")) "Loading the model on your PC" else it }
        _state.update { it.copy(working = short) }
    }

    /** The reply is written (or failed: [error]). The voice finishes saying it; then it's the user's turn again. */
    fun endTurn(t: Int, error: String? = null) {
        if (!active) return
        if (error != null && t == turn) say("Sorry, that didn't work. $error")
        link.send(JSONObject().put("type", "say_end").put("turn", t))
        // Nothing to say (only a picture was asked for, or nothing at all): back to listening when it's quiet.
        if (t == turn && audio.heardOf(t).isEmpty() && _state.value.phase == CallPhase.THINKING) {
            scope.launch {
                delay(1500)
                if (active && t == turn && _state.value.phase == CallPhase.THINKING && !host.replying) {
                    setPhase(CallPhase.LISTENING)
                    armCheckIn()
                }
            }
        }
    }

    /** Says [text] as a turn of its own (not from the model, not kept in the chat). */
    fun say(text: String) {
        if (!active) return
        val t = ++turn
        link.send(JSONObject().put("type", "say").put("turn", t).put("text", text))
        link.send(JSONObject().put("type", "say_end").put("turn", t))
    }

    fun setMaking(making: Boolean) {
        if (_state.value.making != making) _state.update { it.copy(making = making) }
    }

    // --- Controls ---

    fun toggleMute() {
        val muted = !_state.value.muted
        audio.muted = muted
        _state.update { it.copy(muted = muted) }
        if (isGrok && ready) grok.send(grokTurnUpdate(host.callSettings().callPatience, grokCheckIn(host.callSettings())))
        if (muted) checkInJob?.cancel() else if (_state.value.phase == CallPhase.LISTENING) armCheckIn()
        notifyPhase()
    }

    fun toggleSpeaker() {
        val on = !_state.value.speaker
        audio.setSpeaker(on)
        _state.update { it.copy(speaker = on) }
        host.updateCallSettings { it.copy(callSpeaker = on) }
    }

    fun setCamera(on: Boolean) {
        if (on) CallService.stopSharing(app)
        _state.update { it.copy(camera = on, screen = if (on) false else it.screen) }
    }

    fun flipCamera() = _state.update { it.copy(backCamera = !it.backCamera) }

    /** The user allowed the screen to be captured ([code] and [data] from the system's dialog). */
    fun shareScreen(code: Int, data: Intent) {
        CallService.shareScreen(app, code, data)
        _state.update { it.copy(screen = true, camera = false) }
    }

    fun stopSharing() {
        CallService.stopSharing(app)
        _state.update { it.copy(screen = false) }
    }

    fun setMinimized(minimized: Boolean) = _state.update { it.copy(minimized = minimized) }

    fun setVoice(voice: String) {
        val persona = host.callPersona()
        val key = if (voicesAreGrok()) "grok:" + persona.id else persona.id
        host.updateCallSettings { it.copy(callVoices = it.callVoices + (key to voice)) }
        sendConfig("config")
    }

    fun setSpeed(speed: Float) {
        host.updateCallSettings { it.copy(callSpeed = speed) }
        sendConfig("config")
    }

    fun setPatience(ms: Int) {
        host.updateCallSettings { it.copy(callPatience = ms) }
        if (isGrok && active) grok.send(grokTurnUpdate(ms, grokCheckIn(host.callSettings()))) else sendConfig("config")
    }

    /** The behaviour changed: its voice speaks from now on. */
    fun personaChanged() {
        if (!active) return
        sendConfig("config")
        notifyPhase()
    }

    /** The voice sheet lists Grok's voices: in a Grok call, or (outside one) when the next call would be Grok's. */
    fun voicesAreGrok(): Boolean = if (active) isGrok else wouldUseGrok()

    /** The chosen voice for the open behaviour, among the voices the sheet lists. */
    fun chosenVoice(): String {
        val s = host.callSettings()
        return if (voicesAreGrok()) s.grokVoice(host.callPersona()) else s.callVoice(host.callPersona())
    }

    suspend fun preview(voice: String): ByteArray {
        val s = host.callSettings()
        if (voicesAreGrok()) return grok.preview(s.key(Providers.XAI), voice, s.callSpeed)
        return link.preview(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint, voice, s.callSpeed)
    }

    suspend fun voices(): List<VoiceInfo> {
        if (voicesAreGrok()) {
            grokVoicesCache.takeIf { it.isNotEmpty() }?.let { return it }
            return grok.voices(host.callSettings().key(Providers.XAI)).also { grokVoicesCache = it }
        }
        _state.value.voices.takeIf { it.isNotEmpty() }?.let { return it }
        val s = host.callSettings()
        val (voices, missing) = link.voices(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint)
        if (missing != null) throw IllegalStateException(missing)
        _state.update { it.copy(voices = voices) }
        return voices
    }

    private fun setPhase(phase: CallPhase, you: String? = null, them: String? = null) {
        val before = _state.value.phase
        _state.update { it.copy(phase = phase, you = you ?: it.you, them = them ?: it.them) }
        if (phase.label != before.label) notifyPhase()
    }

    private fun title() = host.callPersona().let {
        val who = if (it.id == "none") "On a call" else "On a call with ${it.name}"
        if (_state.value.grok) "$who (Grok)" else who
    }

    private fun notifyPhase() {
        if (!active) return
        val s = _state.value
        CallService.update(app, title(), s.status ?: s.phase.label, s.muted)
    }

    companion object {
        /** Check-ins in a row without the user saying anything, before the voice waits for them. */
        const val MAX_CHECK_INS = 2
        private val FILLER = Regex("(?i)^(uh+|um+|hm+|mm+|ah+|oh)[.!?]*$")
    }
}
