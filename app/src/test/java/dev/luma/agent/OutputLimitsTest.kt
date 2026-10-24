package dev.luma.agent

import dev.luma.agent.core.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** «Авто / максимум модели»: Luma adds no reply limit of its own and never invents 8192. */
class OutputLimitsTest {
    private fun response(p: Provider) = when (p) {
        Provider.GEMINI -> JSONObject("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"ok"}]}}]}""")
        Provider.CLAUDE -> JSONObject("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        else -> JSONObject("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
    }
    private fun limitField(p: Provider, body: JSONObject): Any? = when (p) {
        Provider.GEMINI -> body.getJSONObject("generationConfig").opt("maxOutputTokens")
        Provider.CLAUDE -> body.opt("max_tokens")
        Provider.OPENAI -> body.opt("max_completion_tokens") ?: body.opt("max_tokens")
        else -> body.opt("max_tokens") ?: body.opt("max_completion_tokens")
    }
    private suspend fun sent(p: Provider, model: ModelInfo, manual: Int = 0, agent: Boolean = false): JSONObject {
        var body = JSONObject()
        val api = ProviderClient { r -> body = r.body!!; response(p) }
        api.generate(p, Connection("k", model.id, "http://127.0.0.1:11434/v1/", listOf(model), parameters = mapOf(model.id to GenerationSettings(manual))), listOf(Turn("user", "q")), "s", agent)
        return body
    }

    @Test fun autoSendsNoLimitWhereTheApiAllowsIt() = runTest {
        for (p in listOf(Provider.GEMINI, Provider.OPENAI, Provider.OPENROUTER, Provider.DEEPSEEK, Provider.LOCAL)) for (agent in listOf(false, true)) {
            val body = sent(p, ModelInfo("model", outputLimit = 65_536), agent = agent)
            assertNull("$p agent=$agent", limitField(p, body))
            assertFalse(body.toString().contains("8192"))
        }
        // Ollama's OpenAI-compatible endpoint gets no fields of its native API.
        val local = sent(Provider.LOCAL, ModelInfo("qwen3:8b"))
        listOf("options", "num_ctx", "num_predict", "keep_alive").forEach { assertFalse(it, local.has(it)) }
    }

    @Test fun claudeRequiresALimitAndGetsTheModelsOwnMaximum() = runTest {
        assertEquals(128_000, sent(Provider.CLAUDE, ModelInfo("claude-opus-5-5", outputLimit = 128_000)).getInt("max_tokens"))
        // An older catalog without the field falls back to the published limit of that model, never to 8192.
        assertEquals(128_000, sent(Provider.CLAUDE, ModelInfo("claude-sonnet-4-6")).getInt("max_tokens"))
        assertEquals(64_000, ProviderClient.claudeOutputLimit("claude-haiku-4-5-20251001"))
        assertEquals(32_000, ProviderClient.claudeOutputLimit("claude-opus-4-1-20250805"))
        assertEquals(32_000, ProviderClient.claudeOutputLimit("claude-opus-4-20250514"))
        assertEquals(64_000, ProviderClient.claudeOutputLimit("claude-sonnet-4-20250514"))
        assertEquals(8192, ProviderClient.claudeOutputLimit("claude-3-5-haiku-20241022"))
        assertEquals(4096, ProviderClient.claudeOutputLimit("claude-3-haiku-20240307"))
        assertEquals(ProviderClient.CLAUDE_FALLBACK_TOKENS, ProviderClient.claudeOutputLimit("claude-next"))
    }

    @Test fun manualLimitStaysAnExtraSettingWithinTheModelsMaximum() = runTest {
        for (p in Provider.entries.filter {it!=Provider.DEVICE}) {
            assertEquals("$p", 4096, (limitField(p, sent(p, ModelInfo("m", outputLimit = 65_536), manual = 4096)) as Number).toInt())
            assertEquals("$p cap", 2048, (limitField(p, sent(p, ModelInfo("m", outputLimit = 2048), manual = 4096)) as Number).toInt())
            assertEquals("$p unknown", 50_000, (limitField(p, sent(p, ModelInfo("m"), manual = 50_000)) as Number).toInt())
        }
        assertEquals(100_000, GenerationSettings(100_000).sanitized().maxTokens)
        assertEquals(MAX_OUTPUT_TOKENS, GenerationSettings(Int.MAX_VALUE).sanitized().maxTokens)
        assertEquals(0, GenerationSettings(-5).sanitized().maxTokens) // never a negative limit
        assertEquals(256, GenerationSettings(10).sanitized().maxTokens)
        assertNull(ProviderClient.outputLimit(Provider.OPENAI, "gpt-5", null, 0))
    }

    @Test fun catalogsKeepTheRealLimitsAndForgetTheOld8192Cap() = runTest {
        val gemini = ProviderClient { JSONObject().put("models", JSONArray().put(JSONObject().put("name", "models/gemini-2.5-pro").put("outputTokenLimit", 65_536)
            .put("supportedGenerationMethods", JSONArray().put("generateContent")))) }
        assertEquals(65_536, gemini.models(Provider.GEMINI, Connection("k")).single().outputLimit)
        val claude = ProviderClient { JSONObject().put("data", JSONArray().put(JSONObject().put("id", "claude-opus-5-5").put("max_tokens", 128_000))) }
        assertEquals(128_000, claude.models(Provider.CLAUDE, Connection("k")).single().outputLimit)
        var calls = 0
        val router = ProviderClient { calls++; if (calls == 1) JSONObject().put("data", JSONObject()) else JSONObject().put("data", JSONArray()
            .put(JSONObject().put("id", "a/known").put("top_provider", JSONObject().put("max_completion_tokens", 100_000)))
            .put(JSONObject().put("id", "b/unknown").put("top_provider", JSONObject().put("max_completion_tokens", JSONObject.NULL)))) }
        assertEquals(listOf(100_000, 0), router.models(Provider.OPENROUTER, Connection("k")).map { it.outputLimit })
        val local = ProviderClient { JSONObject().put("data", JSONArray().put(JSONObject().put("id", "qwen3:8b"))) }
        assertEquals(0, local.models(Provider.LOCAL, Connection(endpoint = "http://127.0.0.1:11434/v1/")).single().outputLimit)

        assertEquals(0, ModelInfo.parse(JSONObject().put("id", "old").put("outputLimit", 8192)).outputLimit)
        assertEquals(4096, ModelInfo.parse(JSONObject().put("id", "gpt-3.5").put("outputLimit", 4096)).outputLimit)
        assertEquals(8192, ModelInfo.parse(ModelInfo("real", outputLimit = 8192).json()).outputLimit)
        val stored = AppSettings(connections = mapOf(Provider.GEMINI to Connection("k", "g", models = listOf(ModelInfo("g", outputLimit = 65_536)),
            parameters = mapOf("g" to GenerationSettings(100_000)))), toolCalling = "json")
        val restored = AppSettings.parse(stored.json())
        assertEquals(65_536, restored.connection(Provider.GEMINI).models.single().outputLimit)
        assertEquals(100_000, restored.connection(Provider.GEMINI).generation().maxTokens)
        assertEquals("json", restored.toolCalling); assertEquals("auto", AppSettings.parse(JSONObject()).toolCalling)
        assertEquals(0, AppSettings.parse(JSONObject()).agentTokenLimit) // the task budget is a separate setting and stays as the user set it
    }
}
