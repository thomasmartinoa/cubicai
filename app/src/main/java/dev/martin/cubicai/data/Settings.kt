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
    /** The same PC's IPv6 home address, learned from it: a way in when the router stops passing IPv4. */
    val localLan6Url: String = "",
    /** SHA-256 of the PC's self-signed certificate; the only certificate we accept for the PC. */
    val localFingerprint: String = "",
    val personaId: String = Personas.NONE,
    val customPersonas: List<Persona> = emptyList(),
    /** "About you": added to every persona except None. */
    val aboutMe: String = "",
    val webSearch: Boolean = false,
    /** Hides chats from the recent-apps screen and blocks screenshots. */
    val privacyScreen: Boolean = false,
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** Last thing made on the PC ("generator|aspect|seconds|language"), preselected when the Create sheet opens. */
    val lastCreate: String = "",
    /** Whether a reply's thinking is shown open: the user's last choice, or null to open it only while the model thinks. */
    val thoughtsOpen: Boolean? = null,
    /** Voice calls: the voice each behaviour speaks with (behaviour id → PC voice id), when not its own suggestion. */
    val callVoices: Map<String, String> = emptyMap(),
    val callSpeed: Float = 1.0f,
    /** The voice speaks up after a quiet spell, after [callCheckInSeconds]. */
    val callCheckIns: Boolean = true,
    val callCheckInSeconds: Int = 30,
    /** The model may make pictures, music and videos on the PC during a call. */
    val callMakesThings: Boolean = true,
    val callCaptions: Boolean = true,
    /** How long a pause ends what you're saying, in ms. */
    val callPatience: Int = 600,
    val callSpeaker: Boolean = true,
) {
    val provider get() = Providers.get(providerId)

    val personas get() = Personas.builtIn + customPersonas

    val persona get() = personas.firstOrNull { it.id == personaId } ?: Personas.builtIn.first()

    /** The voice a behaviour speaks with in a call: the user's pick, else its own, else the first one. */
    fun callVoice(persona: Persona) = callVoices[persona.id]?.takeIf { it.isNotBlank() } ?: persona.voice.ifBlank { DEFAULT_VOICE }

    fun key(p: Provider) = apiKeys[p.id].orEmpty()

    fun model(p: Provider) = models[p.id]?.takeIf { it.isNotBlank() } ?: p.defaultModel

    val isPaired get() = localUrl.isNotBlank() && localFingerprint.isNotBlank() && key(Providers.LOCAL).isNotBlank()

    /** Where to reach a provider, in the order to try them (home Wi-Fi before Tailscale). */
    fun baseUrls(p: Provider): List<String> = when (p.id) {
        Providers.CUSTOM.id -> listOf(customBaseUrl.trim().trimEnd('/')).filter { it.isNotBlank() }
        Providers.LOCAL.id -> listOf(localUrl, localLan6Url, localRemoteUrl).filter { it.isNotBlank() }.map { it.trimEnd('/') + "/v1" }
        else -> listOf(p.baseUrl)
    }

    /** Certificate pin for a provider; only the paired PC uses one. */
    fun pin(p: Provider) = if (p.id == Providers.LOCAL.id) localFingerprint.ifBlank { null } else null
}

