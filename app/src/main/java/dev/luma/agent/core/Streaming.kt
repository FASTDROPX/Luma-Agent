package dev.luma.agent.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Token counts and timings of one model reply. [firstTokenMs] is the time to the first streamed
 * token (prompt reading plus network), [contentMs] the time to the first answer token after any
 * reasoning. Both are 0 when the server answered without streaming.
 */
data class TokenStats(val inputTokens: Int, val outputTokens: Int, val firstTokenMs: Long, val totalMs: Long,
    val contentMs: Long = 0, val estimated: Boolean = false) {
    /** Prompt tokens per second, measured up to the first streamed token. */
    val inputPerSecond: Double? get() = if (inputTokens > 0 && firstTokenMs > 0) inputTokens * 1000.0 / firstTokenMs else null
    /** Generated tokens per second; without streaming the whole call time is used. */
    val outputPerSecond: Double? get() {
        val window = if (firstTokenMs > 0) totalMs - firstTokenMs else totalMs
        return if (outputTokens > 0 && window > 0) outputTokens * 1000.0 / window else null
    }
    /** How long the model reasoned before the answer started, when known. */
    val thinkingMs: Long get() = if (contentMs > firstTokenMs && firstTokenMs > 0) contentMs - firstTokenMs else 0
    fun json(): JSONObject = JSONObject().put("in", inputTokens).put("out", outputTokens).put("first", firstTokenMs)
        .put("total", totalMs).put("content", contentMs).put("est", estimated)
    companion object {
        fun parse(raw: String?): TokenStats? = runCatching {
            val j = JSONObject(raw ?: return null); if (!j.has("total")) return null
            TokenStats(j.optInt("in"), j.optInt("out"), j.optLong("first"), j.optLong("total"), j.optLong("content"), j.optBoolean("est"))
        }.getOrNull()
    }
}

data class Generation(val text: String, val reasoning: String = "", val stats: TokenStats? = null, val images: List<GeneratedImage> = emptyList())

/** A picture the model drew; bytes are kept as received and normalised only when saved to the chat. */
class GeneratedImage(val mime: String, val bytes: ByteArray) {
    val extension: String get() = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
    companion object {
        private const val MAX_BYTES = 16_000_000
        fun decode(mime: String, base64: String): GeneratedImage? {
            if (base64.isBlank() || !mime.startsWith("image/") || base64.length > MAX_BYTES * 4 / 3 + 8) return null
            val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(base64) }.getOrNull() ?: return null
            return if (bytes.isEmpty() || bytes.size > MAX_BYTES) null else GeneratedImage(mime.substringBefore(';'), bytes)
        }
        /** "data:image/png;base64,...." as OpenRouter returns it; remote URLs are never fetched. */
        fun fromDataUrl(url: String): GeneratedImage? {
            val match = Regex("^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL).find(url.trim()) ?: return null
            return decode(match.groupValues[1], match.groupValues[2])
        }
        /** Some models put a drawn picture into the text as ![](data:image/...); it becomes a real picture. */
        fun extractInline(text: String): Pair<String, List<GeneratedImage>> {
            if (!text.contains("data:image/")) return text to emptyList()
            val found = mutableListOf<GeneratedImage>()
            val clean = Regex("""!\[[^\]]*\]\((data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+)\)""").replace(text) { m ->
                fromDataUrl(m.groupValues[1].filterNot { it.isWhitespace() })?.let { found += it; "" } ?: m.value
            }
            return (if (found.isEmpty()) text else clean.replace(Regex("\n{3,}"), "\n\n").trim()) to found
        }
    }
}

internal enum class WireFormat { OPENAI, GEMINI, CLAUDE }

/** Rebuilds a server-sent event stream into the same JSON shape the non-streaming API returns. */
internal class StreamAssembler(private val format: WireFormat, private val startedNs: Long, private val clock: () -> Long = System::nanoTime) {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var firstNs = 0L
    private var contentNs = 0L
    private var finish = ""
    private var usage: JSONObject? = null
    private var claudeInput = -1
    private var claudeOutput = -1
    private var anyCandidate = false
    private var size = 0

