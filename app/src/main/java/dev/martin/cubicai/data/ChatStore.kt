package dev.martin.cubicai.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One JSON file per chat in app-private storage; attachments live alongside in their own folder. */
class ChatStore(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    val attachmentDir = File(context.filesDir, "attachments").apply { mkdirs() }

    fun loadAll(): List<Chat> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { chatFromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun save(chat: Chat) {
        val tmp = File(dir, "${chat.id}.tmp")
        tmp.writeText(chat.toJson().toString())
        tmp.renameTo(File(dir, "${chat.id}.json"))
    }

    fun delete(chat: Chat) {
        chat.messages.flatMap { it.attachments }.forEach { File(it.path).delete() }
        File(dir, "${chat.id}.json").delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        attachmentDir.listFiles()?.forEach { it.delete() }
    }
}
