package dev.martin.cubicai.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** How a provider exposes live web search, if at all. */
enum class SearchMode { XAI_TOOLS, OPENROUTER_WEB, LOCAL_PC, NONE }

data class Provider(
    val id: String,
    val name: String,
    val shortName: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
    val search: SearchMode,
    /** Shown in the model picker when the live model list can't be fetched. */
    val suggestedModels: List<String>,
    /** Can create images from a prompt (Gemini's native image models). */
    val images: Boolean = false,
    val imageModel: String = "",
)

object Providers {
    /** Ollama on the user's PC, reached through server/cubicai_server.py (home Wi-Fi, optionally Tailscale). */
    val LOCAL = Provider(
        id = "local",
        name = "Local PC (Ollama)",
        shortName = "Local",
        baseUrl = "",
        defaultModel = "",
        keyUrl = "",
        search = SearchMode.LOCAL_PC,
        suggestedModels = emptyList(),
    )
    val GEMINI = Provider(
        id = "gemini",
        name = "Google Gemini",
        shortName = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-3.5-flash",
        keyUrl = "https://aistudio.google.com/apikey",
        search = SearchMode.NONE,
        suggestedModels = listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-pro-preview"),
        images = true,
        imageModel = "gemini-3.1-flash-image",
    )
    val XAI = Provider(
        id = "xai",
        name = "xAI (Grok)",
        shortName = "Grok",
        baseUrl = "https://api.x.ai/v1",
        defaultModel = "grok-4.7",
        keyUrl = "https://console.x.ai",
        search = SearchMode.XAI_TOOLS,
        suggestedModels = listOf("grok-4.7", "grok-4.6", "grok-4.3", "grok-build-0.1"),
    )
    val OPENROUTER = Provider(
        id = "openrouter",
        name = "OpenRouter",
        shortName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "google/gemma-4-31b-it:free",
        keyUrl = "https://openrouter.ai/keys",
        search = SearchMode.OPENROUTER_WEB,
        suggestedModels = listOf(
            "google/gemma-4-31b-it:free",
            "nvidia/nemotron-3.5-super-vl:free",
            "thinkingmachines/inkling:free",
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            "openrouter/free",
            "x-ai/grok-4.7",
        ),
    )
    val CUSTOM = Provider(
        id = "custom",
        name = "Custom (OpenAI-compatible)",
        shortName = "Custom",
        baseUrl = "",
        defaultModel = "",
        keyUrl = "",
        search = SearchMode.NONE,
        suggestedModels = emptyList(),
    )

    val all = listOf(LOCAL, GEMINI, XAI, OPENROUTER, CUSTOM)

    fun get(id: String) = all.firstOrNull { it.id == id } ?: LOCAL
}

data class Settings(
    val providerId: String = Providers.LOCAL.id,
    val apiKeys: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
    val customBaseUrl: String = "",
    /** Paired PC on home Wi-Fi, e.g. https://192.168.1.10:8443; its token lives in apiKeys["local"]. */
    val localUrl: String = "",
    /** The same PC over Tailscale (https://100.x.y.z:8443), present only if remote access was on when pairing. */
    val localRemoteUrl: String = "",
    /** SHA-256 of the PC's self-signed certificate; the only certificate we accept for the PC. */
    val localFingerprint: String = "",
    val personaId: String = Personas.NONE,
    val customPersonas: List<Persona> = emptyList(),
    /** "About you": added to every persona except None. */
    val aboutMe: String = "",
    val webSearch: Boolean = false,
    /** Hides chats from the recent-apps screen and blocks screenshots. */
    val privacyScreen: Boolean = false,
) {
    val provider get() = Providers.get(providerId)

    val personas get() = Personas.builtIn + customPersonas

    val persona get() = personas.firstOrNull { it.id == personaId } ?: Personas.builtIn.first()

    fun key(p: Provider) = apiKeys[p.id].orEmpty()

    fun model(p: Provider) = models[p.id]?.takeIf { it.isNotBlank() } ?: p.defaultModel

    val isPaired get() = localUrl.isNotBlank() && localFingerprint.isNotBlank() && key(Providers.LOCAL).isNotBlank()

    /** Where to reach a provider, in the order to try them (home Wi-Fi before Tailscale). */
    fun baseUrls(p: Provider): List<String> = when (p.id) {
        Providers.CUSTOM.id -> listOf(customBaseUrl.trim().trimEnd('/')).filter { it.isNotBlank() }
        Providers.LOCAL.id -> listOf(localUrl, localRemoteUrl).filter { it.isNotBlank() }.map { it.trimEnd('/') + "/v1" }
        else -> listOf(p.baseUrl)
    }

    /** Certificate pin for a provider; only the paired PC uses one. */
    fun pin(p: Provider) = if (p.id == Providers.LOCAL.id) localFingerprint.ifBlank { null } else null
}

