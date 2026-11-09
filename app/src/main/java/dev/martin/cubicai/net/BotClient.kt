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
)

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
)

/** What a Bot's run sends while it works. [seq] numbers them, so a phone that comes back can carry on. */
sealed interface BotEvent {
    val seq: Int

    data class Thinking(override val seq: Int) : BotEvent
    data class Text(override val seq: Int, val delta: String) : BotEvent
    data class Reasoning(override val seq: Int, val delta: String) : BotEvent
    /** A tool starts. */
    data class Tool(override val seq: Int, val call: BotCall) : BotEvent
    /** A message was stored (the user's, a finished turn, a tool's result or an error). */
    data class Posted(override val seq: Int, val message: BotMessage) : BotEvent
    data class Done(override val seq: Int, val error: String?, val stopped: Boolean) : BotEvent
}

/** A Bot's chat as the PC has it, and where to follow its run from if it's working. */
data class BotChat(val messages: List<BotMessage>, val running: Boolean, val follow: Int?)

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
    )
}

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
)

internal fun botEventFrom(o: JSONObject): BotEvent? {
    val seq = o.optInt("seq", -1)
    return when (o.optString("type")) {
        "thinking" -> BotEvent.Thinking(seq)
        "text" -> BotEvent.Text(seq, o.optString("delta"))
        "reasoning" -> BotEvent.Reasoning(seq, o.optString("delta"))
        "tool" -> BotEvent.Tool(seq, BotCall(o.optString("id"), o.optString("name"), o.optString("args", "{}")))
        "message" -> o.optJSONObject("message")?.let { BotEvent.Posted(seq, botMessageFrom(it)) }
        "done" -> BotEvent.Done(seq, o.str("error"), o.optBoolean("stopped"))
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
    botCall(baseUrls, token, pin, { url("$it/bots/$id/messages") }) { r ->
        val o = JSONObject(r.body.string())
        BotChat(o.optJSONArray("messages").objects().map(::botMessageFrom), o.optBoolean("running"), if (o.isNull("follow")) null else o.optInt("follow"))
    }

/**
 * Sends a message; the PC stores it and starts the Bot, or hands it to the run under way (then the
 * second value is true).
 */
suspend fun LlmClient.sendToBot(baseUrls: List<String>, token: String, pin: String, id: String, text: String): Pair<BotMessage, Boolean> =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/messages").post(json(JSONObject().put("text", text))) }) { r ->
        JSONObject(r.body.string()).let { botMessageFrom(it.getJSONObject("message")) to it.optBoolean("queued") }
    }

suspend fun LlmClient.clearBotChat(baseUrls: List<String>, token: String, pin: String, id: String) =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/messages").delete() }) { }

suspend fun LlmClient.stopBot(baseUrls: List<String>, token: String, pin: String, id: String) =
    botCall(baseUrls, token, pin, { url("$it/bots/$id/run").delete() }) { }

/**
 * Follows a Bot's run from event [after] until it ends. Throws if the connection drops first (the Bot
 * carries on on the PC; follow again from the last seq seen). 404 means there's no run to follow.
 */
suspend fun LlmClient.followBot(
    baseUrls: List<String>, token: String, pin: String, id: String, after: Int, onEvent: (BotEvent) -> Unit,
) = botCall(baseUrls, token, pin, { url("$it/bots/$id/events?after=$after").header("Accept", "text/event-stream") }) { r ->
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
