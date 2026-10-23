package dev.luma.agent

import dev.luma.agent.core.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Web search in chat replies: the request for each provider, and queries, pages found and pages read in the answer. */
class WebSearchTest {
    private val question = listOf(Turn("user", "Какая погода в Москве?"))
    private fun objects(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }

    @Test fun geminiGroundsWithGoogleSearchAndReportsQueriesAndPages() = runTest {
        var wire: WireRequest? = null
        val stream = StreamAssembler(WireFormat.GEMINI, 0, clock = { 5_000_000 })
        stream.accept("""{"candidates":[{"content":{"parts":[{"text":"В Москве +5 °C"}]}}]}""")
        stream.accept("""{"candidates":[{"content":{"parts":[{"text":", облачно."}]},"finishReason":"STOP","groundingMetadata":{
            "webSearchQueries":["погода Москва сегодня"],
            "groundingChunks":[{"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/a","title":"gismeteo.ru"}},
                               {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/b","title":"yandex.ru"}},
                               {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/c","title":"meteoinfo.ru"}}],
            "groundingSupports":[{"segment":{"endIndex":14},"groundingChunkIndices":[0,2]}]}}]}""")
        val api = ProviderClient { r -> wire = r; stream.result() }
        val reply = api.generateDetailed(Provider.GEMINI, Connection(key = "k", model = "gemini-2.5-flash"), question, "system", webSearch = true)
        assertTrue(wire!!.body!!.getJSONArray("tools").getJSONObject(0).has("google_search"))
        assertTrue(wire!!.slow)
        assertEquals("В Москве +5 °C, облачно.", reply.text)
        val search = reply.search!!
        assertEquals(listOf("погода Москва сегодня"), search.queries)
        assertEquals(3, search.found); assertEquals(2, search.read)
        assertEquals(listOf("gismeteo.ru", "yandex.ru", "meteoinfo.ru"), search.sources.map { it.title })
    }

