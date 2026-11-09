package dev.martin.cubicai.bots

import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.Settings
import dev.martin.cubicai.net.ApiException
import dev.martin.cubicai.net.Bot
import dev.martin.cubicai.net.BotCall
import dev.martin.cubicai.net.BotEvent
import dev.martin.cubicai.net.BotGoneException
import dev.martin.cubicai.net.BotMessage
import dev.martin.cubicai.net.BotApproval
import dev.martin.cubicai.net.BotRoutine
import dev.martin.cubicai.net.answerBot
import dev.martin.cubicai.net.answerPhone
import dev.martin.cubicai.net.GoogleStatus
import dev.martin.cubicai.net.McpServer
import dev.martin.cubicai.net.McpTool
import dev.martin.cubicai.net.addMcp
import dev.martin.cubicai.net.deleteMcp
import dev.martin.cubicai.net.googleClient
import dev.martin.cubicai.net.googleFinish
import dev.martin.cubicai.net.googleSignOut
import dev.martin.cubicai.net.googleStart
import dev.martin.cubicai.net.googleStatus
import dev.martin.cubicai.net.mcpServers
import dev.martin.cubicai.net.mcpTools
import dev.martin.cubicai.net.updateMcp
import dev.martin.cubicai.net.botShot
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
import kotlinx.coroutines.ensureActive
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
    /** An action it waits on the user's yes for. */
    val approval: BotApproval? = null,
    /** A location question waiting for Android's permission (its request id): the screen asks. */
    val locationAsk: String? = null,
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
    /** The PC's Google connection and apps (MCP servers), once loaded. */
    val google: GoogleStatus? = null,
    val apps: List<McpServer>? = null,
    val connectionsError: String? = null,
    /** A Google sign-in is waiting for the browser to come back. */
    val signingIn: Boolean = false,
)

/**
 * The phone's side of the PC's Bots: the list, the open Bot's chat, and following its run live. The Bots
 * themselves live and work on the PC; this only shows them and passes messages on.
 */
