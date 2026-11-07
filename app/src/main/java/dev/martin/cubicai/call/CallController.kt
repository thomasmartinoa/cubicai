package dev.martin.cubicai.call

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import dev.martin.cubicai.data.Persona
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.Settings
import dev.martin.cubicai.net.CallRefused
import dev.martin.cubicai.net.LlmClient
import dev.martin.cubicai.net.VoiceInfo
import dev.martin.cubicai.net.VoiceLink
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
}

/**
 * A live voice call. The PC listens and speaks (VoiceLink); the reply itself is written by the chat (CallHost),
 * which hands its words to [onReplyText] as they stream in. Everything said lands in the chat.
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

    fun start() {
        if (active) return
        val s = host.callSettings()
        if (!s.isPaired) {
            _state.value = CallState(error = "Pair with your PC first: Settings → Local PC.")
            return
        }
        val speaker = s.callSpeaker && !audio.headsetConnected()
        _state.value = CallState(active = true, startedAt = System.currentTimeMillis(), speaker = speaker, status = "Calling your PC…")
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
        audio.onMic = { pcm, n -> if (ready) link.sendAudio(pcm, n) }
        audio.onSentence = { t, text -> scope.launch(Dispatchers.Main) { if (t == turn) setPhase(CallPhase.SPEAKING, them = text) } }
        audio.onTurnPlayed = { t ->
            scope.launch(Dispatchers.Main) {
                if (t == turn && !host.replying && _state.value.phase == CallPhase.SPEAKING) {
                    setPhase(CallPhase.LISTENING)
                    armCheckIn()
                }
            }
        }
        audio.onPlaying = { on -> link.send(JSONObject().put("type", "playing").put("on", on)) }
        audio.start(speaker)
        connect(first = true)
        loadVoices()
    }

    fun end() {
        if (!active) return
        checkInJob?.cancel()
        connectJob?.cancel()
        link.close()
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
                    link.connect(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint, listener)
                    sendConfig("start")
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    // The PC said no (not set up for calls): no point trying again. A network failure is retried.
                    if (e is CallRefused || (first && attempt >= 3) || attempt > 12) {
                        fail(e.message ?: "Couldn't reach your PC.")
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
        _state.update { it.copy(phase = CallPhase.CONNECTING, status = "Lost your PC, reconnecting… ($why)") }
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

    private fun sendConfig(type: String) {
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
        host.updateCallSettings { it.copy(callVoices = it.callVoices + (persona.id to voice)) }
        sendConfig("config")
    }

    fun setSpeed(speed: Float) {
        host.updateCallSettings { it.copy(callSpeed = speed) }
        sendConfig("config")
    }

    fun setPatience(ms: Int) {
        host.updateCallSettings { it.copy(callPatience = ms) }
        sendConfig("config")
    }

    /** The behaviour changed: its voice speaks from now on. */
    fun personaChanged() {
        if (!active) return
        sendConfig("config")
        notifyPhase()
    }

    suspend fun preview(voice: String): ByteArray {
        val s = host.callSettings()
        return link.preview(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint, voice, s.callSpeed)
    }

    suspend fun voices(): List<VoiceInfo> {
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

    private fun title() = host.callPersona().let { if (it.id == "none") "On a call" else "On a call with ${it.name}" }

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
