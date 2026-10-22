package dev.luma.agent

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import dev.luma.agent.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FeaturesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private val png64 = Base64.getEncoder().encodeToString(png)

    @Test fun chatsCanBePinnedRenamedSearchedAndExported() = runBlocking {
        val chats = ChatStore(context)
        try {
            val first = chats.ensure("Рецепт борща"); chats.append(first, "user", "Как сварить борщ?"); chats.append(first, "assistant", "Нужна **свёкла**.")
            chats.select(null); val second = chats.ensure("Погода"); chats.append(second, "user", "Будет дождь?")
            chats.setPinned(first, true)
            assertEquals(first, chats.conversations.value.first().id); assertTrue(chats.conversations.value.first().pinned)
            chats.rename(second, "Прогноз на неделю")
            assertEquals("Прогноз на неделю", chats.conversations.value.first { it.id == second }.title)
            assertEquals(listOf(first), chats.search("СВЁКЛА").map { it.id })
            assertEquals(listOf(second), chats.search("прогноз").map { it.id })
            val md = chats.exportMarkdown(first)
            assertTrue(md.startsWith("# Рецепт борща")); assertTrue(md.contains("Как сварить борщ?")); assertTrue(md.contains("Нужна **свёкла**."))
        } finally { chats.close() }
    }

    @Test fun failedAnswersAreShownButNeverSentBackAndCanBeDeleted() = runBlocking {
        val chats = ChatStore(context)
        try {
            val id = chats.ensure("сбой"); chats.append(id, "user", "вопрос")
            val failed = chats.append(id, "assistant", "Нет ответа от сервиса.", failed = true)
            assertTrue(chats.messages.value.last().failed)
            assertEquals(listOf("вопрос"), chats.history(id).map { it.text })
            assertEquals("вопрос", chats.lastUserMessage(id)?.text)
            chats.deleteMessage(failed)
            assertEquals(listOf("вопрос"), chats.messages.value.map { it.text })
        } finally { chats.close() }
    }

    @Test fun tokenUsageAddsUpPerChatAndPerDay() = runBlocking {
        val chats = ChatStore(context)
        try {
            val a = chats.ensure("a"); chats.select(null); val b = chats.ensure("b")
            chats.addUsage(a, TokenStats(100, 20, 0, 1000)); chats.addUsage(a, TokenStats(50, 5, 0, 1000)); chats.addUsage(b, TokenStats(7, 3, 0, 10))
            chats.select(a)
            val usage = chats.usage.value
            assertEquals(150, usage.chatInput); assertEquals(25, usage.chatOutput)
            assertEquals(157, usage.dayInput); assertEquals(28, usage.dayOutput)
            chats.addUsage(a, null)
            assertEquals(usage, chats.usage.value)
        } finally { chats.close() }
    }

    @Test fun generatedPicturesAreDecodedFromEveryShapeTheProvidersUse() {
        assertArrayEquals(png, GeneratedImage.fromDataUrl("data:image/png;base64,$png64")!!.bytes)
        assertNull(GeneratedImage.fromDataUrl("https://example.com/cat.png"))
        assertNull(GeneratedImage.decode("text/html", png64))
        assertEquals("jpg", GeneratedImage.decode("image/jpeg", png64)!!.extension)
        val (text, found) = GeneratedImage.extractInline("Готово:\n\n![кот](data:image/png;base64,$png64)\n\nНравится?")
        assertEquals(1, found.size); assertFalse(text.contains("base64")); assertTrue(text.contains("Нравится?"))
        assertEquals("обычный ответ" to emptyList<GeneratedImage>(), GeneratedImage.extractInline("обычный ответ"))
    }

    @Test fun imageModelsAskForPicturesAndKeepThem() = runTest {
        val requests = mutableListOf<JSONObject>()
        val api = ProviderClient { r ->
            requests += r.body!!
            when {
                r.url.contains("generateContent") -> JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts",
                    JSONArray().put(JSONObject().put("text", "Вот кот")).put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/png").put("data", png64)))))))
                r.url.contains("images/generations") -> JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", png64)))
                else -> JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "")
                    .put("images", JSONArray().put(JSONObject().put("image_url", JSONObject().put("url", "data:image/png;base64,$png64")))))))
            }
        }
        val turns = listOf(Turn("user", "нарисуй кота"))
        val gemini = api.generateDetailed(Provider.GEMINI, Connection("k", "gemini-2.5-flash-image", models = listOf(ModelInfo("gemini-2.5-flash-image", imageOutput = true))), turns, "s")
        assertEquals("Вот кот", gemini.text); assertArrayEquals(png, gemini.images.single().bytes)
        assertEquals("IMAGE", requests.last().getJSONObject("generationConfig").getJSONArray("responseModalities").getString(1))
        val router = api.generateDetailed(Provider.OPENROUTER, Connection("k", "google/gemini-image", models = listOf(ModelInfo("google/gemini-image", imageOutput = true))), turns, "s")
        assertEquals("", router.text); assertEquals(1, router.images.size)
        assertEquals("image", requests.last().getJSONArray("modalities").getString(0)); assertFalse(requests.last().optBoolean("stream"))
        val openai = api.generateDetailed(Provider.OPENAI, Connection("k", "gpt-image-1", models = listOf(ModelInfo("gpt-image-1", imageOutput = true))), turns, "s")
        assertEquals(1, openai.images.size); assertEquals("нарисуй кота", requests.last().getString("prompt"))
    }

    @Test fun codeFencesBecomeBlocksWithLanguageAndExtension() {
        val segments = splitMarkdownTables("Пример:\n\n```kotlin\nfun main() = println(\"hi\")\n```\n\nИ ещё:\n~~~\nplain\n~~~\n```py\nunclosed")
        val code = segments.filterIsInstance<MarkdownSegment.Code>()
        assertEquals(listOf("kotlin", "", "py"), code.map { it.language })
        assertEquals("fun main() = println(\"hi\")", code[0].code); assertEquals("unclosed", code[2].code)
        assertEquals("kt", SyntaxHighlighter.language("kotlin").extension); assertEquals("Kotlin", SyntaxHighlighter.language("KT").label)
        assertEquals("py", SyntaxHighlighter.language("python").extension); assertEquals("txt", SyntaxHighlighter.language("").extension)
        assertEquals("zig", SyntaxHighlighter.language("zig").extension)
    }

    @Test fun highlighterColoursTokensByLanguageAndKeepsTheText() {
        val p = CodePalette.of(dark = false, plain = Color.Black)
        val kotlin = "// note\nval name = \"Luma\" + 42\nfun launch() = Color.Red"
        val k = SyntaxHighlighter.highlight(kotlin, SyntaxHighlighter.language("kotlin"), p)
        assertEquals(kotlin, k.text)
        fun colourAt(s: androidx.compose.ui.text.AnnotatedString, part: String) = s.spanStyles.firstOrNull { it.start <= s.text.indexOf(part) && it.end > s.text.indexOf(part) }?.item?.color
        assertEquals(p.comment, colourAt(k, "note")); assertEquals(p.keyword, colourAt(k, "val")); assertEquals(p.string, colourAt(k, "Luma"))
        assertEquals(p.number, colourAt(k, "42")); assertEquals(p.function, colourAt(k, "launch")); assertEquals(p.type, colourAt(k, "Color"))
        val json = SyntaxHighlighter.highlight("{\"key\": \"value\", \"n\": true}", SyntaxHighlighter.language("json"), p)
        assertEquals(p.type, colourAt(json, "key")); assertEquals(p.string, colourAt(json, "value")); assertEquals(p.keyword, colourAt(json, "true"))
        val html = SyntaxHighlighter.highlight("<a href=\"x\">hi</a>", SyntaxHighlighter.language("html"), p)
        assertEquals(p.tag, colourAt(html, "<a")); assertEquals(p.function, colourAt(html, "href"))
        val big = "x".repeat(70_000)
        assertTrue(SyntaxHighlighter.highlight(big, SyntaxHighlighter.language("kotlin"), p).spanStyles.isEmpty())
    }

    @Test fun toneRewritesWholeWordsBothWays() {
        assertEquals("Выбери модель в разделе «Подключения».", Tone.adapt("Выберите модель в разделе «Подключения».", false))
        assertEquals("Сохраните ключ, затем выберите модель.", Tone.adapt("Сохрани ключ, затем выбери модель.", true))
        assertEquals("Проверь права ключа и доступность сервиса в твоём регионе.", Tone.adapt("Проверьте права ключа и доступность сервиса в вашем регионе.", false))
        assertEquals("Агент действует за тебя", Tone.adapt("Агент действует за вас", false))
        assertEquals("Выбранная папка изменилась.", Tone.adapt("Выбранная папка изменилась.", false))
        assertEquals("Ты Luma", Tone.adapt("Ты Luma", true))
    }
}
