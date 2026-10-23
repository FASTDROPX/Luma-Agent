package dev.luma.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * How Luma searches the web for models: [mode] "auto" uses the provider's own search where it has one (Gemini, Claude,
 * OpenAI, OpenRouter) and Luma's otherwise; "luma" always uses Luma's. [engine] is the search service Luma asks.
 */
data class WebSearchSettings(val mode: String = "auto", val engine: String = DUCKDUCKGO, val tavilyKey: String = "", val braveKey: String = "",
    val searxng: String = "") {
    fun json(): JSONObject = JSONObject().put("mode", mode).put("engine", engine).put("tavilyKey", tavilyKey).put("braveKey", braveKey).put("searxng", searxng)
    /** Whether a reply with search switched on uses the provider's own search. */
    fun native(provider: Provider) = mode == "auto" && WebSearch.supported(provider)
    companion object {
        const val DUCKDUCKGO = "duckduckgo"; const val TAVILY = "tavily"; const val BRAVE = "brave"; const val SEARXNG = "searxng"
        val ENGINES = listOf(DUCKDUCKGO, TAVILY, BRAVE, SEARXNG)
        fun label(engine: String) = when (engine) { TAVILY -> "Tavily"; BRAVE -> "Brave Search"; SEARXNG -> "SearXNG"; else -> "DuckDuckGo" }
        fun parse(json: JSONObject?): WebSearchSettings {
            json ?: return WebSearchSettings()
            return WebSearchSettings(json.optString("mode", "auto").takeIf { it in listOf("auto", "luma") } ?: "auto",
                json.optString("engine", DUCKDUCKGO).takeIf { it in ENGINES } ?: DUCKDUCKGO,
                json.optString("tavilyKey").take(500), json.optString("braveKey").take(500), json.optString("searxng").take(500))
        }
    }
}

/** One search result. */
data class WebHit(val title: String, val url: String, val snippet: String)
/** The readable text of a web page. */
data class WebPage(val url: String, val title: String, val text: String, val truncated: Boolean)

/** A plain HTTP exchange; tests replace the network with their own [WebTools.Fetch]. */
data class WebRequest(val url: String, val headers: Map<String, String> = emptyMap(), val json: JSONObject? = null, val form: Map<String, String>? = null)
data class WebResponse(val code: Int, val contentType: String, val body: String, val location: String? = null)

/**
 * Web search and page reading done by Luma itself, for models whose API has no search of their own. Search goes to the
 * engine chosen in settings; pages are fetched over HTTPS only, never from this device or the local network, and come
 * back as plain text.
 */
class WebTools(private val settings: WebSearchSettings, private val fetch: Fetch = OkFetch) {
    fun interface Fetch { suspend fun send(request: WebRequest): WebResponse }

    suspend fun search(query: String, count: Int = 6): List<WebHit> {
        val q = query.trim().take(300).ifEmpty { throw UserError("Пустой поисковый запрос.") }
        val hits = when (settings.engine) {
            WebSearchSettings.TAVILY -> tavily(q, count)
            WebSearchSettings.BRAVE -> brave(q, count)
            WebSearchSettings.SEARXNG -> searxng(q, count)
            else -> duckDuckGo(q, count)
        }
        return hits.filter { WebSearch.safeUrl(it.url) != null }.distinctBy { it.url }.take(count)
    }

