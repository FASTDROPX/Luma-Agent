package dev.luma.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** In agent mode the cursor and its overlay appear only when the task needs the screen, not for an answer or a direct action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentOverlayGateTest {
    private lateinit var context: Application
    @Before fun setup() { TestKeys.install(); context = ApplicationProvider.getApplicationContext(); File(context.noBackupFilesDir, "luma-vault.bin").delete(); File(context.noBackupFilesDir, "conversations.db").delete() }
    @After fun teardown() { TestKeys.restore() }

    /** Records every state of the task and whether the overlay was asked for before an action. */
    private class Gate(val readyAfter: Int = 0) : ScreenDriver by FakeScreen() {
        var readyChecks = 0
        val executedWithOverlay = mutableListOf<Boolean>()
        override fun overlayReady(): Boolean = ++readyChecks > readyAfter
        override suspend fun execute(name: String, args: JSONObject): JSONObject {
            executedWithOverlay += readyChecks > readyAfter
            return JSONObject().put("status", ToolStatus.DONE).put("verified", true)
        }
    }

    private fun run(screen: Gate, vararg replies: JSONObject): List<RunState> {
        val agent = AgentRun(context, screen = screen) { i, _ -> replies.getOrElse(i) { Replies.openAiText("Готово.") } }
        val states = mutableListOf<RunState>()
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch { agent.controller.state.collect { states += it } }
        try { agent.run("задача") } finally { watcher.cancel(); agent.close() }
        return states
    }

    @Test fun aQuestionIsAnsweredLikeAChatWithoutAgentUi() {
        val screen = Gate()
        val states = run(screen, Replies.openAiText("Столица Франции — Париж."))
        assertTrue(states.any { it.active })
        assertTrue(states.none { it.acting || it.screen }); assertEquals(0, screen.readyChecks)
    }

    @Test fun aDirectActionShowsTheAgentCardButNoCursor() {
        val screen = Gate()
        val states = run(screen, Replies.openAiCall("device_status", JSONObject(), "c1"), Replies.openAiText("Заряд 84 %."))
        assertTrue(states.any { it.acting }); assertTrue(states.none { it.screen }); assertEquals(0, screen.readyChecks)
    }

    @Test fun theFirstScreenActionWaitsForTheOverlayAndItsStopButton() {
        val screen = Gate(readyAfter = 3)
        val states = run(screen, Replies.openAiCall("screen", JSONObject(), "c1"), Replies.openAiCall("tap", JSONObject().put("node", 1), "c2"),
            Replies.openAiCall("back", JSONObject(), "c3"), Replies.openAiText("Готово."))
        assertTrue(states.any { it.screen })
        assertEquals(4, screen.readyChecks) // asked once, until ready; later steps do not wait again
        assertEquals(listOf(true, true), screen.executedWithOverlay)
        assertTrue(ToolRegistry.controlsScreen("open_app")); assertFalse(ToolRegistry.controlsScreen("flashlight")); assertFalse(ToolRegistry.controlsScreen("write_text"))
    }
}
