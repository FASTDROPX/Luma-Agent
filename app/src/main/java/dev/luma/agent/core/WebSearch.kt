package dev.luma.agent.core

import org.json.JSONArray
import org.json.JSONObject

/** A web page the model found while searching; [read] when it opened or cited the page in its answer. */
data class WebSource(val url: String, val title: String, val read: Boolean = false)

/**
 * What the model looked up on the web for one answer: its search queries and the pages it found and read. Each
 * provider reports this its own way (Gemini grounding, Claude's web_search blocks, OpenAI's Responses API, OpenRouter
 * citations); all become this one shape, stored with the answer and shown above it.
 */
data class WebSearch(val queries: List<String>, val sources: List<WebSource>) {
    val found: Int get() = sources.size
    val read: Int get() = sources.count { it.read }
    fun json(): JSONObject = JSONObject().put("queries", JSONArray(queries)).put("sources", JSONArray(sources.map {
        JSONObject().put("url", it.url).put("title", it.title).put("read", it.read)
    }))

    /** Collects queries and pages from pieces of a reply; the same page is kept once, and stays read once read. */
    class Builder {
        private val queries = LinkedHashSet<String>()
        private val sources = LinkedHashMap<String, WebSource>()
        var used = false; private set
        fun query(text: String?) {
            used = true
            text?.trim()?.take(300)?.takeIf { it.isNotEmpty() && queries.size < 20 }?.let(queries::add)
        }
        fun page(url: String?, title: String? = null, read: Boolean = false) {
            used = true
            val clean = safeUrl(url) ?: return
            val known = sources[clean]
            if (known == null && sources.size >= 60) return
            val name = title?.trim()?.take(200)?.takeIf { it.isNotEmpty() && it != "null" } ?: known?.title?.takeIf { it.isNotEmpty() } ?: host(clean)
            sources[clean] = WebSource(clean, name, read || known?.read == true)
        }
        /** Gemini's candidate.groundingMetadata: queries, grounding chunks, and the chunks that support the text. */
        fun gemini(meta: JSONObject?) {
            meta ?: return; used = true
            meta.optJSONArray("webSearchQueries")?.let { for (i in 0 until it.length()) query(it.optString(i)) }
            val chunks = meta.optJSONArray("groundingChunks") ?: JSONArray()
            val cited = mutableSetOf<Int>()
            meta.optJSONArray("groundingSupports")?.let { supports -> for (i in 0 until supports.length()) {
                supports.optJSONObject(i)?.optJSONArray("groundingChunkIndices")?.let { for (k in 0 until it.length()) cited += it.optInt(k, -1) }
            } }
            for (i in 0 until chunks.length()) chunks.optJSONObject(i)?.optJSONObject("web")?.let { web ->
                page(web.optString("uri"), web.optString("title"), read = cited.isEmpty() || i in cited)
            }
        }
        /** OpenAI-style url_citation annotations (OpenRouter's web plugin, Chat Completions search models). */
        fun annotations(list: JSONArray?) {
            list ?: return
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                if (item.optString("type") != "url_citation") continue
                val citation = item.optJSONObject("url_citation") ?: item
                page(citation.optString("url"), citation.optString("title"), read = true)
            }
        }
        /** One web_search_tool_result block of Claude: every result is a page found. */
        fun claudeResults(block: JSONObject) {
            used = true
            val content = block.optJSONArray("content") ?: return
            for (i in 0 until content.length()) content.optJSONObject(i)?.takeIf { it.optString("type") == "web_search_result" }?.let {
                page(it.optString("url"), it.optString("title"))
            }
        }
        /** A citation inside Claude's answer: the page was read. */
        fun claudeCitation(citation: JSONObject?) {
            citation?.takeIf { it.optString("type") == "web_search_result_location" }?.let { page(it.optString("url"), it.optString("title"), read = true) }
        }
        /** The output list of OpenAI's Responses API: searches with their sources, opened pages, and citations. */
        fun responses(output: JSONArray?) {
            output ?: return
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "web_search_call" -> {
                        used = true
                        val action = item.optJSONObject("action") ?: continue
                        when (action.optString("type")) {
                            "search" -> {
                                query(action.optString("query"))
                                action.optJSONArray("sources")?.let { for (k in 0 until it.length()) it.optJSONObject(k)?.let { s -> page(s.optString("url"), s.optString("title")) } }
                            }
                            "open_page", "find_in_page" -> page(action.optString("url"), read = true)
                        }
                    }
                    "message" -> item.optJSONArray("content")?.let { content -> for (k in 0 until content.length()) {
                        content.optJSONObject(k)?.optJSONArray("annotations")?.let { annotations(it) }
                    } }
                }
            }
        }
        fun build(): WebSearch? = if (!used) null else WebSearch(queries.toList(), sources.values.toList())
    }

    companion object {
        /** Providers whose API lets the model search the web itself. DeepSeek's API and local servers do not. */
        fun supported(provider: Provider): Boolean = provider in setOf(Provider.GEMINI, Provider.CLAUDE, Provider.OPENAI, Provider.OPENROUTER)
        fun parse(json: JSONObject?): WebSearch? {
            json ?: return null
            val queries = json.optJSONArray("queries")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            val sources = json.optJSONArray("sources")?.let { a -> (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { s -> safeUrl(s.optString("url"))?.let { WebSource(it, s.optString("title").ifBlank { host(it) }, s.optBoolean("read")) } }
            } }.orEmpty()
            return WebSearch(queries, sources)
        }
        /** Only plain http(s) links are kept; anything else could open another app or run script. */
        fun safeUrl(url: String?): String? {
            val clean = url?.trim()?.takeIf { it.length in 8..2000 } ?: return null
            return clean.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }?.takeIf { it.none(Char::isWhitespace) }
        }
        fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").take(100)
        /** "1 страница", "3 страницы", "12 страниц". */
        fun pages(count: Int): String {
            val tail = count % 100; val last = count % 10
            val word = if (tail in 11..14) "страниц" else when (last) { 1 -> "страница"; 2, 3, 4 -> "страницы"; else -> "страниц" }
            return "$count $word"
        }
    }
}
