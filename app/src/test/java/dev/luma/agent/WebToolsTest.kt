package dev.luma.agent

import dev.luma.agent.core.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Luma's own web search for models without one: engines, page reading, and the search loop with tools or JSON text. */
class WebToolsTest {
    private val duckPage = """
        <div class="result results_links result--ad"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_domain=shop.example">Реклама</a></div>
        <div class="result results_links"><h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fru.wikipedia.org%2Fwiki%2FBrawl_Stars&amp;rut=abc">Brawl Stars &mdash; Википедия</a></h2>
        <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x"><b>Brawl Stars</b> &laquo;мобильная&raquo; игра от Supercell</a></div>
        <div class="result results_links"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fsupercell.com%2Fen%2Fgames%2Fbrawlstars%2F&amp;rut=def">Brawl Stars | Supercell</a>
        <a class="result__snippet">Официальный сайт</a></div>"""

    private class FakeNet(val answer: (WebRequest) -> WebResponse) : WebTools.Fetch {
        val requests = mutableListOf<WebRequest>()
        override suspend fun send(request: WebRequest): WebResponse { requests += request; return answer(request) }
    }

    @Test fun duckDuckGoResultsSkipAdsAndUnwrapRedirects() {
        val hits = DuckDuckGoPage.parse(duckPage)
        assertEquals(listOf("https://ru.wikipedia.org/wiki/Brawl_Stars", "https://supercell.com/en/games/brawlstars/"), hits.map { it.url })
        assertEquals("Brawl Stars — Википедия", hits[0].title)
        assertEquals("Brawl Stars «мобильная» игра от Supercell", hits[0].snippet)
    }

    @Test fun pagesBecomeReadableTextPreferringTheArticle() {
        val html = """<html><head><title>Курс &amp; новости</title><script>var x="мусор";</script></head><body>
            <nav>Меню сайта</nav><article><h1>Курс доллара</h1><p>Сегодня курс — 92,5&nbsp;₽.</p><p>${"Подробности. ".repeat(40)}</p></article>
            <footer>© 2026</footer></body></html>"""
        val (title, text) = Html.page(html)
        assertEquals("Курс & новости", title)
        assertTrue(text.startsWith("Курс доллара\nСегодня курс — 92,5 ₽."))
        assertFalse(text.contains("мусор")); assertFalse(text.contains("Меню сайта")); assertFalse(text.contains("© 2026"))
        assertEquals("A — B 😀", Html.decodeEntities("A &#8212; B &#x1F600;"))
    }

    @Test fun eachEngineGetsItsOwnRequestAndClearErrors() = runTest {
        val tavilyNet = FakeNet { WebResponse(200, "application/json", """{"results":[{"title":"T","url":"https://t.example","content":"c"}]}""") }
        assertEquals("https://t.example", WebTools(WebSearchSettings(engine = "tavily", tavilyKey = "tvly-1"), tavilyNet).search("q").single().url)
        tavilyNet.requests.single().let { assertEquals("Bearer tvly-1", it.headers["Authorization"]); assertEquals("q", it.json!!.getString("query")) }

        val braveNet = FakeNet { WebResponse(200, "application/json", """{"web":{"results":[{"title":"<strong>B</strong>","url":"https://b.example","description":"d &amp; e"}]}}""") }
        val brave = WebTools(WebSearchSettings(engine = "brave", braveKey = "BSA1"), braveNet).search("курс доллара").single()
        assertEquals("B", brave.title); assertEquals("d & e", brave.snippet)
        assertTrue(braveNet.requests.single().url.startsWith("https://api.search.brave.com/res/v1/web/search?q=%D0%BA"))
        assertEquals("BSA1", braveNet.requests.single().headers["X-Subscription-Token"])

        val searxNet = FakeNet { WebResponse(200, "application/json", """{"results":[{"title":"S","url":"https://s.example","content":"x"}]}""") }
        WebTools(WebSearchSettings(engine = "searxng", searxng = "http://100.101.102.103:8080"), searxNet).search("q")
        assertEquals("http://100.101.102.103:8080/search?q=q&format=json", searxNet.requests.single().url)

        val duckNet = FakeNet { WebResponse(200, "text/html", duckPage) }
        assertEquals(2, WebTools(WebSearchSettings(), duckNet).search("brawl stars").size)
        assertEquals(mapOf("q" to "brawl stars"), duckNet.requests.single().form)

        suspend fun error(settings: WebSearchSettings, response: WebResponse) = try { WebTools(settings, FakeNet { response }).search("q"); fail(); "" } catch (e: UserError) { e.message }
        assertTrue(error(WebSearchSettings(engine = "tavily", tavilyKey = "bad"), WebResponse(401, "", "")).contains("не принял ключ"))
        assertTrue(error(WebSearchSettings(engine = "tavily"), WebResponse(200, "", "")).contains("Добавьте ключ Tavily"))
        assertTrue(error(WebSearchSettings(engine = "searxng", searxng = "http://192.168.1.5:8080"), WebResponse(403, "", "")).contains("formats"))
        assertTrue(error(WebSearchSettings(), WebResponse(200, "text/html", "<div class=\"anomaly-modal\">")).contains("проверка на бота"))
    }

