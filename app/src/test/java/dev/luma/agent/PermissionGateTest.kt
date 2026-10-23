package dev.luma.agent

import android.Manifest
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** When a tool needs an access that is off, the agent pauses, the chat shows a «нужен доступ» card, and after the grant it continues. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PermissionGateTest {
    private lateinit var context: Application
    @Before fun setup() { TestKeys.install(); context = ApplicationProvider.getApplicationContext(); File(context.noBackupFilesDir, "luma-vault.bin").delete(); File(context.noBackupFilesDir, "conversations.db").delete() }
    @After fun teardown() { TestKeys.restore() }

    @Test fun aToolBlockedByAMissingAccessWaitsForTheGrantThenRuns() {
        var steps = 0
        val agent = AgentRun(context, screen = FakeScreen()) { i, _ -> when (steps++) {
            0 -> Replies.openAiCall("find_contacts", JSONObject().put("query", "Аня"), "c1")
            else -> Replies.openAiText("Нашёл контакты.")
        } }
        val states = mutableListOf<RunState>()
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch { agent.controller.state.collect { state ->
            states += state
            // The card appeared: grant the access and resume, as the chat's «Выдать доступ» button does.
            if (state.permission != null) { shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS); agent.controller.resumeAccess() }
        } }
        try {
            val messages = agent.run("Найди контакт Ани")
            assertTrue(states.any { it.permission?.id == "contacts" }) // the card was shown
            assertTrue(states.any { it.permission == null && it.active }) // and cleared
            assertEquals("Нашёл контакты.", messages.last().text)
        } finally { watcher.cancel(); agent.close() }
    }

    @Test fun skippingTheAccessLetsTheModelContinueWithoutIt() {
        var steps = 0
        val agent = AgentRun(context, screen = FakeScreen()) { i, request -> when (steps++) {
            0 -> Replies.openAiCall("read_sms", JSONObject(), "c1")
            else -> { assertTrue(request.bodyText.contains("не выдан")); Replies.openAiText("Без доступа к SMS не могу прочитать переписку.") }
        } }
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch { agent.controller.state.collect { if (it.permission != null) agent.controller.skipAccess() } } // «Пропустить»
        try {
            val messages = agent.run("Прочитай мои SMS")
            assertEquals("Без доступа к SMS не могу прочитать переписку.", messages.last().text)
        } finally { watcher.cancel(); agent.close() }
    }

    @Test fun anAlreadyGrantedAccessRunsWithoutACard() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        var steps = 0
        val agent = AgentRun(context, screen = FakeScreen()) { i, _ -> when (steps++) {
            0 -> Replies.openAiCall("find_contacts", JSONObject().put("query", "А"), "c1")
            else -> Replies.openAiText("Готово.")
        } }
        val states = mutableListOf<RunState>()
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch { agent.controller.state.collect { states += it } }
        try { agent.run("Контакты"); assertTrue(states.none { it.permission != null }) } finally { watcher.cancel(); agent.close() }
    }
}
