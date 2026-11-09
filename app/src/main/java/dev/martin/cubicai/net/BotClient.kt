package dev.martin.cubicai.net

import dev.martin.cubicai.data.objects
import dev.martin.cubicai.data.str
import dev.martin.cubicai.data.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** A Bot living on the paired PC (see the gateway's bots module): its profile and how it's doing. */
data class Bot(
    val id: String,
    val name: String,
    val job: String,
    /** "#rrggbb": the colour of its cube. */
    val color: String,
    /** local, xai, gemini, openrouter or custom. */
    val provider: String,
    val model: String,
    val running: Boolean = false,
    /** The latest thing said in its chat, for the list. */
    val last: String? = null,
    val lastFromUser: Boolean = false,
    val lastError: Boolean = false,
    val lastAt: Long = 0,
    val created: Double = 0.0,
    /** Routines switched on, and ones the Bot proposed that wait for the user's yes. */
    val routines: Int = 0,
    val pending: Int = 0,
    /** Whether it may use the PC's sandboxed computer (terminal and browser). */
    val computer: Boolean = true,
    /** Whether it may use the user's Google account (when the PC has one connected). */
    val google: Boolean = false,
    /** Whether it may use this phone (reminders, notifications, clipboard, links, location). */
    val phone: Boolean = true,
    /** The apps (MCP server ids on the PC) whose tools it gets. */
    val mcp: List<String> = emptyList(),
)

/** The PC's Google connection. */
data class GoogleStatus(val connected: Boolean, val email: String?, val hasClient: Boolean, val note: String? = null)

/** An app (MCP server) on the PC. Header/env values never come back, only their names. */
data class McpServer(
    val id: String,
    val name: String,
    /** remote or sandbox. */
    val kind: String,
    val url: String,
    val command: String,
    val secrets: List<String>,
    val enabled: Boolean,
    /** changes, always or never. */
    val ask: String,
)

data class McpTool(val name: String, val description: String, val readOnly: Boolean)

/** A group chat on the PC: two to six Bots and the user. Its chat is opened as the thread "g:<id>". */
data class BotGroup(
    val id: String,
    val name: String,
    val members: List<String>,
    val running: Boolean = false,
    val last: String? = null,
    /** Who said [last]: a Bot id, or null for the user. */
    val lastBot: String? = null,
    val lastError: Boolean = false,
    val lastAt: Long = 0,
) {
    val thread get() = "g:$id"
}

/** A saved how-to every Bot can use (`/name` in a message). A Bot's proposal waits for approval. */
data class Skill(
    val id: String,
    val name: String,
    val about: String,
    val steps: String,
    val approved: Boolean,
    /** "user", or the id of the Bot that proposed it. */
    val by: String,
    val byName: String,
    /** A proposed change to this skill's id. */
    val replaces: String?,
)

/** Teaching a Bot a task in its browser: where the browser is, the steps so far, the screenshot's number. */
data class TeachState(val url: String, val title: String, val steps: List<String>, val shot: Int, val error: String?)

/**
 * A task the PC starts the Bot on at set times. [schedule] is the PC's JSON
 * ({kind: daily, time, days} | {kind: every, hours} | {kind: once, date, time, at}); [`when`] says it in words.
 */
data class BotRoutine(
    val id: String,
    val name: String,
    val task: String,
    val schedule: JSONObject,
    val `when`: String,
    val enabled: Boolean,
    val approved: Boolean,
    /** "user" or "bot". */
    val by: String,
    /** Seconds since the epoch. */
    val lastRun: Long?,
    val nextRun: Long?,
)

/** Something that happened on the PC, for a notification: a run ended, or a Bot asks to approve a routine. */
data class BotFeedItem(
    val id: String,
    val at: Long,
    /** reply, routine, approval or error. */
    val kind: String,
    val bot: String,
    val name: String,
    val color: String,
    val text: String,
    /** A routine that found nothing worth telling. */
    val quiet: Boolean,
    val routine: String?,
    /** For kind "phone": what the Bot sent this phone ({id, do: remind|notify|copy|open, …}). */
    val phone: JSONObject? = null,
    /** Set when it happened in a group chat. */
    val groupId: String? = null,
    val groupName: String? = null,
)

data class BotCall(val id: String, val name: String, val args: String)

/** One entry of a Bot's chat: the user's message, a reply (maybe with tool calls), or a tool's result. */
data class BotMessage(
    val id: String,
    /** user, assistant or tool. */
    val role: String,
    val text: String,
    val at: Long,
    val reasoning: String = "",
    val calls: List<BotCall> = emptyList(),
    /** Set on a message a routine sent: its name. */
    val routine: String? = null,
    /** For a tool result: the call it answers and the tool's name. */
    val call: String? = null,
    val tool: String? = null,
    val citations: List<String> = emptyList(),
    val failed: Boolean = false,
    val error: String? = null,
    val stopped: Boolean = false,
    val model: String? = null,
    val took: Long = 0,
    /** A browser step with a screenshot (fetched by the message's id). */
    val shot: Boolean = false,
    /** In a group chat: the Bot that said it. */
    val bot: String? = null,
    /** A task another Bot handed over: that Bot's name. */
    val from: String? = null,
    /** The user taught a task: the steps they took in the browser. */
    val taught: List<String> = emptyList(),
    /** Those steps were the user taking the browser over mid-task (not teaching a skill). */
    val tookOver: Boolean = false,
)

/** What a Bot's run sends while it works. [seq] numbers them, so a phone that comes back can carry on. */
sealed interface BotEvent {
    val seq: Int

    /** A turn starts; in a group, [bot] is the one speaking. */
    data class Thinking(override val seq: Int, val bot: String? = null) : BotEvent
    data class Text(override val seq: Int, val delta: String) : BotEvent
    data class Reasoning(override val seq: Int, val delta: String) : BotEvent
    /** A tool starts. */
    data class Tool(override val seq: Int, val call: BotCall) : BotEvent
    /** A message was stored (the user's, a finished turn, a tool's result or an error). */
    data class Posted(override val seq: Int, val message: BotMessage) : BotEvent
    data class Done(override val seq: Int, val error: String?, val stopped: Boolean) : BotEvent
    /** The Bot waits for the user to allow an action (e.g. submitting a form). */
    data class Approval(override val seq: Int, val id: String, val text: String) : BotEvent
    /** That wait is over (answered, stopped or timed out). */
    data class Approved(override val seq: Int, val id: String) : BotEvent
    /** Something for this phone ({id, do, …}): a delivery to carry out, or a question to answer. */
    data class Phone(override val seq: Int, val phone: JSONObject) : BotEvent
    /** A question to the phone is over (answered or timed out). */
    data class PhoneDone(override val seq: Int, val id: String) : BotEvent
}

/** An action the Bot waits on the user's yes for. */
data class BotApproval(val id: String, val text: String)

/** A Bot's chat as the PC has it, and where to follow its run from if it's working. */
data class BotChat(val messages: List<BotMessage>, val running: Boolean, val follow: Int?, val approval: BotApproval? = null, val phone: JSONObject? = null)

/** Which cloud keys the PC holds (never the keys themselves). */
data class PcKeys(val present: Set<String>, val customUrl: String?)

class BotGoneException : ApiException("This Bot isn't on your PC any more.")

internal fun botFrom(o: JSONObject): Bot {
    val last = o.optJSONObject("last")
    return Bot(
        id = o.getString("id"),
        name = o.optString("name"),
        job = o.optString("job"),
        color = o.optString("color", "#2f6bff"),
        provider = o.optString("provider", "local"),
        model = o.optString("model"),
        running = o.optBoolean("running"),
        last = last?.let { it.str("error") ?: it.str("text") },
        lastFromUser = last?.optString("role") == "user",
        lastError = last?.str("error") != null,
        lastAt = last?.optLong("at") ?: 0,
        created = o.optDouble("created", 0.0),
        routines = o.optInt("routines"),
        pending = o.optInt("pending"),
        computer = o.optBoolean("computer", true),
        google = o.optBoolean("google", false),
        phone = o.optBoolean("phone", true),
        mcp = o.optJSONArray("mcp").strings(),
    )
}

internal fun mcpServerFrom(o: JSONObject) = McpServer(
    id = o.optString("id"),
    name = o.optString("name"),
    kind = o.optString("kind", "remote"),
    url = o.optString("url"),
    command = o.optString("command"),
    secrets = (o.optJSONArray("headers") ?: o.optJSONArray("env")).strings(),
    enabled = o.optBoolean("enabled", true),
    ask = o.optString("ask", "changes"),
)

private fun googleFrom(o: JSONObject) = GoogleStatus(o.optBoolean("connected"), o.str("email")?.takeIf { it.isNotBlank() }, o.optBoolean("client"), o.str("note"))

internal fun routineFrom(o: JSONObject) = BotRoutine(
    id = o.optString("id"),
    name = o.optString("name"),
    task = o.optString("task"),
    schedule = o.optJSONObject("schedule") ?: JSONObject(),
    `when` = o.optString("when"),
    enabled = o.optBoolean("enabled", true),
    approved = o.optBoolean("approved"),
    by = o.optString("by", "user"),
    lastRun = if (o.isNull("last_run")) null else o.optLong("last_run"),
    nextRun = if (o.isNull("next_run")) null else o.optLong("next_run"),
)

internal fun feedItemFrom(o: JSONObject) = BotFeedItem(
    id = o.optString("id"),
    at = o.optLong("at"),
    kind = o.optString("kind"),
    bot = o.optString("bot"),
    name = o.optString("name"),
    color = o.optString("color", "#2f6bff"),
    text = o.optString("text"),
    quiet = o.optBoolean("quiet"),
    routine = o.optJSONObject("routine")?.str("name"),
    phone = o.optJSONObject("phone"),
    groupId = o.optJSONObject("group")?.str("id"),
    groupName = o.optJSONObject("group")?.str("name"),
)

internal fun groupFrom(o: JSONObject): BotGroup {
    val last = o.optJSONObject("last")
    return BotGroup(
        id = o.optString("id"),
        name = o.optString("name"),
        members = o.optJSONArray("members").strings(),
        running = o.optBoolean("running"),
        last = last?.let { it.str("error") ?: it.str("text") },
        lastBot = last?.str("bot"),
        lastError = last?.str("error") != null,
        lastAt = last?.optLong("at") ?: 0,
    )
}

internal fun skillFrom(o: JSONObject) = Skill(
    id = o.optString("id"),
    name = o.optString("name"),
    about = o.optString("about"),
    steps = o.optString("steps"),
    approved = o.optBoolean("approved"),
    by = o.optString("by", "user"),
    byName = o.optString("by_name"),
    replaces = o.str("replaces"),
)

internal fun botMessageFrom(o: JSONObject) = BotMessage(
    id = o.optString("id"),
    role = o.optString("role"),
    text = o.optString("text"),
    at = o.optLong("at"),
    routine = o.optJSONObject("routine")?.str("name"),
    reasoning = o.optString("reasoning"),
    calls = o.optJSONArray("calls").objects().map { BotCall(it.optString("id"), it.optString("name"), it.optString("args", "{}")) },
    call = o.str("call"),
    tool = o.str("tool"),
    citations = o.optJSONArray("citations").strings(),
    failed = o.optBoolean("failed"),
    error = o.str("error"),
    stopped = o.optBoolean("stopped"),
    model = o.str("model"),
    took = o.optLong("took"),
    shot = o.optBoolean("shot"),
    bot = o.str("bot"),
    from = o.optJSONObject("from")?.str("name"),
    taught = o.optJSONArray("taught").strings(),
    tookOver = o.optBoolean("took_over"),
)

internal fun botEventFrom(o: JSONObject): BotEvent? {
    val seq = o.optInt("seq", -1)
    return when (o.optString("type")) {
        "thinking" -> BotEvent.Thinking(seq, o.str("bot"))
        "text" -> BotEvent.Text(seq, o.optString("delta"))
        "reasoning" -> BotEvent.Reasoning(seq, o.optString("delta"))
        "tool" -> BotEvent.Tool(seq, BotCall(o.optString("id"), o.optString("name"), o.optString("args", "{}")))
        "message" -> o.optJSONObject("message")?.let { BotEvent.Posted(seq, botMessageFrom(it)) }
        "done" -> BotEvent.Done(seq, o.str("error"), o.optBoolean("stopped"))
        "approval" -> BotEvent.Approval(seq, o.optString("id"), o.optString("text"))
        "approved" -> BotEvent.Approved(seq, o.optString("id"))
        "phone" -> o.optJSONObject("phone")?.let { BotEvent.Phone(seq, it) }
        "phone_done" -> BotEvent.PhoneDone(seq, o.optString("id"))
        else -> null
    }
}

/** A request to the PC's Bot routes; the PC's own words when it refuses. */
private suspend fun <T> LlmClient.botCall(
    baseUrls: List<String>,
    token: String,
    pin: String,
    build: Request.Builder.(String) -> Unit,
    read: (okhttp3.Response) -> T,
): T = withContext(Dispatchers.IO) {
    var call: Call? = null
    val cancelHandle = cancelOnStop { call?.cancel() }
    try {
        executeFirst(baseUrls, pin, { call = it; if (!isActive) it.cancel() }) { base ->
            Request.Builder().header("Authorization", "Bearer $token").apply { build(base) }.build()
        }.use { response ->
            if (!response.isSuccessful) {
                val body = response.body.string()
                val said = runCatching { JSONObject(body).optJSONObject("error")?.str("message") }.getOrNull()
                throw when {
                    response.code == 401 -> ApiException(errorMessage(401, body, pc = true))
                    response.code == 404 && said?.startsWith("No such Bot") == true -> BotGoneException()
                    // A gateway from before Bots answers every Bot route with a plain "Not found".
                    response.code == 404 && (said == null || said == "Not found") ->
                        ApiException("Your PC's CubicAI gateway doesn't have Bots yet. Update it from CubicAI Lab (Phone tab → Update).")
                    said != null -> ApiException(said)
                    else -> ApiException(errorMessage(response.code, body, pc = true))
                }
            }
            read(response)
        }
    } catch (e: IOException) {
        ensureActive()
        throw networkError(e, pin)
    } finally {
        cancelHandle?.dispose()
    }
}

private fun LlmClient.json(o: JSONObject) = o.toString().toRequestBody(jsonType)

/** A chat's routes: a Bot's own ("bots/<id>"), or a group's (thread "g:<id>" → "groups/<id>"). */
private fun thread(id: String) = if (id.startsWith("g:")) "groups/${id.removePrefix("g:")}" else "bots/$id"

suspend fun LlmClient.listBots(baseUrls: List<String>, token: String, pin: String): List<Bot> =
    botCall(baseUrls, token, pin, { url("$it/bots") }) { r -> JSONObject(r.body.string()).optJSONArray("bots").objects().map(::botFrom) }

/** Makes a Bot from {name, job, color, provider, model, memory?}; returns it with its memory. */
suspend fun LlmClient.createBot(baseUrls: List<String>, token: String, pin: String, fields: JSONObject): Pair<Bot, String> =
    botCall(baseUrls, token, pin, { url("$it/bots").post(json(fields)) }) { r ->
        JSONObject(r.body.string()).let { botFrom(it.getJSONObject("bot")) to it.optString("memory") }
    }

suspend fun LlmClient.getBot(baseUrls: List<String>, token: String, pin: String, id: String): Pair<Bot, String> =
    botCall(baseUrls, token, pin, { url("$it/bots/$id") }) { r ->
        JSONObject(r.body.string()).let { botFrom(it.getJSONObject("bot")) to it.optString("memory") }
    }

suspend fun LlmClient.updateBot(baseUrls: List<String>, token: String, pin: String, id: String, fields: JSONObject): Pair<Bot, String> =
    botCall(baseUrls, token, pin, { url("$it/bots/$id").put(json(fields)) }) { r ->
        JSONObject(r.body.string()).let { botFrom(it.getJSONObject("bot")) to it.optString("memory") }
    }

suspend fun LlmClient.deleteBot(baseUrls: List<String>, token: String, pin: String, id: String) =
    botCall(baseUrls, token, pin, { url("$it/bots/$id").delete() }) { }

suspend fun LlmClient.botChat(baseUrls: List<String>, token: String, pin: String, id: String): BotChat =
    botCall(baseUrls, token, pin, { url("$it/${thread(id)}/messages") }) { r ->
        val o = JSONObject(r.body.string())
        BotChat(
            o.optJSONArray("messages").objects().map(::botMessageFrom), o.optBoolean("running"), if (o.isNull("follow")) null else o.optInt("follow"),
            o.optJSONObject("approval")?.let { BotApproval(it.optString("id"), it.optString("text")) },
            o.optJSONObject("phone"),
        )
    }

/**
 * Sends a message; the PC stores it and starts the Bot, or hands it to the run under way (then the
 * second value is true).
 */
suspend fun LlmClient.sendToBot(baseUrls: List<String>, token: String, pin: String, id: String, text: String): Pair<BotMessage, Boolean> =
    botCall(baseUrls, token, pin, { url("$it/${thread(id)}/messages").post(json(JSONObject().put("text", text))) }) { r ->
        JSONObject(r.body.string()).let { botMessageFrom(it.getJSONObject("message")) to it.optBoolean("queued") }
    }

suspend fun LlmClient.clearBotChat(baseUrls: List<String>, token: String, pin: String, id: String) =
    botCall(baseUrls, token, pin, { url("$it/${thread(id)}/messages").delete() }) { }

suspend fun LlmClient.stopBot(baseUrls: List<String>, token: String, pin: String, id: String) =
    botCall(baseUrls, token, pin, { url("$it/${thread(id)}/run").delete() }) { }

/**
 * Follows a Bot's run from event [after] until it ends. Throws if the connection drops first (the Bot
 * carries on on the PC; follow again from the last seq seen). 404 means there's no run to follow.
 */
suspend fun LlmClient.followBot(
    baseUrls: List<String>, token: String, pin: String, id: String, after: Int, onEvent: (BotEvent) -> Unit,
) = botCall(baseUrls, token, pin, { url("$it/${thread(id)}/events?after=$after").header("Accept", "text/event-stream") }) { r ->
    val source = r.body.source()
    while (true) {
        val line = source.readUtf8Line() ?: break
        if (!line.startsWith("data:")) continue
        val event = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull()?.let(::botEventFrom) ?: continue
        onEvent(event)
        if (event is BotEvent.Done) return@botCall
    }
    throw IOException("the Bot's stream ended early")
}

/** Hands cloud keys to the PC for Bots that use them ("" removes one). */
suspend fun LlmClient.pushKeys(baseUrls: List<String>, token: String, pin: String, keys: JSONObject): PcKeys =
    botCall(baseUrls, token, pin, { url("$it/keys").put(json(keys)) }) { r -> pcKeysFrom(JSONObject(r.body.string())) }

suspend fun LlmClient.pcKeys(baseUrls: List<String>, token: String, pin: String): PcKeys =
    botCall(baseUrls, token, pin, { url("$it/keys") }) { r -> pcKeysFrom(JSONObject(r.body.string())) }

private fun pcKeysFrom(o: JSONObject): PcKeys {
    val k = o.optJSONObject("keys") ?: JSONObject()
    return PcKeys(k.keys().asSequence().filter { k.optBoolean(it) }.toSet(), k.str("custom_url"))
}

private fun routinesOf(r: okhttp3.Response) = JSONObject(r.body.string()).optJSONArray("routines").objects().map(::routineFrom)

suspend fun LlmClient.botRoutines(baseUrls: List<String>, token: String, pin: String, id: String): List<BotRoutine> =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/routines") }, ::routinesOf)

/** A new routine from {name, task, schedule}; returns all of the Bot's routines. */
suspend fun LlmClient.addRoutine(baseUrls: List<String>, token: String, pin: String, id: String, fields: JSONObject): List<BotRoutine> =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/routines").post(json(fields)) }, ::routinesOf)

/** Changes a routine (also {enabled}, {approved: true}); returns all of the Bot's routines. */
suspend fun LlmClient.updateRoutine(baseUrls: List<String>, token: String, pin: String, id: String, rid: String, fields: JSONObject): List<BotRoutine> =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/routines/$rid").put(json(fields)) }, ::routinesOf)

suspend fun LlmClient.deleteRoutine(baseUrls: List<String>, token: String, pin: String, id: String, rid: String): List<BotRoutine> =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/routines/$rid").delete() }, ::routinesOf)

suspend fun LlmClient.runRoutine(baseUrls: List<String>, token: String, pin: String, id: String, rid: String) =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/routines/$rid/run").post(json(JSONObject())) }) { }

/** What happened since [after] (ms), and the time to ask from next. */
suspend fun LlmClient.botFeed(baseUrls: List<String>, token: String, pin: String, after: Long): Pair<List<BotFeedItem>, Long> =
    botCall(baseUrls, token, pin, { url("$it/bots/feed?after=$after") }) { r ->
        val o = JSONObject(r.body.string())
        o.optJSONArray("items").objects().map(::feedItemFrom) to o.optLong("now", after)
    }

/** Answers an action the Bot waits on. */
suspend fun LlmClient.answerBot(baseUrls: List<String>, token: String, pin: String, id: String, approval: String, allow: Boolean) =
    botCall(baseUrls, token, pin, { url("$it/${thread(id)}/approval").post(json(JSONObject().put("id", approval).put("allow", allow))) }) { }

/** The Bots' computer's browser right now (JPEG), to watch a Bot work; fails while the computer is off. */
suspend fun LlmClient.botScreen(baseUrls: List<String>, token: String, pin: String, id: String): ByteArray =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/screen") }) { r -> r.body.bytes() }

/** A browser step's screenshot (JPEG bytes). */
suspend fun LlmClient.botShot(baseUrls: List<String>, token: String, pin: String, id: String, messageId: String): ByteArray =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/shots/$messageId.jpg") }) { r -> r.body.bytes() }

/** The phone's answer to a question its Bot asked ({error} when it can't). */
suspend fun LlmClient.answerPhone(baseUrls: List<String>, token: String, pin: String, id: String, request: String, answer: JSONObject) =
    botCall(baseUrls, token, pin, { url("$it/${thread(id)}/phone").post(json(JSONObject().put("id", request).put("answer", answer))) }) { }

// --- Google ---------------------------------------------------------------------------------------

suspend fun LlmClient.googleStatus(baseUrls: List<String>, token: String, pin: String): GoogleStatus =
    botCall(baseUrls, token, pin, { url("$it/google") }) { r -> googleFrom(JSONObject(r.body.string())) }

/** The user's Google Cloud "Desktop app" client, kept on the PC. */
suspend fun LlmClient.googleClient(baseUrls: List<String>, token: String, pin: String, id: String, secret: String): GoogleStatus =
    botCall(baseUrls, token, pin, { url("$it/google").put(json(JSONObject().put("client_id", id).put("client_secret", secret))) }) { r -> googleFrom(JSONObject(r.body.string())) }

/** Starts a sign-in that Google sends back to [redirect]; the page to open. */
suspend fun LlmClient.googleStart(baseUrls: List<String>, token: String, pin: String, redirect: String): String =
    botCall(baseUrls, token, pin, { url("$it/google/start").post(json(JSONObject().put("redirect", redirect))) }) { r -> JSONObject(r.body.string()).optString("url") }

suspend fun LlmClient.googleFinish(baseUrls: List<String>, token: String, pin: String, code: String, state: String): GoogleStatus =
    botCall(baseUrls, token, pin, { url("$it/google/finish").post(json(JSONObject().put("code", code).put("state", state))) }) { r -> googleFrom(JSONObject(r.body.string())) }

suspend fun LlmClient.googleSignOut(baseUrls: List<String>, token: String, pin: String): GoogleStatus =
    botCall(baseUrls, token, pin, { url("$it/google").delete() }) { r -> googleFrom(JSONObject(r.body.string())) }

// --- Apps (MCP) -----------------------------------------------------------------------------------

suspend fun LlmClient.mcpServers(baseUrls: List<String>, token: String, pin: String): List<McpServer> =
    botCall(baseUrls, token, pin, { url("$it/mcp") }) { r -> JSONObject(r.body.string()).optJSONArray("servers").objects().map(::mcpServerFrom) }

private fun toolsOf(o: JSONObject) = o.optJSONArray("tools").objects().map { McpTool(it.optString("name"), it.optString("description"), it.optBoolean("read_only")) }

/** Adds an app; the PC tries it at once: its tools, or why it couldn't reach it. */
suspend fun LlmClient.addMcp(baseUrls: List<String>, token: String, pin: String, fields: JSONObject): Triple<McpServer, List<McpTool>, String?> =
    botCall(baseUrls, token, pin, { url("$it/mcp").post(json(fields)) }) { r ->
        val o = JSONObject(r.body.string())
        Triple(mcpServerFrom(o.getJSONObject("server")), toolsOf(o), o.str("error"))
    }

suspend fun LlmClient.updateMcp(baseUrls: List<String>, token: String, pin: String, id: String, fields: JSONObject): McpServer =
    botCall(baseUrls, token, pin, { url("$it/mcp/$id").put(json(fields)) }) { r -> mcpServerFrom(JSONObject(r.body.string()).getJSONObject("server")) }

suspend fun LlmClient.deleteMcp(baseUrls: List<String>, token: String, pin: String, id: String) =
    botCall(baseUrls, token, pin, { url("$it/mcp/$id").delete() }) { }

/** An app's tools, asked afresh (or why it can't be reached). */
suspend fun LlmClient.mcpTools(baseUrls: List<String>, token: String, pin: String, id: String): Pair<List<McpTool>, String?> =
    botCall(baseUrls, token, pin, { url("$it/mcp/$id/tools") }) { r -> JSONObject(r.body.string()).let { toolsOf(it) to it.str("error") } }

// --- Groups ---------------------------------------------------------------------------------------

suspend fun LlmClient.listGroups(baseUrls: List<String>, token: String, pin: String): List<BotGroup> =
    botCall(baseUrls, token, pin, { url("$it/groups") }) { r -> JSONObject(r.body.string()).optJSONArray("groups").objects().map(::groupFrom) }

/** Makes a group from {name, members}. */
suspend fun LlmClient.createGroup(baseUrls: List<String>, token: String, pin: String, fields: JSONObject): BotGroup =
    botCall(baseUrls, token, pin, { url("$it/groups").post(json(fields)) }) { r -> groupFrom(JSONObject(r.body.string()).getJSONObject("group")) }

suspend fun LlmClient.getGroup(baseUrls: List<String>, token: String, pin: String, id: String): BotGroup =
    botCall(baseUrls, token, pin, { url("$it/groups/$id") }) { r -> groupFrom(JSONObject(r.body.string()).getJSONObject("group")) }

suspend fun LlmClient.updateGroup(baseUrls: List<String>, token: String, pin: String, id: String, fields: JSONObject): BotGroup =
    botCall(baseUrls, token, pin, { url("$it/groups/$id").put(json(fields)) }) { r -> groupFrom(JSONObject(r.body.string()).getJSONObject("group")) }

suspend fun LlmClient.deleteGroup(baseUrls: List<String>, token: String, pin: String, id: String) =
    botCall(baseUrls, token, pin, { url("$it/groups/$id").delete() }) { }

// --- Skills ---------------------------------------------------------------------------------------

private fun skillsOf(r: okhttp3.Response) = JSONObject(r.body.string()).optJSONArray("skills").objects().map(::skillFrom)

suspend fun LlmClient.listSkills(baseUrls: List<String>, token: String, pin: String): List<Skill> =
    botCall(baseUrls, token, pin, { url("$it/skills") }, ::skillsOf)

/** The user's own skill {name, about, steps}; returns all skills. */
suspend fun LlmClient.addSkill(baseUrls: List<String>, token: String, pin: String, fields: JSONObject): List<Skill> =
    botCall(baseUrls, token, pin, { url("$it/skills").post(json(fields)) }, ::skillsOf)

/** Changes a skill, or approves a proposal ({approved: true}); returns all skills. */
suspend fun LlmClient.updateSkill(baseUrls: List<String>, token: String, pin: String, id: String, fields: JSONObject): List<Skill> =
    botCall(baseUrls, token, pin, { url("$it/skills/$id").put(json(fields)) }, ::skillsOf)

/** Deletes a skill, or declines a proposal; returns all skills. */
suspend fun LlmClient.deleteSkill(baseUrls: List<String>, token: String, pin: String, id: String): List<Skill> =
    botCall(baseUrls, token, pin, { url("$it/skills/$id").delete() }, ::skillsOf)

// --- Teaching a task ------------------------------------------------------------------------------

/** One step of teaching Bot [id] in its browser ({do: start|look|open|tap|text|key|scroll|back, …}). */
suspend fun LlmClient.teachStep(baseUrls: List<String>, token: String, pin: String, id: String, step: JSONObject): TeachState =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/teach").post(json(step)) }) { r ->
        val o = JSONObject(r.body.string())
        TeachState(o.optString("url"), o.optString("title"), o.optJSONArray("steps").strings(), o.optInt("shot"), o.str("error"))
    }

suspend fun LlmClient.teachShot(baseUrls: List<String>, token: String, pin: String, id: String): ByteArray =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/teach/shot") }) { r -> r.body.bytes() }

/** Done: the steps go to the Bot ([what]: what the task is for), which writes a skill. */
suspend fun LlmClient.teachDone(baseUrls: List<String>, token: String, pin: String, id: String, what: String): BotMessage =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/teach/done").post(json(JSONObject().put("what", what))) }) { r ->
        botMessageFrom(JSONObject(r.body.string()).getJSONObject("message"))
    }

suspend fun LlmClient.teachCancel(baseUrls: List<String>, token: String, pin: String, id: String) =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/teach").delete() }) { }
