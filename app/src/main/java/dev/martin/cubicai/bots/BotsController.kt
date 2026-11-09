package dev.martin.cubicai.bots

import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.Settings
import dev.martin.cubicai.net.ApiException
import dev.martin.cubicai.net.Bot
import dev.martin.cubicai.net.BotCall
import dev.martin.cubicai.net.BotEvent
import dev.martin.cubicai.net.BotGoneException
import dev.martin.cubicai.net.BotMessage
import dev.martin.cubicai.net.BotRoutine
import dev.martin.cubicai.net.addRoutine
import dev.martin.cubicai.net.botRoutines
import dev.martin.cubicai.net.deleteRoutine
import dev.martin.cubicai.net.runRoutine
import dev.martin.cubicai.net.updateRoutine
import dev.martin.cubicai.net.LlmClient
import dev.martin.cubicai.net.botChat
import dev.martin.cubicai.net.clearBotChat
import dev.martin.cubicai.net.createBot
import dev.martin.cubicai.net.deleteBot
import dev.martin.cubicai.net.followBot
import dev.martin.cubicai.net.getBot
import dev.martin.cubicai.net.listBots
import dev.martin.cubicai.net.pushKeys
import dev.martin.cubicai.net.sendToBot
import dev.martin.cubicai.net.stopBot
import dev.martin.cubicai.net.updateBot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** The turn a Bot is writing right now (words and thoughts so far, and the tool it's using). */
data class LiveTurn(val text: String = "", val reasoning: String = "", val tool: BotCall? = null)

data class OpenBot(
    val bot: Bot,
    val memory: String = "",
    val messages: List<BotMessage> = emptyList(),
    /** Its routines; ones it proposed wait for approval. */
    val routines: List<BotRoutine> = emptyList(),
    val loading: Boolean = true,
    val running: Boolean = false,
    /** Set while the Bot works: what it's writing or doing now. */
    val live: LiveTurn? = null,
    /** Lost the PC while following (the Bot carries on there); retrying. */
    val reconnecting: Boolean = false,
    val error: String? = null,
)

data class BotsState(
    val bots: List<Bot> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val open: OpenBot? = null,
)

/**
 * The phone's side of the PC's Bots: the list, the open Bot's chat, and following its run live. The Bots
 * themselves live and work on the PC; this only shows them and passes messages on.
 */
class BotsController(
    private val scope: CoroutineScope,
    private val client: LlmClient,
    private val settings: () -> Settings,
) {
    private val _state = MutableStateFlow(BotsState())
    val state: StateFlow<BotsState> = _state.asStateFlow()

    private var follow: Job? = null
    private var listJob: Job? = null
    /** Keys handed to the PC this session (provider → key), so each goes once. */
    private val pushed = mutableMapOf<String, String>()
    private val networkBack = Channel<Unit>(Channel.CONFLATED)

    private class NotPaired : ApiException("Bots live on your PC. Pair with it first: Settings → Local PC.")

    private fun pc(): Triple<List<String>, String, String> {
        val s = settings()
        if (!s.isPaired) throw NotPaired()
        return Triple(s.baseUrls(Providers.LOCAL), s.key(Providers.LOCAL), s.localFingerprint)
    }

    val paired get() = settings().isPaired

    fun refresh() {
        if (listJob?.isActive == true) return
        listJob = scope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val (urls, token, pin) = pc()
                val bots = client.listBots(urls, token, pin)
                _state.update { s ->
                    // The open Bot's profile follows the list (it may have been changed on another phone).
                    val open = s.open?.let { o -> bots.firstOrNull { it.id == o.bot.id }?.let { o.copy(bot = it.copy(running = o.running || it.running)) } ?: o }
                    s.copy(bots = bots, loaded = true, loading = false, error = null, open = open)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your Bots.") }
            }
        }
    }

    /** While the list shows: refreshed now, and every few seconds while a Bot works (to show it finishing). */
    suspend fun watchList() {
        while (true) {
            refresh()
            delay(if (_state.value.bots.any { it.running }) 4_000 else 30_000)
        }
    }

    /** Hands the PC the key a cloud Bot needs, from this phone's Settings (once per session per key). */
    private suspend fun pushKeyFor(provider: String) {
        val s = settings()
        val p = Providers.all.firstOrNull { it.id == provider } ?: return
        if (p == Providers.LOCAL) return
        val key = s.key(p)
        val wanted = if (p == Providers.CUSTOM) key + "|" + s.customBaseUrl.trim() else key
        if (key.isBlank() && p != Providers.CUSTOM) return // the PC may have one already; if not, the Bot says so
        if (pushed[provider] == wanted) return
        val body = JSONObject()
        if (p == Providers.CUSTOM) body.put("custom", key).put("custom_url", s.customBaseUrl.trim().trimEnd('/'))
        else body.put(p.id, key)
        val (urls, token, pin) = pc()
        client.pushKeys(urls, token, pin, body)
        pushed[provider] = wanted
    }

    /** Makes a Bot; [done] gets it, or the reason it couldn't be made. */
    fun create(fields: JSONObject, done: (Result<Bot>) -> Unit) {
        scope.launch {
            val result = runCatching {
                pushKeyFor(fields.optString("provider"))
                val (urls, token, pin) = pc()
                client.createBot(urls, token, pin, fields).first
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { b -> _state.update { it.copy(bots = it.bots + b) } }
            done(result)
        }
    }

    fun update(id: String, fields: JSONObject, done: (Result<Bot>) -> Unit) {
        scope.launch {
            val result = runCatching {
                if (fields.has("provider")) pushKeyFor(fields.optString("provider"))
                val (urls, token, pin) = pc()
                client.updateBot(urls, token, pin, id, fields)
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { (b, memory) ->
                _state.update { s ->
                    s.copy(
                        bots = s.bots.map { if (it.id == id) b.copy(running = it.running, last = it.last, lastFromUser = it.lastFromUser, lastError = it.lastError) else it },
                        open = s.open?.takeIf { it.bot.id == id }?.let { it.copy(bot = b.copy(running = it.running), memory = memory) } ?: s.open,
                    )
                }
            }
            done(result.map { it.first })
        }
    }

    fun delete(id: String, done: (Result<Unit>) -> Unit = {}) {
        scope.launch {
            val result = runCatching {
                val (urls, token, pin) = pc()
                client.deleteBot(urls, token, pin, id)
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess {
                _state.update { s -> s.copy(bots = s.bots.filterNot { it.id == id }, open = s.open?.takeIf { it.bot.id != id }) }
                if (_state.value.open == null) follow?.cancel()
            }
            done(result)
        }
    }

    // --- The open Bot -----------------------------------------------------------------------------

    fun open(id: String) {
        // From a notification the list may not be loaded yet: a stand-in until the PC answers.
        val known = _state.value.bots.firstOrNull { it.id == id } ?: Bot(id, "", "", "#2f6bff", "local", "")
        follow?.cancel()
        _state.update { it.copy(open = OpenBot(known, running = known.running)) }
        load(id)
    }

    fun close() {
        follow?.cancel()
        follow = null
        _state.update { it.copy(open = null) }
        refresh()
    }

    /** (Re)reads the open Bot's chat and, if it's working, follows it from the turn it's on. */
    private fun load(id: String) {
        follow?.cancel()
        follow = scope.launch {
            try {
                val (urls, token, pin) = pc()
                val (bot, memory) = client.getBot(urls, token, pin, id)
                runCatching { pushKeyFor(bot.provider) } // a cloud Bot works with this phone's current key
                val chat = client.botChat(urls, token, pin, id)
                val routines = runCatching { client.botRoutines(urls, token, pin, id) }.getOrNull()
                _state.update { s ->
                    s.copy(open = s.open?.takeIf { it.bot.id == id }?.copy(
                        bot = bot.copy(running = chat.running), memory = memory, messages = chat.messages, routines = routines ?: s.open.routines,
                        loading = false, running = chat.running, live = if (chat.running) LiveTurn() else null, error = null, reconnecting = false,
                    ))
                }
                if (chat.running) followRun(id, chat.follow ?: 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotGoneException) {
                _state.update { s -> s.copy(bots = s.bots.filterNot { it.id == id }, open = s.open?.takeIf { it.bot.id == id }?.copy(loading = false, error = e.message)) }
            } catch (e: Exception) {
                setOpen(id) { it.copy(loading = false, error = e.message ?: "Couldn't open the Bot.") }
            }
        }
    }

    private fun setOpen(id: String, change: (OpenBot) -> OpenBot) =
        _state.update { s -> s.copy(open = s.open?.takeIf { it.bot.id == id }?.let(change) ?: s.open) }

    /** Follows the run from event [from], reconnecting when the connection drops; ends with the run. */
    private suspend fun followRun(id: String, from: Int) {
        var next = from
        var misses = 0
        while (true) {
            try {
                val (urls, token, pin) = pc()
                client.followBot(urls, token, pin, id, next) { ev ->
                    next = ev.seq + 1
                    misses = 0
                    apply(id, ev)
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Not a dropped connection: no run to follow (it ended long ago, or the PC restarted).
                if (e.message?.contains("isn't working") == true) break
                misses++
            } catch (e: Exception) {
                misses++
            }
            if (misses > 8) {
                setOpen(id) { it.copy(reconnecting = false, error = "Lost your PC. The Bot carries on there; reopen it to see how it got on.") }
                return
            }
            setOpen(id) { it.copy(reconnecting = true) }
            // Growing waits, cut short when the phone's network comes back.
            withTimeoutOrNull(minOf(1_000L shl (misses - 1), 15_000L)) { networkBack.receive() }
        }
        // The run's over: the PC's chat is the record (it orders replies and messages sent meanwhile).
        runCatching {
            val (urls, token, pin) = pc()
            val chat = client.botChat(urls, token, pin, id)
            val memory = client.getBot(urls, token, pin, id).second // it may have remembered or forgotten something
            val routines = runCatching { client.botRoutines(urls, token, pin, id) }.getOrNull() // or proposed a routine
            setOpen(id) { it.copy(messages = chat.messages, memory = memory, routines = routines ?: it.routines, running = chat.running, live = null, reconnecting = false, bot = it.bot.copy(running = chat.running)) }
            if (chat.running) followRun(id, chat.follow ?: 0)
        }.onFailure { if (it is CancellationException) throw it }
        refresh()
    }

    private fun apply(id: String, ev: BotEvent) = setOpen(id) { o ->
        val live = o.live ?: LiveTurn()
        when (ev) {
            is BotEvent.Thinking -> o.copy(running = true, reconnecting = false, live = LiveTurn())
            is BotEvent.Text -> o.copy(reconnecting = false, live = live.copy(text = live.text + ev.delta, tool = null))
            is BotEvent.Reasoning -> o.copy(reconnecting = false, live = live.copy(reasoning = live.reasoning + ev.delta))
            is BotEvent.Tool -> o.copy(reconnecting = false, live = live.copy(tool = ev.call))
            is BotEvent.Posted -> {
                val m = ev.message
                val messages = if (o.messages.any { it.id == m.id }) o.messages else (o.messages + m).sortedBy { it.at }
                o.copy(
                    messages = messages,
                    reconnecting = false,
                    // A finished turn replaces the words written live; a tool's result ends that tool.
                    live = when (m.role) {
                        "assistant" -> LiveTurn()
                        "tool" -> live.copy(tool = null)
                        else -> o.live
                    },
                )
            }
            is BotEvent.Done -> o.copy(running = false, live = null, reconnecting = false, bot = o.bot.copy(running = false))
        }
    }

    fun send(text: String) {
        val open = _state.value.open ?: return
        val id = open.bot.id
        val words = text.trim()
        if (words.isEmpty()) return
        scope.launch {
            try {
                runCatching { pushKeyFor(open.bot.provider) }
                val (urls, token, pin) = pc()
                val (message, queued) = client.sendToBot(urls, token, pin, id, words)
                setOpen(id) { o ->
                    o.copy(
                        messages = if (o.messages.any { it.id == message.id }) o.messages else o.messages + message,
                        running = true, error = null, live = o.live ?: LiveTurn(), bot = o.bot.copy(running = true),
                    )
                }
                // A new run is followed from its start; one already under way is followed already (or again).
                if (!queued || follow?.isActive != true) {
                    follow?.cancel()
                    follow = scope.launch {
                        if (queued) {
                            val chat = client.botChat(urls, token, pin, id)
                            followRun(id, chat.follow ?: 0)
                        } else followRun(id, 0)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setOpen(id) { it.copy(error = e.message ?: "Couldn't send that.") }
            }
        }
    }

    fun stop() {
        val id = _state.value.open?.bot?.id ?: return
        scope.launch {
            runCatching {
                val (urls, token, pin) = pc()
                client.stopBot(urls, token, pin, id)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                setOpen(id) { it.copy(error = e.message) }
            }
        }
    }

    fun clearChat(done: (Result<Unit>) -> Unit = {}) {
        val id = _state.value.open?.bot?.id ?: return
        scope.launch {
            val result = runCatching {
                val (urls, token, pin) = pc()
                client.clearBotChat(urls, token, pin, id)
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { setOpen(id) { it.copy(messages = emptyList(), error = null) } }
            done(result)
        }
    }

    /** The Bot's memory as the PC has it now (the Bot changes it while it works). */
    fun memoryOf(id: String, done: (Result<String>) -> Unit) {
        scope.launch {
            val result = runCatching {
                val (urls, token, pin) = pc()
                client.getBot(urls, token, pin, id).second
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { m -> setOpen(id) { it.copy(memory = m) } }
            done(result)
        }
    }

    // --- Routines ---------------------------------------------------------------------------------

    /** A routine call on the open Bot; the PC answers with all of its routines. */
    private fun routines(done: (Result<Unit>) -> Unit, call: suspend (List<String>, String, String, String) -> List<BotRoutine>) {
        val id = _state.value.open?.bot?.id ?: return
        scope.launch {
            val result = runCatching {
                val (urls, token, pin) = pc()
                call(urls, token, pin, id)
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { list -> setOpen(id) { it.copy(routines = list) } }
            done(result.map { })
            refresh() // the list shows routine counts
        }
    }

    fun addRoutine(fields: JSONObject, done: (Result<Unit>) -> Unit) = routines(done) { u, t, p, id -> client.addRoutine(u, t, p, id, fields) }

    fun updateRoutine(rid: String, fields: JSONObject, done: (Result<Unit>) -> Unit = ::reportError) =
        routines(done) { u, t, p, id -> client.updateRoutine(u, t, p, id, rid, fields) }

    fun approveRoutine(rid: String) = updateRoutine(rid, JSONObject().put("approved", true))

    /** Deletes a routine, or declines one the Bot proposed. */
    fun deleteRoutine(rid: String, done: (Result<Unit>) -> Unit = ::reportError) = routines(done) { u, t, p, id -> client.deleteRoutine(u, t, p, id, rid) }

    private fun reportError(r: Result<Unit>) {
        r.onFailure { e -> _state.update { s -> s.copy(open = s.open?.copy(error = e.message ?: "Couldn't change the routine.")) } }
    }

    /** Runs a routine now and follows it like a message. */
    fun runRoutine(rid: String) {
        val open = _state.value.open ?: return
        val id = open.bot.id
        scope.launch {
            try {
                runCatching { pushKeyFor(open.bot.provider) }
                val (urls, token, pin) = pc()
                client.runRoutine(urls, token, pin, id, rid)
                setOpen(id) { it.copy(running = true, error = null, live = it.live ?: LiveTurn(), bot = it.bot.copy(running = true)) }
                if (follow?.isActive != true) load(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setOpen(id) { it.copy(error = e.message ?: "Couldn't start the routine.") }
            }
        }
    }

    /**
     * While a Bot's chat shows: notices a run it didn't start (a routine, or a message from another
     * phone) and follows it.
     */
    suspend fun watchOpen() {
        while (true) {
            delay(15_000)
            val open = _state.value.open ?: continue
            if (open.running || open.loading || follow?.isActive == true) continue
            runCatching {
                val (urls, token, pin) = pc()
                val chat = client.botChat(urls, token, pin, open.bot.id)
                if (chat.running || chat.messages.size != open.messages.size) load(open.bot.id)
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** Whether any Bot is working, as far as this phone knows. */
    val anyWorking get() = _state.value.let { s -> s.open?.running == true || s.bots.any { it.running } }

    fun dismissError() = _state.update { s -> s.copy(open = s.open?.copy(error = null)) }

    /** The app is on screen again: catch up with the open Bot (its run may have ended meanwhile). */
    fun resume() {
        val open = _state.value.open ?: return
        if (follow?.isActive != true) load(open.bot.id)
    }

    fun networkBack() {
        networkBack.trySend(Unit)
    }
}