/** Contents of the QR code / link shown by CubicAI Server → Pair. */
data class Pairing(val url: String, val remoteUrl: String, val token: String, val fingerprint: String) {
    companion object {
        fun parse(text: String): Pairing? = runCatching {
            val uri = Uri.parse(text.trim())
            if (uri.scheme != "cubicai" || uri.host != "pair") return null
            val url = uri.getQueryParameter("u").orEmpty()
            val remote = uri.getQueryParameter("r").orEmpty()
            val token = uri.getQueryParameter("t").orEmpty()
            val fp = uri.getQueryParameter("f").orEmpty().lowercase()
            if (!url.startsWith("https://") || token.length < 32 || !fp.matches(Regex("[0-9a-f]{64}"))) return null
            if (remote.isNotEmpty() && !remote.startsWith("https://")) return null
            Pairing(url.trimEnd('/'), remote.trimEnd('/'), token, fp)
        }.getOrNull()
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        providerId = prefs.getString("provider", null) ?: Providers.LOCAL.id,
        apiKeys = Providers.all.associate { it.id to SecretBox.open(prefs.getString("key_${it.id}", null)) },
        models = Providers.all.associate { it.id to prefs.getString("model_${it.id}", "").orEmpty() },
        customBaseUrl = prefs.getString("custom_base_url", "").orEmpty(),
        localUrl = prefs.getString("local_url", "").orEmpty(),
        localRemoteUrl = prefs.getString("local_remote_url", "").orEmpty(),
        localFingerprint = prefs.getString("local_fingerprint", "").orEmpty(),
        personaId = prefs.getString("persona", null) ?: Personas.NONE,
        customPersonas = runCatching { personasFromJson(prefs.getString("custom_personas", "[]")!!) }.getOrDefault(emptyList()),
        aboutMe = prefs.getString("about_me", "").orEmpty(),
        webSearch = prefs.getBoolean("web_search", false),
        privacyScreen = prefs.getBoolean("privacy_screen", false),
    )

    fun save(s: Settings) = prefs.edit {
        putString("provider", s.providerId)
        s.apiKeys.forEach { (id, key) -> putString("key_$id", SecretBox.seal(key)) }
        s.models.forEach { (id, model) -> putString("model_$id", model) }
        putString("custom_base_url", s.customBaseUrl)
        putString("local_url", s.localUrl)
        putString("local_remote_url", s.localRemoteUrl)
        putString("local_fingerprint", s.localFingerprint)
        putString("persona", s.personaId)
        putString("custom_personas", personasToJson(s.customPersonas))
        putString("about_me", s.aboutMe)
        putBoolean("web_search", s.webSearch)
        putBoolean("privacy_screen", s.privacyScreen)
    }

    private fun personasToJson(list: List<Persona>) = JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("instructions", it.instructions)
    }).toString()

    private fun personasFromJson(json: String) = JSONArray(json).objects().map {
        Persona(it.getString("id"), it.getString("name"), it.optString("instructions"))
    }
}
