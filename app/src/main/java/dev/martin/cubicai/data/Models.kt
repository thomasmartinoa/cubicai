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
/** [seed]: the one the PC used for the result, kept apart from [params] so Regenerate still varies it. */
data class MediaRequest(val generator: String, val params: String, val jobId: String? = null, val seed: Long? = null)

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
    val id: String = newId(),
) {
    /** A PC generation that hasn't produced its files yet (and hasn't failed or been stopped). */
    val mediaPending get() = media != null && attachments.isEmpty() && error == null && !stopped
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
    .put("messages", JSONArray(messages.map { it.toJson() }))

fun chatFromJson(o: JSONObject) = Chat(
    id = o.getString("id"),
    title = o.optString("title", "Chat"),
    updatedAt = o.optLong("updatedAt"),
    personaId = o.optString("personaId", Personas.NONE),
    providerId = o.str("providerId"),
    model = o.str("model"),
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
        put("media", JSONObject().put("generator", it.generator).put("params", it.params).put("jobId", it.jobId).put("seed", it.seed))
    }
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
        MediaRequest(it.getString("generator"), it.optString("params", "{}"), it.str("jobId"), if (it.has("seed")) it.optLong("seed") else null)
    },
)