    @Test fun claudeUsesItsWebSearchToolAndCountsResultsAndCitations() = runTest {
        var body = JSONObject()
        val s = StreamAssembler(WireFormat.CLAUDE, 0, clock = { 5_000_000 })
        s.accept("""{"type":"message_start","message":{"usage":{"input_tokens":20}}}""")
        s.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Ищу. "}}""")
        s.accept("""{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""")
        s.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"курс "}}""")
        s.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"доллара\"}"}}""")
        s.accept("""{"type":"content_block_stop","index":1}""")
        s.accept("""{"type":"content_block_start","index":2,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
            {"type":"web_search_result","url":"https://cbr.ru/currency","title":"Банк России","encrypted_content":"x"},
            {"type":"web_search_result","url":"https://www.rbc.ru/quote","title":"РБК","encrypted_content":"y"},
            {"type":"web_search_result","url":"javascript:alert(1)","title":"bad"}]}}""")
        s.accept("""{"type":"content_block_start","index":3,"content_block":{"type":"text","text":"","citations":[]}}""")
        s.accept("""{"type":"content_block_delta","index":3,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://cbr.ru/currency","title":"Банк России","cited_text":"92,5"}}}""")
        s.accept("""{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"Курс — 92,5 ₽."}}""")
        s.accept("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":40}}""")
        val api = ProviderClient { r -> body = r.body!!; s.result() }
        val reply = api.generateDetailed(Provider.CLAUDE, Connection(key = "k", model = "claude-sonnet-4-5"), question, "system", webSearch = true)
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search_20250305", tool.getString("type")); assertEquals("web_search", tool.getString("name")); assertEquals(5, tool.getInt("max_uses"))
        assertEquals("Ищу. Курс — 92,5 ₽.", reply.text)
        val search = reply.search!!
        assertEquals(listOf("курс доллара"), search.queries)
        assertEquals(listOf("https://cbr.ru/currency", "https://www.rbc.ru/quote"), search.sources.map { it.url })
        assertEquals(2, search.found); assertEquals(1, search.read)
    }

    @Test fun openRouterAddsTheWebPluginAndReadsUrlCitations() = runTest {
        var body = JSONObject()
        val s = StreamAssembler(WireFormat.OPENAI, 0, clock = { 5_000_000 })
        s.accept("""{"choices":[{"delta":{"content":"Ответ [1]"}}]}""")
        s.accept("""{"choices":[{"delta":{"annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com/a","title":"A"}},
            {"type":"url_citation","url_citation":{"url":"https://example.com/b","title":"B"}}]},"finish_reason":"stop"}]}""")
        val api = ProviderClient { r -> body = r.body!!; s.result() }
        val reply = api.generateDetailed(Provider.OPENROUTER, Connection(key = "k", model = "openai/gpt-4.1"), question, "system", webSearch = true)
        assertEquals("web", body.getJSONArray("plugins").getJSONObject(0).getString("id"))
        assertEquals(2, reply.search!!.found); assertEquals(2, reply.search!!.read)
        assertEquals("Ответ [1]", reply.text)
    }

    @Test fun openAiSearchesThroughTheResponsesApi() = runTest {
        var wire: WireRequest? = null
        val api = ProviderClient { r -> wire = r; JSONObject("""{"status":"completed","output":[
            {"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"новости Android 17",
                "sources":[{"type":"url","url":"https://developer.android.com/about/versions/17"},{"type":"url","url":"https://9to5google.com/android-17"},{"type":"url","url":"https://www.theverge.com/android"}]}},
            {"type":"web_search_call","id":"ws_2","status":"completed","action":{"type":"open_page","url":"https://developer.android.com/about/versions/17"}},
            {"type":"reasoning","summary":[{"type":"summary_text","text":"Сверил источники."}]},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Вышла бета Android 17.",
                "annotations":[{"type":"url_citation","url":"https://9to5google.com/android-17","title":"9to5Google"}]}]}],
            "usage":{"input_tokens":120,"output_tokens":30},"_luma":{"totalMs":4200}}""") }
        val history = question + Turn("assistant", "Ранее.") + Turn("user", "А что нового в Android?")
        val reply = api.generateDetailed(Provider.OPENAI, Connection(key = "k", model = "gpt-5"), history, "system", webSearch = true)
        assertTrue(wire!!.url.endsWith("/v1/responses")); assertTrue(wire!!.slow)
        val body = wire!!.body!!
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("web_search_call.action.sources", body.getJSONArray("include").getString(0))
        assertEquals("system", body.getString("instructions"))
        val input = objects(body.getJSONArray("input"))
        assertEquals(listOf("user", "assistant", "user"), input.map { it.getString("role") })
        assertEquals("output_text", input[1].getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("Вышла бета Android 17.", reply.text); assertEquals("Сверил источники.", reply.reasoning)
        val search = reply.search!!
        assertEquals(listOf("новости Android 17"), search.queries)
        assertEquals(3, search.found); assertEquals(2, search.read)
        assertEquals(30, reply.stats!!.outputTokens)
    }

    @Test fun withoutTheSwitchOrWithoutProviderSupportNothingChanges() = runTest {
        var body = JSONObject()
        val api = ProviderClient { r -> body = r.body!!; Replies.openAiText("Привет") }
        val off = api.generateDetailed(Provider.OPENROUTER, Connection(key = "k", model = "m"), question, "system")
        assertFalse(body.has("plugins")); assertNull(off.search)
        val deepSeek = api.generateDetailed(Provider.DEEPSEEK, Connection(key = "k", model = "deepseek-chat"), question, "system", webSearch = true)
        assertFalse(body.has("plugins")); assertFalse(body.has("tools")); assertNull(deepSeek.search)
        assertFalse(WebSearch.supported(Provider.DEEPSEEK)); assertFalse(WebSearch.supported(Provider.LOCAL))
        // The agent keeps its own tools; search is for chat replies.
        var geminiBody = JSONObject()
        val gemini = ProviderClient { r -> geminiBody = r.body!!; Replies.geminiText("{\"type\":\"final\",\"text\":\"ok\"}") }
        gemini.generateDetailed(Provider.GEMINI, Connection(key = "k", model = "gemini-2.5-flash"), question, "system", agent = true, webSearch = true)
        assertFalse(geminiBody.has("tools"))
    }

    @Test fun searchRoundTripsAndKeepsOnlyWebLinks() {
        val search = WebSearch.Builder().apply {
            query("a"); page("https://a.example/x", "A"); page("https://a.example/x", null, read = true); page("intent://evil"); page("ftp://x.y/z")
        }.build()!!
        assertEquals(1, search.found); assertEquals(1, search.read); assertEquals("A", search.sources.single().title)
        assertEquals(search, WebSearch.parse(search.json()))
        assertEquals(listOf("1 страница", "3 страницы", "12 страниц", "21 страница", "111 страниц"), listOf(1, 3, 12, 21, 111).map(WebSearch::pages))
        assertEquals("example.com", WebSearch.host("https://www.example.com/path?q=1"))
        assertNull(WebSearch.Builder().build())
    }
}