    private suspend fun tavily(q: String, count: Int): List<WebHit> {
        if (settings.tavilyKey.isBlank()) throw UserError("Добавьте ключ Tavily в «Настройки» → «Поиск в интернете».")
        val response = fetch.send(WebRequest("https://api.tavily.com/search", mapOf("Authorization" to "Bearer ${settings.tavilyKey}"),
            JSONObject().put("query", q).put("max_results", count).put("search_depth", "basic").put("include_answer", false)))
        val json = engineJson(response, "Tavily")
        return objects(json.optJSONArray("results")).map { WebHit(it.optString("title"), it.optString("url"), it.optString("content")) }
    }
    private suspend fun brave(q: String, count: Int): List<WebHit> {
        if (settings.braveKey.isBlank()) throw UserError("Добавьте ключ Brave Search в «Настройки» → «Поиск в интернете».")
        val url = "https://api.search.brave.com/res/v1/web/search?q=${encode(q)}&count=$count"
        val json = engineJson(fetch.send(WebRequest(url, mapOf("X-Subscription-Token" to settings.braveKey, "Accept" to "application/json"))), "Brave Search")
        return objects(json.optJSONObject("web")?.optJSONArray("results")).map { WebHit(Html.text(it.optString("title")), it.optString("url"), Html.text(it.optString("description"))) }
    }
    private suspend fun searxng(q: String, count: Int): List<WebHit> {
        val base = settings.searxng.ifBlank { throw UserError("Укажите адрес SearXNG в «Настройки» → «Поиск в интернете».") }.let(::validateEndpoint)
        val response = fetch.send(WebRequest(base + "search?q=${encode(q)}&format=json"))
        if (response.code == 403) throw UserError("SearXNG не отдаёт JSON. Добавьте json в search → formats в settings.yml и перезапустите его.")
        val json = engineJson(response, "SearXNG")
        return objects(json.optJSONArray("results")).take(count).map { WebHit(it.optString("title"), it.optString("url"), it.optString("content")) }
    }
    /** DuckDuckGo's HTML page; no key, but it may show a bot check after many requests. */
    private suspend fun duckDuckGo(q: String, count: Int): List<WebHit> {
        val response = fetch.send(WebRequest("https://html.duckduckgo.com/html/", mapOf("User-Agent" to USER_AGENT, "Accept-Language" to "ru,en;q=0.8"), form = mapOf("q" to q)))
        if (response.code == 202 || response.body.contains("anomaly-modal") || response.body.contains("challenge-form"))
            throw UserError("DuckDuckGo временно ограничил поиск (проверка на бота). Подождите пару минут или выберите другой поисковик в настройках.")
        if (response.code !in 200..299) throw UserError("DuckDuckGo не ответил (HTTP ${response.code}).")
        return DuckDuckGoPage.parse(response.body).take(count)
    }
    private fun engineJson(response: WebResponse, name: String): JSONObject {
        when (response.code) {
            in 200..299 -> {}
            401, 403 -> throw UserError("$name не принял ключ. Проверьте его в «Настройки» → «Поиск в интернете».")
            429 -> throw UserError("Исчерпан лимит запросов $name. Подождите или выберите другой поисковик.")
            else -> throw UserError("$name не ответил (HTTP ${response.code}).")
        }
        return runCatching { JSONObject(response.body) }.getOrElse { throw UserError("$name вернул ответ в неизвестном формате.") }
    }

    /** Reads a page as text. Only public HTTPS pages: never this phone, the home network or a non-web link. */
    suspend fun read(url: String, limit: Int = 12_000): WebPage {
        var current = WebSearch.safeUrl(url)?.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://") ?: throw UserError("Открываются только ссылки http и https.")
        repeat(5) {
            publicHost(current)
            val response = fetch.send(WebRequest(current, mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,text/plain;q=0.9", "Accept-Language" to "ru,en;q=0.8")))
            if (response.code in 300..399 && response.location != null) {
                current = (current.toHttpUrlOrNull()?.resolve(response.location)?.toString() ?: throw UserError("Страница перенаправила по неверному адресу."))
                    .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
                return@repeat
            }
            if (response.code !in 200..299) throw UserError("Страница не открылась (HTTP ${response.code}).")
            val type = response.contentType.lowercase()
            if (type.isNotEmpty() && !type.startsWith("text/html") && !type.startsWith("text/plain") && !type.startsWith("application/xhtml"))
                throw UserError("По ссылке не страница, а ${type.substringBefore(';')}.")
            val (title, text) = if (type.startsWith("text/plain")) "" to response.body else Html.page(response.body)
            val clean = text.trim()
            if (clean.isEmpty()) throw UserError("На странице нет текста: возможно, её содержимое рисует JavaScript.")
            return WebPage(current, title.ifBlank { WebSearch.host(current) }, clean.take(limit), clean.length > limit)
        }
        throw UserError("Слишком много перенаправлений.")
    }
    private suspend fun publicHost(url: String) {
        val host = url.toHttpUrlOrNull()?.host ?: throw UserError("Неверный адрес страницы.")
        if (isPrivateHost(host)) throw UserError("Страницы из домашней сети и с этого устройства не открываются.")
        if (fetch === OkFetch) withContext(Dispatchers.IO) {
            val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { throw UserError("Сайт $host не найден.") }
            if (addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || isPrivateHost(it.hostAddress ?: "") })
                throw UserError("Страницы из домашней сети и с этого устройства не открываются.")
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        private fun objects(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { array!!.optJSONObject(it) }
    }

    /** The real network: HTTPS (plain HTTP only to a local SearXNG), no automatic redirects, 3 MB at most. */
    object OkFetch : Fetch {
        private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).addInterceptor(localCleartextOnly).build()
        override suspend fun send(request: WebRequest): WebResponse {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            request.json?.let { builder.post(it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
            request.form?.let { form -> builder.post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()) }
            val call = client.newCall(builder.build())
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(if (e is CleartextBlocked) UserError("HTTP разрешён только для домашней сети и Tailscale.")
                            else UserError("Нет ответа от ${call.request().url.host}. Проверьте интернет."))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val value = response.use {
                                val body = it.body
                                val source = body?.source()
                                source?.request(3_000_001)
                                val bytes = source?.buffer?.let { buffer -> buffer.readByteArray(minOf(buffer.size, 3_000_000)) } ?: ByteArray(0)
                                val charset = body?.contentType()?.charset() ?: Charsets.UTF_8
                                WebResponse(it.code, body?.contentType()?.toString().orEmpty(), String(bytes, charset), it.header("Location"))
                            }
                            if (continuation.isActive) continuation.resume(value)
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(UserError("Соединение прервалось.")) }
                    }
                })
            }
        }
    }
}