    fun accept(data: String) {
        val payload = data.trim()
        if (payload.isEmpty() || payload == "[DONE]") return
        size += payload.length
        if (size > 8_000_000) throw UserError("Ответ сервиса слишком большой.")
        val j = runCatching { JSONObject(payload) }.getOrNull() ?: return
        j.optJSONObject("error")?.let { throw UserError("Провайдер прервал ответ: ${it.optString("message").take(200).ifBlank { "ошибка без описания" }}") }
        when (format) {
            WireFormat.OPENAI -> {
                val choice = j.optJSONArray("choices")?.optJSONObject(0)
                choice?.optJSONObject("delta")?.let { delta ->
                    thought(delta.optString("reasoning_content").ifEmpty { delta.optString("reasoning") })
                    answer(delta.optString("content"))
                }
                choice?.optString("finish_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
                j.optJSONObject("usage")?.let { usage = it }
            }
            WireFormat.GEMINI -> {
                val candidate = j.optJSONArray("candidates")?.optJSONObject(0)
                if (candidate != null) anyCandidate = true
                val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                for (i in 0 until (parts?.length() ?: 0)) {
                    val part = parts!!.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought")) thought(part.optString("text")) else answer(part.optString("text"))
                }
                candidate?.optString("finishReason")?.takeIf { it.isNotEmpty() }?.let { finish = it }
                j.optJSONObject("usageMetadata")?.let { usage = it }
            }
            WireFormat.CLAUDE -> when (j.optString("type")) {
                "message_start" -> j.optJSONObject("message")?.optJSONObject("usage")?.let { claudeInput = it.optInt("input_tokens", -1) }
                "content_block_start" -> j.optJSONObject("content_block")?.let { block ->
                    if (block.optString("type") == "thinking") thought(block.optString("thinking")) else answer(block.optString("text"))
                }
                "content_block_delta" -> j.optJSONObject("delta")?.let { delta ->
                    when (delta.optString("type")) { "thinking_delta" -> thought(delta.optString("thinking")); "text_delta" -> answer(delta.optString("text")) }
                }
                "message_delta" -> {
                    j.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
                    j.optJSONObject("usage")?.let { claudeOutput = it.optInt("output_tokens", -1) }
                }
            }
        }
    }
    private fun mark() { if (firstNs == 0L) firstNs = clock() }
    private fun thought(value: String) { if (value.isEmpty()) return; mark(); reasoning.append(value) }
    private fun answer(value: String) { if (value.isEmpty()) return; mark(); if (contentNs == 0L) contentNs = clock(); text.append(value) }

    /** The assembled reply plus a "_luma" timing block in milliseconds. */
    fun result(): JSONObject {
        val now = clock()
        fun ms(ns: Long) = if (ns == 0L) 0L else ((ns - startedNs) / 1_000_000).coerceAtLeast(1)
        val timing = JSONObject().put("firstMs", ms(firstNs)).put("contentMs", ms(contentNs)).put("totalMs", ((now - startedNs) / 1_000_000).coerceAtLeast(1))
        val root = when (format) {
            WireFormat.OPENAI -> JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", text.toString()).put("reasoning", reasoning.toString())).put("finish_reason", finish)))
                .apply { usage?.let { put("usage", it) } }
            WireFormat.GEMINI -> JSONObject().apply {
                if (anyCandidate) {
                    val parts = JSONArray()
                    if (reasoning.isNotEmpty()) parts.put(JSONObject().put("text", reasoning.toString()).put("thought", true))
                    parts.put(JSONObject().put("text", text.toString()))
                    put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", parts)).put("finishReason", finish)))
                }
                usage?.let { put("usageMetadata", it) }
            }
            WireFormat.CLAUDE -> JSONObject().put("stop_reason", finish).put("content", JSONArray().apply {
                if (reasoning.isNotEmpty()) put(JSONObject().put("type", "thinking").put("thinking", reasoning.toString()))
                put(JSONObject().put("type", "text").put("text", text.toString()))
            }).apply {
                if (claudeInput >= 0 || claudeOutput >= 0) put("usage", JSONObject().put("input_tokens", claudeInput.coerceAtLeast(0)).put("output_tokens", claudeOutput.coerceAtLeast(0)))
            }
        }
        return root.put("_luma", timing)
    }
}

internal object ReplyParts {
    /** Splits a `<think>` block (or a stray closing tag) that some local servers leave inside the answer. */
    fun splitThink(text: String): Pair<String, String> {
        val match = Regex("(?s)^\\s*<think>(.*?)</think>").find(text)
        if (match != null) return text.substring(match.range.last + 1).trim() to match.groupValues[1].trim()
        val stray = text.indexOf("</think>")
        if (stray >= 0 && !text.substring(0, stray).contains("<think>")) return text.substring(stray + 8).trim() to text.substring(0, stray).trim()
        return text to ""
    }
    fun stats(format: WireFormat, root: JSONObject, text: String, reasoning: String): TokenStats? {
        val timing = root.optJSONObject("_luma") ?: return null
        var input = -1; var output = -1
        when (format) {
            WireFormat.OPENAI -> root.optJSONObject("usage")?.let { input = it.optInt("prompt_tokens", -1); output = it.optInt("completion_tokens", -1) }
            WireFormat.GEMINI -> root.optJSONObject("usageMetadata")?.let {
                input = it.optInt("promptTokenCount", -1)
                output = it.optInt("candidatesTokenCount", -1).let { c -> if (c < 0) -1 else c + it.optInt("thoughtsTokenCount", 0) }
            }
            WireFormat.CLAUDE -> root.optJSONObject("usage")?.let { input = it.optInt("input_tokens", -1); output = it.optInt("output_tokens", -1) }
        }
        // Without usage data, about four characters make a token; the meter marks this as an estimate.
        val estimated = output < 0
        if (output < 0) output = ((text.length + reasoning.length) / 4).coerceAtLeast(if (text.isEmpty()) 0 else 1)
        return TokenStats(input.coerceAtLeast(0), output, timing.optLong("firstMs"), timing.optLong("totalMs"), timing.optLong("contentMs"), estimated)
    }
}

/** "0:42", "12:05", "1:02:33". */
fun formatElapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
/** Whole numbers above 10, one decimal below: "812", "38", "4.6". */
fun formatRate(value: Double?): String = when {
    value == null || !value.isFinite() -> "—"
    value >= 10 -> "%,d".format(java.util.Locale.ROOT, value.toLong()).replace(',', ' ')
    else -> "%.1f".format(java.util.Locale.ROOT, value)
}