class BotsController(
    private val scope: CoroutineScope,
    private val client: LlmClient,
    private val context: android.content.Context,
    private val settings: () -> Settings,
) {
    /** Questions to the phone already answered (one comes by the run and by /messages). */
    private val asked = mutableSetOf<String>()
    private val waitingLocation = mutableMapOf<String, JSONObject>()
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
                        bot = bot.copy(running = chat.running), memory = memory, messages = chat.messages, routines = routines ?: s.open.routines, approval = chat.approval,
                        loading = false, running = chat.running, live = if (chat.running) LiveTurn() else null, error = null, reconnecting = false,
                    ))
                }
                chat.phone?.let { phone(bot, it) }
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
                    if (ev is BotEvent.Phone) _state.value.open?.bot?.takeIf { it.id == id }?.let { phone(it, ev.phone) }
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
            is BotEvent.Done -> o.copy(running = false, live = null, reconnecting = false, approval = null, bot = o.bot.copy(running = false))
            is BotEvent.Approval -> o.copy(reconnecting = false, approval = BotApproval(ev.id, ev.text))
            is BotEvent.Approved -> o.copy(approval = o.approval?.takeIf { it.id != ev.id })
            is BotEvent.Phone -> o.copy(reconnecting = false)
            is BotEvent.PhoneDone -> o.copy(locationAsk = o.locationAsk?.takeIf { it != ev.id })
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

    /** Something from [bot] for this phone: carried out, or (a question) answered. */
    private fun phone(bot: Bot, p: JSONObject) {
        if (!PhoneTools.isQuestion(p)) {
            PhoneTools.deliver(context, bot.id, bot.name, bot.color, p, BotFeed.appVisible)
            return
        }
        val rid = p.optString("id")
        if (!asked.add(rid)) return
        // Location needs Android's permission: the screen asks for it first.
        if (p.optString("do") == "location" && !PhoneTools.hasLocation(context) && BotFeed.appVisible) {
            waitingLocation[rid] = p
            setOpen(bot.id) { it.copy(locationAsk = rid) }
            return
        }
        answerPhone(bot.id, p)
    }

    private fun answerPhone(botId: String, p: JSONObject) {
        scope.launch {
            runCatching {
                val answer = PhoneTools.answer(context, p)
                val (urls, token, pin) = pc()
                client.answerPhone(urls, token, pin, botId, p.optString("id"), answer)
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** The screen asked Android for location (granted or not): answer the question that waited. */
    fun locationAnswered() {
        val open = _state.value.open ?: return
        val rid = open.locationAsk ?: return
        setOpen(open.bot.id) { it.copy(locationAsk = null) }
        waitingLocation.remove(rid)?.let { answerPhone(open.bot.id, it) }
    }

    // --- Accounts and apps (Google, MCP) ------------------------------------------------------------

    fun loadConnections() {
        scope.launch {
            try {
                val (urls, token, pin) = pc()
                val google = runCatching { client.googleStatus(urls, token, pin) }
                val apps = client.mcpServers(urls, token, pin)
                _state.update { it.copy(google = google.getOrNull(), apps = apps, connectionsError = google.exceptionOrNull()?.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(connectionsError = e.message ?: "Couldn't reach your PC.") }
            }
        }
    }

    private fun <T> connections(work: suspend (List<String>, String, String) -> T, done: (Result<T>) -> Unit = {}) {
        scope.launch {
            val r = runCatching { val (urls, token, pin) = pc(); work(urls, token, pin) }.onFailure { if (it is CancellationException) throw it }
            done(r)
        }
    }

    fun setGoogleClient(id: String, secret: String, done: (Result<GoogleStatus>) -> Unit) =
        connections({ u, t, p -> client.googleClient(u, t, p, id.trim(), secret.trim()) }) { r -> r.onSuccess { g -> _state.update { it.copy(google = g) } }; done(r) }

    fun signOutGoogle() = connections({ u, t, p -> client.googleSignOut(u, t, p) }) { r -> r.onSuccess { g -> _state.update { it.copy(google = g) } } }

    private var signIn: Job? = null

    /**
     * Signs in to Google on this phone's browser: Google sends it back to a loopback address this app
     * listens on, and the code goes to the PC, which keeps the tokens. [openUrl] opens the browser.
     */
    fun signInGoogle(openUrl: (String) -> Unit, done: (Result<GoogleStatus>) -> Unit) {
        signIn?.cancel()
        _state.update { it.copy(signingIn = true) }
        signIn = scope.launch {
            val r = runCatching {
                val (urls, token, pin) = pc()
                val (code, state) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                        val url = client.googleStart(urls, token, pin, "http://127.0.0.1:${server.localPort}")
                        val expected = android.net.Uri.parse(url).getQueryParameter("state")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { openUrl(url) }
                        server.soTimeout = 1_000
                        val end = System.currentTimeMillis() + 15 * 60_000
                        var got: Pair<String, String>? = null
                        while (got == null) {
                            ensureActive()
                            if (System.currentTimeMillis() > end) throw ApiException("The sign-in took too long; try again.")
                            val sock = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                            sock.use {
                                val line = it.getInputStream().bufferedReader().readLine().orEmpty()
                                val q = android.net.Uri.parse("http://x" + line.split(" ").getOrElse(1) { "/" })
                                val (msg, result) = when {
                                    q.getQueryParameter("state") != expected -> "Waiting for Google…" to null
                                    q.getQueryParameter("error") != null -> "Sign-in was cancelled. Go back to CubicAI." to Result.failure<Pair<String, String>>(ApiException("Google sign-in was cancelled (${q.getQueryParameter("error")})."))
                                    q.getQueryParameter("code") != null -> "Signed in. Go back to CubicAI." to Result.success(q.getQueryParameter("code")!! to expected!!)
                                    else -> "Waiting for Google…" to null
                                }
                                val page = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>CubicAI</title><body style='font-family:sans-serif;padding:2em'><h2>$msg</h2>"
                                val bytes = page.toByteArray()
                                it.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(bytes)
                                    flush()
                                }
                                if (result != null) got = result.getOrThrow()
                            }
                        }
                        got!!
                    }
                }
                client.googleFinish(urls, token, pin, code, state)
            }.onFailure { if (it is CancellationException) { _state.update { s -> s.copy(signingIn = false) }; throw it } }
            _state.update { s -> s.copy(signingIn = false, google = r.getOrNull() ?: s.google) }
            done(r)
        }
    }

    fun cancelSignIn() {
        signIn?.cancel()
        _state.update { it.copy(signingIn = false) }
    }

    /** Adds an app; the PC tries it at once (its tools, or why it couldn't reach it). */
    fun addApp(fields: JSONObject, done: (Result<Triple<McpServer, List<McpTool>, String?>>) -> Unit) =
        connections({ u, t, p -> client.addMcp(u, t, p, fields) }) { r ->
            r.onSuccess { (srv, _, _) -> _state.update { it.copy(apps = it.apps.orEmpty() + srv) } }
            done(r)
        }

    fun updateApp(id: String, fields: JSONObject, done: (Result<McpServer>) -> Unit = {}) =
        connections({ u, t, p -> client.updateMcp(u, t, p, id, fields) }) { r ->
            r.onSuccess { srv -> _state.update { s -> s.copy(apps = s.apps?.map { if (it.id == id) srv else it }) } }
            done(r)
        }

    fun deleteApp(id: String) = connections({ u, t, p -> client.deleteMcp(u, t, p, id) }) { r ->
        r.onSuccess { _state.update { s -> s.copy(apps = s.apps?.filterNot { it.id == id }) } }
    }

    fun appTools(id: String, done: (Result<Pair<List<McpTool>, String?>>) -> Unit) = connections({ u, t, p -> client.mcpTools(u, t, p, id) }, done)

    /** Allows or refuses the action the open Bot waits on. */
    fun answer(allow: Boolean) {
        val open = _state.value.open ?: return
        val a = open.approval ?: return
        setOpen(open.bot.id) { it.copy(approval = null) }
        scope.launch {
            runCatching {
                val (urls, token, pin) = pc()
                client.answerBot(urls, token, pin, open.bot.id, a.id, allow)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                setOpen(open.bot.id) { it.copy(error = e.message) }
            }
        }
    }

    private val shots = android.util.LruCache<String, android.graphics.Bitmap>(24)

    /** A browser step's screenshot, decoded (kept for a few steps). */
    suspend fun shot(botId: String, messageId: String): android.graphics.Bitmap? {
        shots.get(messageId)?.let { return it }
        return runCatching {
            val (urls, token, pin) = pc()
            val bytes = client.botShot(urls, token, pin, botId, messageId)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()?.also { shots.put(messageId, it) }
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
