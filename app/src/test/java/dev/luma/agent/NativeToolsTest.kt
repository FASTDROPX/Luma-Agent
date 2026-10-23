package dev.luma.agent

import dev.luma.agent.core.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Native function calling on all three wire formats, the shared registry and the gesture geometry. */
class NativeToolsTest {
    private val tools = ToolRegistry.functions()
    private val flashlightCall = ToolCall("call_7", "flashlight", JSONObject().put("state", "on"))
    private val history = listOf(Turn("user", "Включи фонарик"), Turn("assistant", "", toolCalls = listOf(flashlightCall)),
        Turn("tool", """{"status":"done","verified":true}""", image = "aW1n", toolCallId = "call_7", toolName = "flashlight"))
    private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }

    @Test fun openAiCompatibleRequestsCarrySchemasAndReplayTheCallById() = runTest {
        var body = JSONObject()
        val api = ProviderClient { r -> body = r.body!!; Replies.openAiCall("volume", JSONObject().put("stream", "ring").put("level", 30), "call_8") }
        val reply = api.generateDetailed(Provider.LOCAL, Connection(model = "qwen", endpoint = "http://127.0.0.1:11434/v1/"), history, "system", true, tools = tools)
        assertEquals("auto", body.getString("tool_choice"))
        val functions = objects(body.getJSONArray("tools")).map { it.getJSONObject("function") }
        assertEquals(ToolRegistry.names, functions.map { it.getString("name") }.toSet())
        assertTrue(functions.all { it.getJSONObject("parameters").getString("type") == "object" })
        val messages = objects(body.getJSONArray("messages"))
        assertEquals("call_7", messages.first { it.has("tool_calls") }.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("{\"state\":\"on\"}", messages.first { it.has("tool_calls") }.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        val toolMessage = messages.first { it.optString("role") == "tool" }
        assertEquals("call_7", toolMessage.getString("tool_call_id"))
        assertTrue(messages.last().getJSONArray("content").toString().contains("image_url")) // the screenshot follows as a user message
        assertFalse(body.has("response_format"))
        assertEquals(listOf(ToolCall("call_8", "volume", JSONObject().put("stream", "ring").put("level", 30)).name), reply.toolCalls.map { it.name })
        assertEquals(30, reply.toolCalls.single().args.getInt("level"))
    }

    @Test fun brokenArgumentsBecomeAValidationErrorInsteadOfACrash() = runTest {
        val api = ProviderClient { JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject()
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "c").put("function", JSONObject().put("name", "volume").put("arguments", "{level: 5"))))))) }
        val call = api.generateDetailed(Provider.OPENAI, Connection("k", "gpt-5"), listOf(Turn("user", "x")), "s", true, tools = tools).toolCalls.single()
        try { ToolRegistry.validate(call.name, call.args); fail() } catch (e: ToolFailure) { assertTrue(e.message.contains("некорректный JSON")) }
        val reply = AgentProtocol.fromCalls(listOf(call, call), "")
        assertEquals(1, reply.skipped)
    }

    @Test fun claudeGetsToolUseAndToolResultWithoutReplayedThinking() = runTest {
        var body = JSONObject()
        val api = ProviderClient { r -> body = r.body!!
            JSONObject().put("stop_reason", "tool_use").put("content", JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "думаю").put("signature", "sig"))
                .put(JSONObject().put("type", "tool_use").put("id", "toolu_1").put("name", "open_notifications").put("input", JSONObject()))) }
        val reply = api.generateDetailed(Provider.CLAUDE, Connection("k", "claude-opus-5-5", models = listOf(ModelInfo("claude-opus-5-5", outputLimit = 128_000))), history, "system", true, tools = tools)
        assertEquals("open_notifications", reply.toolCalls.single().name); assertEquals("toolu_1", reply.toolCalls.single().id)
        assertEquals(128_000, body.getInt("max_tokens"))
        assertTrue(objects(body.getJSONArray("tools")).all { it.getJSONObject("input_schema").getString("type") == "object" })
        val messages = objects(body.getJSONArray("messages"))
        assertEquals("tool_use", messages[1].getJSONArray("content").getJSONObject(0).getString("type"))
        val result = messages[2].getJSONArray("content").getJSONObject(0)
        assertEquals("user", messages[2].getString("role")); assertEquals("tool_result", result.getString("type")); assertEquals("call_7", result.getString("tool_use_id"))
        assertTrue(result.getJSONArray("content").toString().contains("\"type\":\"image\""))
        assertFalse(body.toString().contains("thinking"))
    }

    @Test fun geminiEchoesTheThoughtSignatureAndDropsJsonModeWithTools() = runTest {
        val bodies = mutableListOf<JSONObject>()
        val signed = JSONObject().put("functionCall", JSONObject().put("name", "media").put("args", JSONObject().put("action", "pause"))).put("thoughtSignature", "c2ln")
        val api = ProviderClient { r -> bodies += r.body!!; JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray().put(if (r.body!!.has("tools")) signed else JSONObject().put("text", "{}")))))) }
        val connection = Connection("k", "gemini-3-pro")
        val first = api.generateDetailed(Provider.GEMINI, connection, listOf(Turn("user", "Пауза")), "system", true, tools = tools)
        val call = first.toolCalls.single(); assertEquals("media", call.name); assertEquals("pause", call.args.getString("action"))
        val config = bodies[0].getJSONObject("generationConfig")
        assertFalse(config.has("responseMimeType")); assertFalse(config.has("maxOutputTokens"))
        assertFalse(bodies[0].getJSONArray("tools").toString().contains("additionalProperties"))
        assertEquals("AUTO", bodies[0].getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))
        api.generateDetailed(Provider.GEMINI, connection, listOf(Turn("user", "Пауза"), Turn("assistant", "", toolCalls = listOf(call)),
            Turn("tool", """{"status":"done"}""", toolCallId = call.id, toolName = "media")), "system", true, tools = tools)
        val contents = objects(bodies[1].getJSONArray("contents"))
        assertEquals("c2ln", contents[1].getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        assertEquals("done", contents[2].getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getJSONObject("response").getString("status"))
        // Without native tools the old JSON mode stays.
        api.generateDetailed(Provider.GEMINI, connection, listOf(Turn("user", "x")), "system", true)
        assertEquals("application/json", bodies[2].getJSONObject("generationConfig").getString("responseMimeType")); assertFalse(bodies[2].has("tools"))
    }

    @Test fun jsonModeRewritesEarlierNativeTurnsAsCommands() = runTest {
        var body = JSONObject()
        val api = ProviderClient { r -> body = r.body!!; Replies.openAiText("""{"type":"final","text":"ok"}""") }
        api.generateDetailed(Provider.LOCAL, Connection(model = "m", endpoint = "http://127.0.0.1:1/v1/"), history, "system", true)
        assertFalse(body.has("tools"))
        val messages = objects(body.getJSONArray("messages"))
        assertTrue(messages.none { it.optString("role") == "tool" || it.has("tool_calls") })
        assertTrue(messages[2].getString("content").contains("\"tool\":\"flashlight\""))
    }

    @Test fun streamedToolCallsAreAssembledForEveryFormat() {
        val openAi = StreamAssembler(WireFormat.OPENAI, 0, clock = { 5_000_000 })
        openAi.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"set_timer","arguments":"{\"sec"}}]}}]}""")
        openAi.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"onds\":60}"}}]},"finish_reason":"tool_calls"}]}""")
        val call = openAi.result().getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id")); assertEquals(60, JSONObject(call.getJSONObject("function").getString("arguments")).getInt("seconds"))

        val claude = StreamAssembler(WireFormat.CLAUDE, 0, clock = { 5_000_000 })
        claude.accept("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"brightness","input":{}}}""")
        claude.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"level\":"}}""")
        claude.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"70}"}}""")
        val block = objects(claude.result().getJSONArray("content")).first { it.getString("type") == "tool_use" }
        assertEquals(70, block.getJSONObject("input").getInt("level")); assertEquals("toolu_9", block.getString("id"))

        val gemini = StreamAssembler(WireFormat.GEMINI, 0, clock = { 5_000_000 })
        gemini.accept("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"home","args":{}},"thoughtSignature":"eA=="}]},"finishReason":"STOP"}]}""")
        val part = objects(gemini.result().getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")).first { it.has("functionCall") }
        assertEquals("eA==", part.getString("thoughtSignature"))
    }

    @Test fun registryValidatesTypesChoicesAndRangesAndStaysCompact() {
        assertEquals(ToolRegistry.specs.size, ToolRegistry.names.size)
        fun bad(name: String, args: JSONObject) { try { ToolRegistry.validate(name, args); fail("$name $args") } catch (e: ToolFailure) { assertEquals(ToolStatus.ERROR, e.status) } }
        bad("volume", JSONObject().put("level", 150)); bad("volume", JSONObject().put("level", "50")); bad("volume", JSONObject().put("level", 5.5))
        bad("toggle_setting", JSONObject().put("setting", "root").put("state", "on")); bad("toggle_setting", JSONObject().put("setting", "wifi"))
        bad("set_alarm", JSONObject().put("hour", 7).put("minute", 0).put("days", JSONArray(listOf("monday")))); bad("set_timer", JSONObject().put("seconds", 0))
        bad("flashlight", JSONObject().put("state", true)); bad("no_such_tool", JSONObject())
        ToolRegistry.validate("set_alarm", JSONObject().put("hour", 7).put("minute", 0).put("days", JSONArray(listOf("mon"))).put("snapshot", "ignored"))
        ToolRegistry.validate("open_notifications", JSONObject())
        val catalog = ToolRegistry.catalog()
        assertTrue("catalog ${catalog.length}", catalog.length < 4500)
        assertTrue(catalog.contains("open_notifications {}")); assertTrue(catalog.contains("flashlight {state:on|off|toggle|status}"))
        ToolRegistry.specs.forEach { spec -> assertEquals("object", spec.schema().getString("type")); assertFalse(spec.schema(gemini = true).has("additionalProperties")) }
        assertEquals("volume", (AgentProtocol.parse("""{"tool":"volume","args":{"level":3}}""") as AgentReply.Tool).name)
        try { AgentProtocol.parse("""{"tool":"shell","args":{}}"""); fail() } catch (e: UserError) { }
    }

    @Test fun standardSwipesComeFromTheScreenSizeAlone() {
        val shade = Gestures.standard("down", "top", null, 1080, 2400)
        assertEquals(540f, shade.x1); assertEquals(2f, shade.y1); assertEquals(2f + 2400 * .45f, shade.y2, .01f)
        val home = Gestures.standard("up", "bottom", "short", 1080, 2400)
        assertEquals(2398f, home.y1); assertTrue(home.y2 < home.y1)
        val left = Gestures.standard("left", null, "long", 1080, 2400)
        assertEquals(1200f, left.y1); assertTrue(left.x1 > left.x2); assertEquals(540f, (left.x1 + left.x2) / 2, .5f)
        for ((direction, edge) in listOf("up" to "top", "right" to "right", "sideways" to null))
            try { Gestures.standard(direction, edge, null, 1080, 2400); fail("$direction $edge") } catch (e: ToolFailure) { }
        listOf(shade, home, left).forEach { s -> listOf(s.x1, s.x2).forEach { assertTrue(it in 0f..1079f) }; listOf(s.y1, s.y2).forEach { assertTrue(it in 0f..2399f) } }
    }
}