/** Results of DuckDuckGo's HTML search page. */
internal object DuckDuckGoPage {
    private val link = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val snippet = Regex("""class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|td)>""", RegexOption.DOT_MATCHES_ALL)
    fun parse(html: String): List<WebHit> {
        val links = link.findAll(html).toList()
        return links.mapIndexedNotNull { i, match ->
            val end = links.getOrNull(i + 1)?.range?.first ?: html.length
            val block = html.substring(match.range.first, end)
            val href = Html.decodeEntities(match.groupValues[1])
            // Ads go through y.js; results through /l/?uddg=<the real address>.
            if (href.contains("duckduckgo.com/y.js")) return@mapIndexedNotNull null
            val real = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: href
            val url = if (real.startsWith("//")) "https:$real" else real
            WebHit(Html.text(match.groupValues[2]), url, snippet.find(block)?.groupValues?.get(1)?.let(Html::text).orEmpty())
        }
    }
}

/** Just enough HTML handling to turn a page into readable text without a browser. */
internal object Html {
    private val drop = Regex("""<(script|style|noscript|svg|template|iframe|head|title|nav|footer|aside|form|button|select)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val block = Regex("""</?(p|div|br|li|ul|ol|h[1-6]|tr|table|section|article|main|header|blockquote|pre|dd|dt|figcaption)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]+>")
    private val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val main = Regex("""<(article|main)\b[^>]*>(.*?)</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "laquo" to "«", "raquo" to "»", "hellip" to "…", "copy" to "©", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "bull" to "•", "middot" to "·")
    fun decodeEntities(value: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(value) { m ->
        val code = m.groupValues[1]
        when {
            code.startsWith("#x") || code.startsWith("#X") -> code.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it.coerceIn(0, 0x10FFFF))) } ?: m.value
            code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { String(Character.toChars(it.coerceIn(0, 0x10FFFF))) } ?: m.value
            else -> named[code.lowercase()] ?: m.value
        }
    }
    /** Inline HTML (a title or a snippet) as one line of text. */
    fun text(fragment: String): String = decodeEntities(tag.replace(fragment, "")).replace(Regex("\\s+"), " ").trim()
    /** Title and readable text of a page; the article or main part when it holds most of the text. */
    fun page(html: String): Pair<String, String> {
        val name = title.find(html)?.groupValues?.get(1)?.let(::text).orEmpty()
        val cleaned = drop.replace(comment.replace(html, ""), " ")
        val body = main.findAll(cleaned).map { it.groupValues[2] }.maxByOrNull { it.length }?.takeIf { flatten(it).length > 400 } ?: cleaned
        return name to flatten(body)
    }
    private fun flatten(html: String): String = decodeEntities(tag.replace(block.replace(html, "\n"), " "))
        .lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