const val DEFAULT_VOICE = "af_heart"

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
    /** Keys as written by this store, so a save re-encrypts only a key that changed, not all of them on every keystroke.
     *  Starts empty, so the first save also seals any key stored before encryption existed. */
    private var savedKeys = emptyMap<String, String>()

    fun load() = Settings(
        providerId = prefs.getString("provider", null) ?: Providers.LOCAL.id,
        apiKeys = Providers.all.associate { it.id to SecretBox.open(prefs.getString("key_${it.id}", null)) },
        models = Providers.all.associate { it.id to prefs.getString("model_${it.id}", "").orEmpty() },
        customBaseUrl = prefs.getString("custom_base_url", "").orEmpty(),
        localUrl = prefs.getString("local_url", "").orEmpty(),
        localRemoteUrl = prefs.getString("local_remote_url", "").orEmpty(),
        localLan6Url = prefs.getString("local_lan6_url", "").orEmpty(),
        localFingerprint = prefs.getString("local_fingerprint", "").orEmpty(),
        personaId = prefs.getString("persona", null) ?: Personas.NONE,
        customPersonas = runCatching { personasFromJson(prefs.getString("custom_personas", "[]")!!) }.getOrDefault(emptyList()),
        aboutMe = prefs.getString("about_me", "").orEmpty(),
        lastCreate = prefs.getString("last_create", "").orEmpty(),
        webSearch = prefs.getBoolean("web_search", false),
        privacyScreen = prefs.getBoolean("privacy_screen", false),
        theme = prefs.getString("theme", null) ?: "system",
        thoughtsOpen = if (prefs.contains("thoughts_open")) prefs.getBoolean("thoughts_open", false) else null,
        callVoices = runCatching {
            JSONObject(prefs.getString("call_voices", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.optString(it) } }
        }.getOrDefault(emptyMap()),
        callSpeed = prefs.getFloat("call_speed", 1.0f),
        callCheckIns = prefs.getBoolean("call_check_ins", true),
        callCheckInSeconds = prefs.getInt("call_check_in_seconds", 30),
        callMakesThings = prefs.getBoolean("call_makes_things", true),
        callCaptions = prefs.getBoolean("call_captions", true),
        callPatience = prefs.getInt("call_patience", 600),
        callSpeaker = prefs.getBoolean("call_speaker", true),
    )

    fun save(s: Settings) = prefs.edit {
        putString("provider", s.providerId)
        s.apiKeys.forEach { (id, key) -> if (savedKeys[id] != key) putString("key_$id", SecretBox.seal(key)) }
        savedKeys = s.apiKeys
        s.models.forEach { (id, model) -> putString("model_$id", model) }
        putString("custom_base_url", s.customBaseUrl)
        putString("local_url", s.localUrl)
        putString("local_remote_url", s.localRemoteUrl)
        putString("local_lan6_url", s.localLan6Url)
        putString("local_fingerprint", s.localFingerprint)
        putString("persona", s.personaId)
        putString("custom_personas", personasToJson(s.customPersonas))
        putString("about_me", s.aboutMe)
        putString("last_create", s.lastCreate)
        putBoolean("web_search", s.webSearch)
        putBoolean("privacy_screen", s.privacyScreen)
        putString("theme", s.theme)
        s.thoughtsOpen?.let { putBoolean("thoughts_open", it) } ?: remove("thoughts_open")
        putString("call_voices", JSONObject(s.callVoices).toString())
        putFloat("call_speed", s.callSpeed)
        putBoolean("call_check_ins", s.callCheckIns)
        putInt("call_check_in_seconds", s.callCheckInSeconds)
        putBoolean("call_makes_things", s.callMakesThings)
        putBoolean("call_captions", s.callCaptions)
        putInt("call_patience", s.callPatience)
        putBoolean("call_speaker", s.callSpeaker)
    }

    private fun personasToJson(list: List<Persona>) = JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("instructions", it.instructions)
            .put("looks", it.looks).put("facePath", it.facePath).put("voicePath", it.voicePath).put("sendsOnItsOwn", it.sendsOnItsOwn)
            .put("voice", it.voice).put("adult", it.adult)
    }).toString()

    private fun personasFromJson(json: String) = JSONArray(json).objects().map {
        Persona(
            it.getString("id"), it.getString("name"), it.optString("instructions"),
            looks = it.optString("looks"),
            facePath = it.str("facePath"),
            voicePath = it.str("voicePath"),
            sendsOnItsOwn = it.optBoolean("sendsOnItsOwn"),
            voice = it.optString("voice"),
            adult = it.optBoolean("adult"),
        )
    }
}
