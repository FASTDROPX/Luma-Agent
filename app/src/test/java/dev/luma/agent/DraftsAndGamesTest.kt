package dev.luma.agent

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DraftsAndGamesTest {
    private lateinit var context: Application
    @Before fun setup() {
        TestKeys.install(); context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, "luma-vault.bin").delete(); File(context.noBackupFilesDir, "conversations.db").delete()
        context.getSharedPreferences("drafts", 0).edit().clear().commit()
    }
    @After fun teardown() { TestKeys.restore() }

    @Test fun eachChatKeepsItsUnsentTextAcrossScreensAndRestarts() {
        val drafts = DraftStore(context)
        drafts.save(null, "Напиши письмо", emptyList())
        drafts.save(7, "Вопрос во втором чате", listOf(Attachment("0b7f6f5e-1c2d-4e3f-8a9b-0123456789ab", "план.pdf", "application/pdf", 10)))
        val reopened = DraftStore(context)
        assertEquals("Напиши письмо", reopened.get(null).text)
        assertEquals("Вопрос во втором чате", reopened.get(7).text); assertEquals("план.pdf", reopened.get(7).files.single().name)
        assertEquals("", reopened.get(8).text)
        reopened.save(null, "", emptyList()) // sent: the draft is gone
        assertEquals("", DraftStore(context).get(null).text)
    }

    @Test fun anAnswerKeepsItsWebSearchInTheChatAndInTheExport() = runBlocking {
        val chats = ChatStore(context); chats.init()
        try {
            val id = chats.ensure("Погода"); chats.append(id, "user", "Погода?")
            val search = WebSearch(listOf("погода"), listOf(WebSource("https://gismeteo.ru/a", "Gismeteo", true), WebSource("https://yandex.ru/pogoda", "Яндекс")))
            chats.append(id, "assistant", "+5 °C", search = search, stats = TokenStats(10, 5, 100, 900))
            val answer = chats.messages.value.last()
            assertEquals(search, answer.search); assertEquals(5, answer.stats!!.outputTokens)
            assertNull(chats.messages.value.first().search)
            // The model gets the answer text only.
            assertEquals(listOf("Погода?", "+5 °C"), chats.history(id).map { it.text })
            val markdown = chats.exportMarkdown(id)
            assertTrue(markdown.contains("- [Gismeteo](https://gismeteo.ru/a)")); assertTrue(markdown.contains("Источники:"))
        } finally { chats.close() }
    }

    private fun picture(draw: Canvas.(Paint) -> Unit): Bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply { drawColor(Color.rgb(20, 24, 40)); draw(Paint().apply { color = Color.rgb(240, 200, 60) }) }
    }

    @Test fun screenshotHashesTellMenusApartButIgnoreSmallAnimation() {
        val menu = picture { drawRect(100f, 1700f, 980f, 2000f, it); drawCircle(540f, 900f, 300f, it) }
        val animated = picture { drawRect(100f, 1700f, 980f, 2000f, it); drawCircle(560f, 900f, 300f, it) }
        val shop = picture { drawRect(0f, 0f, 1080f, 600f, it); drawRect(60f, 900f, 500f, 1500f, it); drawRect(580f, 900f, 1020f, 1500f, it) }
        val a = FrameHash.of(menu); val b = FrameHash.of(animated); val c = FrameHash.of(shop)
        assertEquals(a, FrameHash.of(menu))
        assertTrue(FrameHash.similar(a, b)); assertFalse(FrameHash.similar(a, c))
        assertTrue(FrameHash.similar(null, c))
        assertEquals(0L, FrameHash.of(IntArray(72) { 100 }))
    }

    @Test fun inAGameTheSameEmptyScreenIsNotARepeatWhileThePictureChanges() {
        val empty = JSONObject().put("package", "com.supercell.brawlstars").put("width", 1000).put("height", 1000).put("nodes", JSONArray())
        fun screen() = AgentReply.Tool("screen", JSONObject().put("image", true), "")
        // Main menu → shop → offers → daily gift: one tree, four different pictures.
        val guard = AgentLoopGuard()
        for (frame in listOf(0x0L, 0xFFFFL, 0xFFFF0000L, -1L)) assertFalse(guard.repeated(screen(), JSONObject(empty.toString()), frame))
        // Stuck on one picture: paused at the fourth identical look, small animation included.
        val stuck = AgentLoopGuard()
        val frames = listOf(0x0F0F0F0FL, 0x0F0F0F0FL, 0x0F0F0F0EL, 0x0F0F0F1FL)
        assertEquals(listOf(false, false, false, true), frames.map { stuck.repeated(screen(), JSONObject(empty.toString()), it) })
        // A tap in the same place after each look is judged by the picture it was made on.
        val taps = AgentLoopGuard()
        val tap = AgentReply.Tool("tap_point", JSONObject().put("x", 500).put("y", 880), "")
        val result = JSONObject().put("status", "done").put("screen", empty)
        assertFalse(listOf(1L, 0xFF00L, 0xFF0000L, 0xFF000000L).any { taps.repeated(tap, JSONObject(result.toString()), it) })
    }

    @Test fun geminiModelsGetTheZeroToThousandGridOthersScreenshotPixels() {
        for ((provider, model, grid) in listOf(Triple(Provider.OPENROUTER, "google/gemini-2.5-flash", 1000), Triple(Provider.LOCAL, "qwen3:8b", 0))) {
            val screen = FakeScreen()
            val agent = AgentRun(context, provider, ModelInfo(model, vision = false), screen = screen) { i, _ -> when (i) {
                0 -> Replies.openAiCall("screen", JSONObject(), "call_1")
                1 -> Replies.openAiCall("home", JSONObject(), "call_2")
                else -> Replies.openAiText("Готово.")
            } }
            try {
                agent.run("Открой главный экран")
                assertTrue(screen.grids.isNotEmpty()); assertTrue("$model ${screen.grids}", screen.grids.all { it == grid })
            } finally { agent.close() }
        }
    }
}