    @Test fun pagesOpenOnlyFromThePublicWebOverHttps() = runTest {
        val net = FakeNet { r -> when (r.url) {
            "https://short.example/a" -> WebResponse(301, "", "", "https://news.example/story")
            "https://news.example/story" -> WebResponse(200, "text/html; charset=utf-8", "<title>Новость</title><p>Текст новости.</p>")
            "https://files.example/app.apk" -> WebResponse(200, "application/vnd.android.package-archive", "PK")
            else -> WebResponse(404, "", "")
        } }
        val tools = WebTools(WebSearchSettings(), net)
        val page = tools.read("http://short.example/a")
        assertEquals("https://news.example/story", page.url); assertEquals("Новость", page.title); assertEquals("Текст новости.", page.text)
        assertEquals("https://short.example/a", net.requests.first().url) // http was upgraded to https
        for (bad in listOf("http://192.168.0.1/admin", "https://localhost:8080", "http://100.64.1.2/", "file:///sdcard/secret.txt", "intent://x"))
            try { tools.read(bad); fail(bad) } catch (_: UserError) {}
        try { tools.read("https://files.example/app.apk"); fail() } catch (e: UserError) { assertTrue(e.message.contains("не страница")) }
        assertEquals(3, net.requests.size) // nothing was requested for the refused addresses
    }

