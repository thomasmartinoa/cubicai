package dev.martin.cubicai.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/**
 * A file stored in app-private storage. Attached images are JPEGs and other attached files extracted text;
 * videos and audio only come from generation on the PC (or a voice sample for it).
 */
data class Attachment(val kind: String, val name: String, val path: String) {
    val isImage get() = kind == KIND_IMAGE
    val isVideo get() = kind == KIND_VIDEO
    val isAudio get() = kind == KIND_AUDIO
    val isMedia get() = isImage || isVideo || isAudio

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_TEXT = "text"
        const val KIND_VIDEO = "video"
        const val KIND_AUDIO = "audio"
    }
}

/**
 * A reply made by ComfyUI on the PC. [jobId] is set once the PC accepted the job, so the app can pick the
 * progress back up after switching chats or a restart; [params] is the request, kept for Regenerate.
 */
/**
 * [seed]: the one the PC used for the result, kept apart from [params] so Regenerate still varies it.
 * [call]: set when the chat model asked for this itself (its `create` tool call), so the model gets the result
 * back and carries on; null for something made from the Create sheet.
 */
data class MediaRequest(
    val generator: String,
    val params: String,
    val jobId: String? = null,
    val seed: Long? = null,
    val call: ToolCall? = null,
    /** Waiting for the user's yes before it starts (a video asked for by the chat model). */
    val ask: Boolean = false,
    /** When it was sent to the PC, for how long it took. */
    val startedAt: Long = 0,
)

/** A video Grok Imagine is making on xAI's side: its request id, kept so a closed app can collect it later. */
data class CloudJob(val id: String, val startedAt: Long)

/** A tool call from the chat model; [arguments] is its JSON object as text. */
data class ToolCall(val id: String, val name: String, val arguments: String)

data class Message(
    val role: String,
    val content: String = "",
    val reasoning: String = "",
    val attachments: List<Attachment> = emptyList(),
    val citations: List<String> = emptyList(),
    val error: String? = null,
    val model: String? = null,
    /** Persona that wrote this reply, for the label above it. */
    val persona: String? = null,
    val stopped: Boolean = false,
    /** Token counts the provider reported for this reply: the whole conversation it read, and what it wrote. */
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val media: MediaRequest? = null,
    /** How long the reply took to write or make, in milliseconds (0 when unknown). */
    val took: Long = 0,
    /** Said aloud in a voice call (the user's words were transcribed, the reply was spoken). */
    val spoken: Boolean = false,
    val cloudJob: CloudJob? = null,
    val id: String = newId(),
) {
    /** A PC generation that hasn't produced its files yet (and hasn't failed, been stopped, or is still waiting for a yes). */
    val mediaPending get() = media != null && !media.ask && attachments.isEmpty() && error == null && !stopped

    /** A Grok video still being made (or made but not downloaded yet). */
    val cloudPending get() = cloudJob != null && attachments.isEmpty() && error == null && !stopped
}

data class Chat(
    val id: String = newId(),
    val title: String = "New chat",
    val messages: List<Message> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Each chat keeps its own persona, so reopening it brings the same character back. */
    val personaId: String = Personas.NONE,
    /** Model this chat last used; reopening the chat switches back to it. */
    val providerId: String? = null,
    val model: String? = null,
    /**
     * This chat's copy of the character: the face picture new pictures of it start from, and the voice sample
     * its voice messages copy. Null falls back to the behaviour's own.
     */
    val facePath: String? = null,
    val voicePath: String? = null,
)

// --- JSON helpers -------------------------------------------------------------------------------

fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { if (isNull(it)) null else optString(it) }

fun Chat.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("updatedAt", updatedAt)
    .put("personaId", personaId)
    .put("providerId", providerId)
    .put("model", model)
    .put("facePath", facePath)
    .put("voicePath", voicePath)
    .put("messages", JSONArray(messages.map { it.toJson() }))

fun chatFromJson(o: JSONObject) = Chat(
    id = o.getString("id"),
    title = o.optString("title", "Chat"),
    updatedAt = o.optLong("updatedAt"),
    personaId = o.optString("personaId", Personas.NONE),
    providerId = o.str("providerId"),
    model = o.str("model"),
    facePath = o.str("facePath"),
    voicePath = o.str("voicePath"),
    messages = o.optJSONArray("messages").objects().map(::messageFromJson),
)

private fun Message.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("role", role)
    put("content", content)
    put("reasoning", reasoning)
    put("attachments", JSONArray(attachments.map {
        JSONObject().put("kind", it.kind).put("name", it.name).put("path", it.path)
    }))
    put("citations", JSONArray(citations))
    put("stopped", stopped)
    error?.let { put("error", it) }
    model?.let { put("model", it) }
    persona?.let { put("persona", it) }
    if (promptTokens > 0) put("promptTokens", promptTokens)
    if (outputTokens > 0) put("outputTokens", outputTokens)
    media?.let {
        put("media", JSONObject().put("generator", it.generator).put("params", it.params).put("jobId", it.jobId).put("seed", it.seed).apply {
            it.call?.let { c -> put("call", JSONObject().put("id", c.id).put("name", c.name).put("arguments", c.arguments)) }
            if (it.ask) put("ask", true)
            if (it.startedAt > 0) put("startedAt", it.startedAt)
        })
    }
    if (took > 0) put("took", took)
    if (spoken) put("spoken", true)
    cloudJob?.let { put("cloudJob", JSONObject().put("id", it.id).put("startedAt", it.startedAt)) }
}

private fun messageFromJson(o: JSONObject) = Message(
    id = o.optString("id", newId()),
    role = o.getString("role"),
    content = o.optString("content"),
    reasoning = o.optString("reasoning"),
    attachments = o.optJSONArray("attachments").objects().map {
        Attachment(it.getString("kind"), it.getString("name"), it.getString("path"))
    },
    citations = o.optJSONArray("citations").strings(),
    error = o.str("error"),
    model = o.str("model"),
    persona = o.str("persona"),
    stopped = o.optBoolean("stopped"),
    promptTokens = o.optInt("promptTokens"),
    outputTokens = o.optInt("outputTokens"),
    media = o.optJSONObject("media")?.let {
        MediaRequest(
            it.getString("generator"),
            it.optString("params", "{}"),
            it.str("jobId"),
            if (it.has("seed")) it.optLong("seed") else null,
            it.optJSONObject("call")?.let { c -> ToolCall(c.optString("id"), c.optString("name"), c.optString("arguments", "{}")) },
            it.optBoolean("ask"),
            it.optLong("startedAt"),
        )
    },
    took = o.optLong("took"),
    spoken = o.optBoolean("spoken"),
    cloudJob = o.optJSONObject("cloudJob")?.let { j -> j.str("id")?.let { CloudJob(it, j.optLong("startedAt")) } },
)
