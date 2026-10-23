package dev.luma.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** «Глубокие размышления»: a failed or repeated step is analysed, and the same step on the same screen is not run again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeepThinkingTest {
    private lateinit var context: Application
    @Before fun setup() { TestKeys.install(); context = ApplicationProvider.getApplicationContext(); File(context.noBackupFilesDir, "luma-vault.bin").delete(); File(context.noBackupFilesDir, "conversations.db").delete() }
    @After fun teardown() { TestKeys.restore() }

    /** Telegram's chat: tapping the message with a link opens the message menu; tapping elsewhere closes it. */
    private class TelegramChat : ScreenDriver {
        var menu = false
        val executed = mutableListOf<String>()
        private fun node(id: Int, text: String, clickable: Boolean = true) = JSONObject().put("id", id).put("text", text).put("clickable", clickable)
            .put("bounds", JSONArray(listOf(40, 300 + id * 100, 680, 380 + id * 100))).apply { ScreenLinks.find(text).takeIf { it.isNotEmpty() }?.let { put("links", JSONArray(it)) } }
        override suspend fun observe(withImage: Boolean, grid: Int) = ScreenResult(JSONObject().put("package", "org.telegram.messenger").put("width", 720).put("height", 1600)
            .put("nodes", if (menu) JSONArray(listOf(node(1, "Ответить"), node(2, "Копировать"), node(3, "Переслать")))
                else JSONArray(listOf(node(1, "Админка: http://192.168.1.38:8780/ — откройте в браузере"), node(2, "Избранное", false)))))
        override suspend fun execute(name: String, args: JSONObject): JSONObject {
            executed += "$name ${args.optInt("node", -1)}"
            menu = when (name) { "tap" -> !menu && args.optInt("node") == 1; else -> false }
            return JSONObject().put("status", ToolStatus.DONE).put("verified", true)
        }
        override fun sensitive(name: String, args: JSONObject, blockedWords: String): String? = null
        override fun actionLabel(name: String, args: JSONObject) = "$name ${args.optInt("node", -1)}"
    }

    private fun analysis(request: WireRequest) = request.bodyText.contains("разбираешь неудачную попытку")
    /** Request text with JSON escaping undone, so nested strings can be searched as written. */
    private fun plain(request: WireRequest): String {
        // Working notes are JSON inside a message inside the request JSON: undo the escaping of every level.
        var text = request.bodyText
        repeat(3) { text = text.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\") }
        return text
    }

    @Test fun aRepeatedTapOnTheSameScreenIsAnalysedInsteadOfRepeated() {
        val screen = TelegramChat()
        var steps = 0
        val agent = AgentRun(context, screen = screen, configure = { it.copy(confirmActions = false, deepThinking = true) }) { _, request ->
            if (analysis(request)) Replies.openAiText("""{"why":"Нажатие на сообщение в Telegram открывает его меню, а не ссылку.","options":["open_url со ссылкой из links","long_press → Копировать"],"next":"open_url http://192.168.1.38:8780/"}""")
            else when (steps++) {
                0 -> Replies.openAiCall("screen", JSONObject(), "c0")
                1 -> Replies.openAiCall("tap", JSONObject().put("node", 1), "c1")          // opens the message menu
                2 -> Replies.openAiCall("tap", JSONObject().put("node", 3), "c2")          // "Переслать"… closes it
                3 -> Replies.openAiCall("tap", JSONObject().put("node", 1), "c3")          // the same tap on the same chat screen
                else -> Replies.openAiText("Открыл ссылку в браузере.")
            }
        }
        try {
            val messages = agent.run("Открой ссылку из Избранного")
            assertEquals(listOf("tap 1", "tap 3"), screen.executed) // the repeat was never sent to the phone
            assertEquals(2, agent.requests.count(::analysis))
            val reflection = agent.requests.first(::analysis).bodyText
            assertTrue(reflection.contains("меню сообщения вместо ссылки"))
            // The blocked step and the lesson reach the model on the next step.
            val next = agent.requests.indexOfLast(::analysis) + 1
            assertTrue(agent.requests[next].bodyText.contains("повтор не поможет"))
            assertTrue(plain(agent.requests[next]).contains("lessons")); assertTrue(plain(agent.requests[next]).contains("План: open_url http://192.168.1.38:8780/"))
            assertTrue(messages.any { it.role == "tool" && it.text.startsWith("Разбор: open_url") })
            // The link is visible to the model as a link, not only as text.
            assertTrue(plain(agent.requests[1]).contains("\"links\":[\"http://192.168.1.38:8780/\"]"))
            assertEquals("Открыл ссылку в браузере.", messages.last().text)
        } finally { agent.close() }
    }

    @Test fun aTapThatChangesNothingIsAnalysedAtOnce() {
        val screen = object : ScreenDriver by TelegramChat() {
            override suspend fun execute(name: String, args: JSONObject) = JSONObject().put("status", ToolStatus.DONE) // the screen stays the same
        }
        var steps = 0
        val agent = AgentRun(context, screen = screen, configure = { it.copy(confirmActions = false, deepThinking = true) }) { _, request ->
            if (analysis(request)) Replies.openAiText("Попробую долгое нажатие.")
            else when (steps++) {
                0 -> Replies.openAiCall("screen", JSONObject(), "c0")
                1 -> Replies.openAiCall("tap", JSONObject().put("node", 2), "c1")
                else -> Replies.openAiText("Готово.")
            }
        }
        try {
            agent.run("Открой Избранное")
            assertEquals(1, agent.requests.count(::analysis))
            assertTrue(agent.requests.first(::analysis).bodyText.contains("экран не изменился"))
            assertTrue(agent.requests.last().bodyText.contains("Попробую долгое нажатие."))
        } finally { agent.close() }
    }

    @Test fun withoutTheModeTheAgentWorksAsBefore() {
        val screen = TelegramChat()
        var steps = 0
        val agent = AgentRun(context, screen = screen) { _, request ->
            assertFalse(analysis(request)); assertFalse(request.bodyText.contains("Режим глубоких размышлений"))
            when (steps++) {
                0 -> Replies.openAiCall("screen", JSONObject(), "c0")
                1 -> Replies.openAiCall("tap", JSONObject().put("node", 1), "c1")
                2 -> Replies.openAiCall("tap", JSONObject().put("node", 3), "c2")
                3 -> Replies.openAiCall("tap", JSONObject().put("node", 1), "c3")
                else -> Replies.openAiText("Готово.")
            }
        }
        try { agent.run("Открой ссылку"); assertEquals(listOf("tap 1", "tap 3", "tap 1"), screen.executed) } finally { agent.close() }
    }

    @Test fun linksAndAnalysesAreReadFromWhatModelsActuallyWrite() {
        assertEquals(listOf("http://192.168.1.38:8780/", "https://www.example.com/a?b=1"), ScreenLinks.find("Админка: http://192.168.1.38:8780/. И www.example.com/a?b=1, ещё текст"))
        assertEquals(emptyList<String>(), ScreenLinks.find("никаких ссылок, только example.com"))
        val json = Reflection.parse("""<think>хм</think>```json
            {"why":"меню вместо ссылки","options":"- open_url\n- long_press","next":""}```""")
        assertEquals("меню вместо ссылки", json.why); assertEquals(listOf("open_url", "long_press"), json.options); assertEquals("open_url", json.next)
        val prose = Reflection.parse("Лучше открыть ссылку через браузер.")
        assertEquals("Лучше открыть ссылку через браузер.", prose.next)
        assertTrue(Reflection.Result("a", listOf("b"), "c").lesson("tap 1").startsWith("Не сработало: tap 1. Причина: a"))
        assertTrue(AppSettings.parse(AppSettings(deepThinking = true).json()).deepThinking)
        assertFalse(AppSettings.parse(JSONObject()).deepThinking)
    }
}
