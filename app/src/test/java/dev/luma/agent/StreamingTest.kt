package dev.luma.agent

import dev.luma.agent.core.*
import dev.luma.agent.ui.MarkdownSegment
import dev.luma.agent.ui.splitMarkdownTables
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class StreamingTest {
    /** A fake clock in nanoseconds: start at 0, each tick() adds the given milliseconds. */
    private class Clock { var ns = 0L; fun at(ms: Long) { ns = ms * 1_000_000 } }

    @Test fun openAiStreamKeepsReasoningAnswerUsageAndTiming() {
        val clock = Clock()
        val s = StreamAssembler(WireFormat.OPENAI, 0) { clock.ns }
        clock.at(4000); s.accept(""" {"choices":[{"delta":{"reasoning":"Думаю"}}]}""")
        clock.at(6000); s.accept("""{"choices":[{"delta":{"content":"При"}}]}""")
        clock.at(7000); s.accept("""{"choices":[{"delta":{"content":"вет"},"finish_reason":"stop"}]}""")
        s.accept("""{"choices":[],"usage":{"prompt_tokens":3200,"completion_tokens":120}}""")
        s.accept("[DONE]")
        clock.at(10000)
        val root = s.result()
        assertEquals("Привет", root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals("Думаю", root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("reasoning"))
        val stats = ReplyParts.stats(WireFormat.OPENAI, root, "Привет", "Думаю")!!
        assertEquals(4000, stats.firstTokenMs); assertEquals(6000, stats.contentMs); assertEquals(10000, stats.totalMs)
        assertEquals(800.0, stats.inputPerSecond!!, .01)      // 3200 tokens before the first token at 4 s
        assertEquals(20.0, stats.outputPerSecond!!, .01)      // 120 tokens over the remaining 6 s
        assertEquals(2000, stats.thinkingMs); assertFalse(stats.estimated)
    }

    @Test fun geminiStreamSeparatesThoughtsAndAddsThoughtTokens() {
        val clock = Clock()
        val s = StreamAssembler(WireFormat.GEMINI, 0) { clock.ns }
        clock.at(500); s.accept("""{"candidates":[{"content":{"parts":[{"text":"план","thought":true}]}}]}""")
        clock.at(900); s.accept("""{"candidates":[{"content":{"parts":[{"text":"{\"type\":\"tool\",\"tool\":\"home\"}"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1000,"candidatesTokenCount":20,"thoughtsTokenCount":30}}""")
        clock.at(1500)
        val root = s.result()
        val parts = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        assertTrue(parts.getJSONObject(0).getBoolean("thought")); assertEquals("план", parts.getJSONObject(0).getString("text"))
        assertEquals("STOP", root.getJSONArray("candidates").getJSONObject(0).getString("finishReason"))
        val stats = ReplyParts.stats(WireFormat.GEMINI, root, "x", "план")!!
        assertEquals(50, stats.outputTokens); assertEquals(1000, stats.inputTokens)
    }

    @Test fun claudeEventsBuildTextThinkingAndUsage() {
        val clock = Clock()
        val s = StreamAssembler(WireFormat.CLAUDE, 0) { clock.ns }
        s.accept("""{"type":"message_start","message":{"usage":{"input_tokens":700}}}""")
        clock.at(300); s.accept("""{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"хм"}}""")
        clock.at(600); s.accept("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"Готово"}}""")
        s.accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":40}}""")
        clock.at(1300)
        val root = s.result()
        assertEquals("end_turn", root.getString("stop_reason"))
        assertEquals("Готово", root.getJSONArray("content").getJSONObject(1).getString("text"))
        val stats = ReplyParts.stats(WireFormat.CLAUDE, root, "Готово", "хм")!!
        assertEquals(700, stats.inputTokens); assertEquals(40, stats.outputTokens); assertEquals(300, stats.firstTokenMs)
    }

    @Test fun midStreamErrorIsReportedAndMissingUsageIsEstimated() {
        val s = StreamAssembler(WireFormat.OPENAI, 0) { 0L }
        try { s.accept("""{"error":{"message":"overloaded"}}"""); fail() } catch (e: UserError) { assertTrue(e.message.contains("overloaded")) }
        val root = JSONObject().put("_luma", JSONObject().put("firstMs", 100).put("totalMs", 1100))
        val stats = ReplyParts.stats(WireFormat.OPENAI, root, "a".repeat(400), "")!!
        assertTrue(stats.estimated); assertEquals(100, stats.outputTokens)
        assertNull(stats.inputPerSecond)
        assertNull(ReplyParts.stats(WireFormat.OPENAI, JSONObject(), "x", ""))   // fake transports carry no timing
    }

    @Test fun thinkTagsAreSplitFromTheAnswer() {
        assertEquals("ответ" to "мысли", ReplyParts.splitThink("<think>мысли</think>\nответ"))
        assertEquals("""{"a":1}""" to "", ReplyParts.splitThink("""</think>{"a":1}"""))
        assertEquals("обычный текст" to "", ReplyParts.splitThink("обычный текст"))
    }

    @Test fun generationReturnsReasoningAndPublishesStats() = runTest {
        val api = ProviderClient { JSONObject("""{"choices":[{"message":{"content":"ok","reasoning_content":"почему"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5},"_luma":{"firstMs":50,"contentMs":80,"totalMs":550}}""") }
        val result = api.generateDetailed(Provider.DEEPSEEK, Connection("k", "m"), listOf(Turn("user", "q")), "system")
        assertEquals("ok", result.text); assertEquals("почему", result.reasoning)
        assertEquals(10.0, api.stats.value!!.outputPerSecond!!, .01)
    }

    @Test fun requestsAskForStreamingAndUsage() = runTest {
        for (p in Provider.entries) {
            val api = ProviderClient { r ->
                assertTrue(p.name, r.stream)
                when (p) {
                    Provider.GEMINI -> assertTrue(r.url.endsWith(":streamGenerateContent?alt=sse"))
                    Provider.CLAUDE -> assertTrue(r.body!!.getBoolean("stream"))
                    else -> { assertTrue(r.body!!.getBoolean("stream")); assertTrue(r.body!!.getJSONObject("stream_options").getBoolean("include_usage")) }
                }
                JSONObject(when (p) { Provider.GEMINI -> """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""; Provider.CLAUDE -> """{"content":[{"type":"text","text":"ok"}]}"""; else -> """{"choices":[{"message":{"content":"ok"}}]}""" })
            }
            assertEquals("ok", api.generate(p, Connection("k", "gemini-2.5-flash", "http://127.0.0.1:1/v1/"), listOf(Turn("user", "q")), "s"))
        }
    }

    @Test fun localServerWithoutStreamingIsAskedAgainPlainly() = runTest {
        val seen = mutableListOf<Boolean>()
        val api = ProviderClient { r -> seen += r.stream; if (r.stream) throw ProviderClient.httpError(400); JSONObject("""{"choices":[{"message":{"content":"ok"}}]}""") }
        assertEquals("ok", api.generate(Provider.LOCAL, Connection("", "m", "http://127.0.0.1:1/v1/"), listOf(Turn("user", "q")), "s"))
        assertEquals(listOf(true, false), seen)
    }

    @Test fun timesAndRatesReadWell() {
        assertEquals("0:42", formatElapsed(42_000)); assertEquals("12:05", formatElapsed(725_000)); assertEquals("1:02:33", formatElapsed(3_753_000))
        assertEquals("1 234", formatRate(1234.6)); assertEquals("38", formatRate(38.2)); assertEquals("4.6", formatRate(4.62)); assertEquals("—", formatRate(null))
        assertEquals(stats(), TokenStats.parse(stats().json().toString())); assertNull(TokenStats.parse(""))
    }
    private fun stats() = TokenStats(1, 2, 3, 4, 5, true)

    @Test fun tablesAreSplitOutsideCodeWithAlignment() {
        val md = "Итог:\n\n| Вид | Значение |\n|:---|---:|\n| Орбиты | 144 |\n| Шар | **1** |\n\n```\n| не | таблица |\n|---|---|\n```\nконец"
        val segments = splitMarkdownTables(md)
        assertEquals(4, segments.size)
        val table = segments[1] as MarkdownSegment.Table
        assertEquals(listOf("Вид", "Значение"), table.header); assertEquals(listOf(-1, 1), table.align)
        assertEquals(listOf("Шар", "**1**"), table.rows[1])
        assertTrue((segments[2] as MarkdownSegment.Code).code.contains("| не | таблица |")); assertEquals("конец", (segments[3] as MarkdownSegment.Text).markdown)
        assertEquals(1, splitMarkdownTables("просто текст | с чертой").size)
    }

    @Test fun cursorParticlesStreamAlongTheFlightThenSwirlBackOntoOrbits() {
        val seeds = dev.luma.agent.ui.OrbPhysics(56).particles.map { CursorSeed(it.x, it.y, it.z, it.size, it.shade) }
        val field = CursorParticleField(seeds, 100f, 400f, 14f)
        repeat(30) { field.advance(1f / 60, 100f, 400f, 14f) }
        val restSpread = field.particles.maxOf { it.x } - field.particles.minOf { it.x }
        repeat(30) { field.advance(1f / 60, 100f + (it + 1) * 20f, 400f, 14f) }   // 1200 px/s to the right
        assertTrue(field.motion > .8f)
        val flightSpread = field.particles.maxOf { it.x } - field.particles.minOf { it.x }
        assertTrue("A flying cursor pulls a long stream: $flightSpread vs $restSpread", flightSpread > restSpread * 3)
        repeat(300) { field.advance(1f / 60, 700f, 400f, 14f) }
        assertTrue(field.motion < .01f)
        assertTrue(field.particles.all { val d = hypot(it.x - 700f, it.y - 400f); d < 30f && it.x.isFinite() })
        assertTrue(field.particles.any { abs(it.z) > 1f })   // back on tilted 3D orbits
    }
}
