package dev.luma.agent.core

import org.json.JSONArray
import org.json.JSONObject

enum class Provider(val label: String, val base: String, val keyRequired: Boolean = true) {
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/"),
    OPENAI("OpenAI", "https://api.openai.com/v1/"),
    CLAUDE("Claude", "https://api.anthropic.com/v1/"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/"),
    LOCAL("Локальный сервер", "", false),
    DEVICE("На телефоне", "", false)
}
/**
 * [imageOutput]: the model can draw pictures in its reply. [outputLimit] is the provider's own maximum reply
 * length in tokens; 0 means the provider does not publish it.
 */
data class ModelInfo(val id: String, val name: String = id, val vision: Boolean = false, val outputLimit: Int = 0, val imageOutput: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("name", name).put("vision", vision).put("outputLimit", outputLimit).put("limitV", 2).put("imageOutput", imageOutput)
    companion object {
        /** Catalogs saved before 0.8 capped every limit at 8192; such a value is treated as unknown until the list is refreshed. */
        fun parse(j: JSONObject): ModelInfo {
            val stored = j.optInt("outputLimit", 0)
            val limit = if (j.has("limitV")) stored else if (stored in 1 until 8192) stored else 0
            return ModelInfo(j.optString("id"), j.optString("name"), j.optBoolean("vision"), limit.coerceIn(0, MAX_OUTPUT_TOKENS), j.optBoolean("imageOutput"))
        }
    }
}
/** Upper bound for a manual reply limit; real requests are also capped by the model's published limit. */
const val MAX_OUTPUT_TOKENS = 1_048_576
/** One native function call from the model. [raw] keeps provider data that must be sent back unchanged (Gemini thought signatures). */
data class ToolCall(val id: String, val name: String, val args: JSONObject, val raw: JSONObject? = null)
/**
 * Optional sampling stays off by default: reasoning models often reject explicit sampling.
 * [maxTokens] 0 is «Авто / максимум модели»: Luma adds no reply limit of its own.
 */
data class GenerationSettings(val maxTokens: Int = 0, val temperature: Float = -1f, val topP: Float = -1f,
    val contextTurns: Int = 20, val instruction: String = "") {
    fun sanitized() = copy(maxTokens = if (maxTokens <= 0) 0 else maxTokens.coerceIn(256,MAX_OUTPUT_TOKENS),
        temperature = if (!temperature.isFinite() || temperature < 0) -1f else temperature.coerceIn(0f,1f),
        topP = if (!topP.isFinite() || topP < 0) -1f else topP.coerceIn(.05f,1f),
        contextTurns = contextTurns.coerceIn(2,30), instruction = instruction.take(4000))
    fun json() = sanitized().let { JSONObject().put("maxTokens",it.maxTokens).put("temperature",it.temperature)
        .put("topP",it.topP).put("contextTurns",it.contextTurns).put("instruction",it.instruction) }
    companion object { fun parse(j: JSONObject) = GenerationSettings(j.optInt("maxTokens"), j.optDouble("temperature",-1.0).toFloat(),
        j.optDouble("topP",-1.0).toFloat(),j.optInt("contextTurns",20),j.optString("instruction")).sanitized() }
}
data class VisualSettings(val palette: String = "sky", val blackBackground: Boolean = false,
    val smoothInput: Boolean = true, val motionBlur: Boolean = false, val blurStrength: Float = .45f,
    val particleCount: Int = 144, val orbitSpeed: Float = 1f, val returnDelay: Float = 3f,
    val cursor: Boolean = true, val edgeGlow: Boolean = true, val glowIntensity: Float = .7f) {
    fun sanitized() = copy(palette = palette.takeIf { it in listOf("sky","lilac","mint","peach","violet","cobalt","rose","teal","citrus","orange","graphite") } ?: "sky",
        blurStrength = blurStrength.finiteClamp(.1f,1f,.45f), particleCount = particleCount.coerceIn(36,180),
        orbitSpeed = orbitSpeed.finiteClamp(.4f,2f,1f), returnDelay = returnDelay.finiteClamp(2f,6f,3f),
        glowIntensity = glowIntensity.finiteClamp(.2f,1f,.7f))
    fun json() = sanitized().let { JSONObject().put("palette",it.palette).put("blackBackground",it.blackBackground)
        .put("smoothInput",it.smoothInput).put("motionBlur",it.motionBlur).put("blurStrength",it.blurStrength)
        .put("particleCount",it.particleCount).put("orbitSpeed",it.orbitSpeed).put("returnDelay",it.returnDelay)
        .put("cursor",it.cursor).put("edgeGlow",it.edgeGlow).put("glowIntensity",it.glowIntensity) }
    companion object { fun parse(j: JSONObject) = VisualSettings(j.optString("palette","sky"),j.optBoolean("blackBackground"),
        j.optBoolean("smoothInput",true),j.optBoolean("motionBlur"),j.optDouble("blurStrength",.45).toFloat(),j.optInt("particleCount",144),
        j.optDouble("orbitSpeed",1.0).toFloat(),j.optDouble("returnDelay",3.0).toFloat(),j.optBoolean("cursor",true),
        j.optBoolean("edgeGlow",true),j.optDouble("glowIntensity",.7).toFloat()).sanitized() }
}
private fun Float.finiteClamp(low: Float,high: Float,fallback: Float) = if(isFinite()) coerceIn(low,high) else fallback

data class Connection(val key: String = "", val model: String = "", val endpoint: String = "", val models: List<ModelInfo> = emptyList(), val catalogAt: Long = 0,
    val parameters: Map<String,GenerationSettings> = emptyMap()) {
    fun generation() = (parameters[model] ?: GenerationSettings()).sanitized()
    fun json() = JSONObject().put("key", key).put("model", model).put("endpoint", endpoint).put("models", JSONArray(models.map { it.json() })).put("catalogAt", catalogAt)
        .put("parameters",JSONObject().also { j -> parameters.forEach { (id,p) -> j.put(id,p.json()) } })
}
data class AppSettings(
    val onboarded: Boolean = false, val provider: Provider = Provider.GEMINI,
    val connections: Map<Provider, Connection> = emptyMap(), val theme: String = "system",
    val dynamicColors: Boolean = true, val reducedMotion: Boolean = false, val panel: Boolean = true,
    val screenshots: Boolean = false, val confirmActions: Boolean = true, val folder: String = "",
    val visuals: VisualSettings = VisualSettings(), val maxSteps: Int = 20, val taskMinutes: Int = 8,
    val unlimitedAgent: Boolean = true, val blockedWords: String = "", val tokenMeter: Boolean = true,
    /** Size of chat text, 0.85..1.4 of the body style. */
    val chatTextScale: Float = 1f, val haptics: Boolean = true,
    /** Pause the agent after this many thousand tokens in one task; 0 = no limit. */
    val agentTokenLimit: Int = 0,
    /** "friendly" addresses the user as «ты», "formal" as «вы» — in the interface and in the model's replies. */
    val tone: String = "friendly",
    /** How the agent calls tools: "auto" (native function calling, JSON commands if the server rejects it), "native" or "json". */
    val toolCalling: String = "auto",
    /** Chat replies may search the web: through the provider's own search or through Luma ([search]). */
    val webSearch: Boolean = false,
    val search: WebSearchSettings = WebSearchSettings(),
    /** «Глубокие размышления»: the agent analyses a failed step and tries another way instead of repeating it. */
    val deepThinking: Boolean = false,
    val voice: VoiceSettings = VoiceSettings(),
    val localContext: Int = 8192, val localThreads: Int = 0
) {
    val formal: Boolean get() = tone == "formal"
    fun connection(p: Provider = provider) = connections[p] ?: Connection()
    fun withGeneration(p: Provider, model: String, change: (GenerationSettings) -> GenerationSettings): AppSettings {
        val c = connection(p)
        if(model.isBlank()) return this
        return copy(connections = connections + (p to c.copy(parameters = c.parameters + (model to change(c.parameters[model] ?: GenerationSettings()).sanitized()))))
    }
    fun json(): JSONObject {
        val config = JSONObject(); connections.forEach { (p, c) -> config.put(p.name, c.json()) }
        return JSONObject().put("version", 2).put("onboarded", onboarded).put("provider", provider.name)
            .put("connections", config).put("theme", theme).put("dynamicColors", dynamicColors)
            .put("reducedMotion", reducedMotion).put("panel", panel).put("screenshots", screenshots)
            .put("confirmActions", confirmActions).put("folder", folder).put("visuals",visuals.json())
            .put("maxSteps",maxSteps.coerceIn(5,30)).put("taskMinutes",taskMinutes.coerceIn(2,15)).put("unlimitedAgent", unlimitedAgent)
            .put("blockedWords", blockedWords.take(BlockedWords.MAX_LENGTH)).put("tokenMeter", tokenMeter)
            .put("chatTextScale", chatTextScale.toDouble()).put("haptics", haptics).put("agentTokenLimit", agentTokenLimit).put("tone", tone).put("toolCalling", toolCalling).put("webSearch", webSearch).put("search", search.json()).put("deepThinking", deepThinking).put("voice",voice.json()).put("localContext",localContext).put("localThreads",localThreads)
    }
    companion object {
        fun parse(json: JSONObject): AppSettings {
            val connections = Provider.entries.associateWith { provider ->
                val c = json.optJSONObject("connections")?.optJSONObject(provider.name) ?: JSONObject()
                val models = c.optJSONArray("models") ?: JSONArray()
                val parameters = c.optJSONObject("parameters") ?: JSONObject()
                Connection(c.optString("key"), c.optString("model"), c.optString("endpoint"),
                    (0 until models.length()).mapNotNull { i -> models.optJSONObject(i)?.let(ModelInfo::parse) }, c.optLong("catalogAt"),
                    parameters.keys().asSequence().take(1000).mapNotNull { id -> parameters.optJSONObject(id)?.let { id to GenerationSettings.parse(it) } }.toMap())
            }
            return AppSettings(json.optBoolean("onboarded"), runCatching { Provider.valueOf(json.optString("provider")) }.getOrDefault(Provider.GEMINI),
                connections, json.optString("theme", "system").takeIf { it in listOf("system","light","dark") } ?: "system", json.optBoolean("dynamicColors", true), json.optBoolean("reducedMotion"),
                json.optBoolean("panel", true), json.optBoolean("screenshots"), json.optBoolean("confirmActions", true), json.optString("folder"),
                VisualSettings.parse(json.optJSONObject("visuals") ?: JSONObject()),json.optInt("maxSteps",20).coerceIn(5,30),json.optInt("taskMinutes",8).coerceIn(2,15),json.optBoolean("unlimitedAgent",true),
                json.optString("blockedWords").take(BlockedWords.MAX_LENGTH), json.optBoolean("tokenMeter", true),
                json.optDouble("chatTextScale", 1.0).toFloat().let { if (it.isFinite()) it.coerceIn(.85f, 1.4f) else 1f }, json.optBoolean("haptics", true),
                json.optInt("agentTokenLimit", 0).coerceIn(0, 5000), json.optString("tone", "friendly").takeIf { it in listOf("friendly", "formal") } ?: "friendly",
                json.optString("toolCalling", "auto").takeIf { it in TOOL_CALLING_MODES } ?: "auto", json.optBoolean("webSearch"), WebSearchSettings.parse(json.optJSONObject("search")), json.optBoolean("deepThinking"), VoiceSettings.parse(json.optJSONObject("voice")),json.optInt("localContext",8192).coerceIn(2048,16384),json.optInt("localThreads",0).coerceIn(0,8))
        }
    }
}
val TOOL_CALLING_MODES = listOf("auto", "native", "json")
/** User-defined words that force a confirmation; there is no built-in list. */
object BlockedWords {
    const val MAX_LENGTH = 2000
    fun parse(raw: String): List<String> = raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(200)
    fun match(raw: String, text: String): String? = parse(raw).firstOrNull { text.contains(it, ignoreCase = true) }
}
open class UserError(override val message: String) : RuntimeException(message)
fun safeError(error: Throwable): String = if (error is UserError) error.message else "Не получилось выполнить запрос. Проверьте подключение и попробуйте ещё раз."
/**
 * One message for the model. An assistant turn may carry native [toolCalls]; a "tool" turn answers the call
 * [toolCallId] of [toolName]. Only the agent creates these; the chat history holds plain user/assistant turns.
 */
data class Turn(val role: String, val text: String, val image: String? = null, val attachments: List<AttachmentData> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(), val toolCallId: String? = null, val toolName: String? = null)
data class ChatMessage(val id: Long, val role: String, val text: String, val timestamp: Long, val attachments: List<Attachment> = emptyList(),
    val reasoning: String = "", val stats: TokenStats? = null, val failed: Boolean = false, val search: WebSearch? = null,
    val generatedFiles: List<GeneratedFile> = emptyList())
data class Conversation(val id: Long, val title: String, val updated: Long, val pinned: Boolean = false, val agent: Boolean = false)
data class Approval(val title: String, val details: String, val background: Boolean = false)
/** An access a tool needs that is off: the chat shows a «выдайте доступ» card, and the task continues once it is granted. */
data class PermissionNeed(val id: String, val title: String, val purpose: String, val runtime: List<String> = emptyList(), val fallback: String? = null)
data class RunState(val active: Boolean = false, val paused: Boolean = false, val stage: String = "", val step: Int = 0,
    val chatId: Long = 0, val approval: Approval? = null, val error: String? = null, val retryPending: Boolean = false,
    /** SystemClock.elapsedRealtime() when the task started; drives the run timer. */
    val startedAt: Long = 0,
    /** Steps of this task, newest last, for the expandable log in the agent card. */
    val log: List<StepLog> = emptyList(),
    /** Tokens (prompt + reply) this task has spent so far. */
    val tokens: Long = 0,
    /** The agent has started acting (its first tool); until then the chat shows a plain «thinking» line, not the agent card. */
    val acting: Boolean = false,
    /** The task controls the screen or leaves Luma: only then the cursor, glow, Stop pill and floating panel appear. */
    val screen: Boolean = false,
    /** An access the current step needs that is off; the chat shows a card to grant it. */
    val permission: PermissionNeed? = null, val systemAction: SystemToolAction? = null)
/** One agent step: what it did, how long the model and the action took, and whether it worked. */
data class StepLog(val number: Int, val label: String, val millis: Long, val ok: Boolean, val outputPerSecond: Double? = null)
fun formatSeconds(millis: Long): String = if (millis < 60_000) "%.1f с".format(java.util.Locale.ROOT, millis / 1000.0).replace('.', ',') else formatElapsed(millis)