    @Test fun anyModelSearchesAndReadsThroughLumaWithNativeTools() = runTest {
        val bodies = mutableListOf<JSONObject>()
        val api = ProviderClient { r -> bodies += r.body!!; when (bodies.size) {
            1 -> Replies.openAiCall("web_search", JSONObject().put("query", "курс доллара ЦБ"), "call_1")
            2 -> Replies.openAiCall("read_page", JSONObject().put("url", "https://cbr.example/rates"), "call_2")
            else -> Replies.openAiText("Курс ЦБ — 92,5 ₽ ([ЦБ](https://cbr.example/rates)).")
        } }
        val net = FakeNet { r -> if (r.url.contains("duckduckgo")) WebResponse(200, "text/html",
            """<a class="result__a" href="https://cbr.example/rates">Курсы ЦБ</a><a class="result__snippet">Официальные курсы</a>
               <a class="result__a" href="https://bank.example/usd">Курс в банке</a>""")
            else WebResponse(200, "text/html", "<title>Курсы ЦБ</title><p>USD 92,5</p>") }
        val statuses = mutableListOf<String>()
        val reply = SearchingChat(api, WebTools(WebSearchSettings(), net)).answer(Provider.DEEPSEEK, Connection(key = "k", model = "deepseek-chat"),
            listOf(Turn("user", "Какой курс доллара?")), "system", "23 октября 2026") { statuses += it }
        assertEquals("Курс ЦБ — 92,5 ₽ ([ЦБ](https://cbr.example/rates)).", reply.text)
        val search = reply.search!!
        assertEquals(listOf("курс доллара ЦБ"), search.queries); assertEquals(2, search.found); assertEquals(1, search.read)
        assertTrue(statuses.contains("Ищу: «курс доллара ЦБ»")); assertTrue(statuses.contains("Читаю: cbr.example"))
        // The tools are offered natively, and each result goes back as a tool message.
        val names = (0 until bodies[0].getJSONArray("tools").length()).map { bodies[0].getJSONArray("tools").getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(listOf("web_search", "read_page"), names)
        assertTrue(bodies[0].getJSONArray("messages").getJSONObject(0).getString("content").contains("Сегодня 23 октября 2026"))
        val toolMessage = (0 until bodies[2].getJSONArray("messages").length()).map { bodies[2].getJSONArray("messages").getJSONObject(it) }.last { it.optString("role") == "tool" }
        assertEquals("call_2", toolMessage.getString("tool_call_id")); assertTrue(toolMessage.getString("content").contains("USD 92,5"))
    }

    @Test fun modelsWithoutFunctionCallingSearchThroughJsonText() = runTest {
        // A server without function calling answers HTTP 400 to any request with tools.
        val sent = mutableListOf<JSONObject>()
        val api = ProviderClient { r ->
            if (r.body!!.has("tools")) throw ProviderClient.httpError(400)
            sent += r.body!!
            if (sent.size == 1) Replies.openAiText("{\"tool\":\"web_search\",\"query\":\"Android 17\"}") else Replies.openAiText("Android 17 вышел в бете.")
        }
        val net = FakeNet { WebResponse(200, "text/html", """<a class="result__a" href="https://a.example/17">Android 17</a>""") }
        val reply = SearchingChat(api, WebTools(WebSearchSettings(), net)).answer(Provider.LOCAL, Connection(model = "llama", endpoint = "http://127.0.0.1:11434/v1/"),
            listOf(Turn("user", "Что нового в Android?")), "system", "сегодня")
        assertEquals("Android 17 вышел в бете.", reply.text); assertEquals(listOf("Android 17"), reply.search!!.queries)
        assertTrue(sent[0].getJSONArray("messages").getJSONObject(0).getString("content").contains("{\"tool\":\"web_search\""))
        assertTrue(sent[1].toString().contains("Результат web_search"))
    }

    @Test fun aModelThatKeepsSearchingIsStoppedAndMustAnswer() = runTest {
        var searches = 0
        val api = ProviderClient { r -> if (r.body!!.has("tools")) { searches++; Replies.openAiCall("web_search", JSONObject().put("query", "q$searches"), "call_$searches") }
            else Replies.openAiText("Ответ по найденному.") }
        val net = FakeNet { WebResponse(200, "text/html", "") }
        val reply = SearchingChat(api, WebTools(WebSearchSettings(), net)).answer(Provider.OPENAI, Connection(key = "k", model = "gpt-4.1"),
            listOf(Turn("user", "?")), "system", "сегодня")
        assertEquals("Ответ по найденному.", reply.text)
        assertEquals(SearchingChat.MAX_SEARCHES, net.requests.size) // later calls were refused without touching the network
    }

    @Test fun settingsChooseTheProvidersSearchOrLumas() {
        val auto = WebSearchSettings()
        assertTrue(auto.native(Provider.GEMINI)); assertFalse(auto.native(Provider.DEEPSEEK)); assertFalse(auto.native(Provider.LOCAL))
        assertFalse(auto.copy(mode = "luma").native(Provider.GEMINI))
        val custom = WebSearchSettings("luma", "brave", braveKey = "k", searxng = "http://pc.ts.net:8080/")
        assertEquals(custom, WebSearchSettings.parse(custom.json()))
        assertEquals(custom, AppSettings.parse(AppSettings(search = custom).json()).search)
        assertEquals(WebSearchSettings(), WebSearchSettings.parse(JSONObject().put("engine", "google").put("mode", "x")))
    }
}
