package dev.luma.agent

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccessActionsTest {
    private lateinit var context: Application
    @Before fun setup() {
        TestKeys.install(); context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, "luma-vault.bin").delete(); File(context.noBackupFilesDir, "conversations.db").delete()
    }
    @After fun teardown() { TestKeys.restore() }

    @Test fun allSettingsAccessesHaveAnActionAndEveryToolIsInTheJsonCatalog() {
        val scenarios = listOf(
            "create_reminder" to JSONObject(), "brightness" to JSONObject().put("level", 30),
            "toggle_setting" to JSONObject().put("setting", "do_not_disturb").put("state", "on"),
            "toggle_setting" to JSONObject().put("setting", "bluetooth").put("state", "on"),
            "prepare_background" to JSONObject(), "capture_photo" to JSONObject(), "record_audio" to JSONObject(),
            "get_background_location" to JSONObject(), "find_contacts" to JSONObject(), "list_calendar" to JSONObject(),
            "phone_info" to JSONObject(), "read_call_log" to JSONObject(), "wait_sms" to JSONObject(),
            "list_media" to JSONObject().put("type", "image"), "list_media" to JSONObject().put("type", "audio"),
            "read_sensor" to JSONObject().put("sensor", "steps"), "read_sensor" to JSONObject().put("sensor", "heart_rate"),
            "scan_bluetooth" to JSONObject(), "wifi_connect" to JSONObject(), "storage_read" to JSONObject(),
            "show_floating_note" to JSONObject(), "read_notifications" to JSONObject(), "app_usage" to JSONObject(),
            "install_apk" to JSONObject(), "delete_media" to JSONObject(), "show_notification" to JSONObject().put("full_screen", true))
        val covered = scenarios.flatMap { (name, args) -> AccessPolicy.requirements(name, args, 35).map { it.id } }.toSet()
        assertTrue("Missing: ${OptionalAccess.items(35).map { it.id }.toSet() - covered}", covered.containsAll(OptionalAccess.items(35).map { it.id }))
        val catalog = ToolRegistry.catalog()
        ToolRegistry.names.forEach { assertTrue("Missing $it", catalog.contains("$it {")) }
        assertEquals(ToolRegistry.names.size, ToolRegistry.specs.size)
    }

    @Test fun audioAndReadsRequestOnlyTheirOwnPermissions() {
        val audio = AccessPolicy.needed(context, "list_media", JSONObject().put("type", "audio")).single()
        assertEquals("audio", audio.id); assertEquals("Музыка и аудио", audio.title)
        assertEquals(listOf(Manifest.permission.READ_MEDIA_AUDIO), audio.runtime)
        assertNull(audio.fallback)
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), AccessPolicy.requirements("find_contacts", JSONObject()).single().permissions)
        assertEquals(listOf(Manifest.permission.WRITE_CONTACTS), AccessPolicy.requirements("add_contact", JSONObject()).single().permissions)
    }

    @Test fun denyingPermissionKeepsTheCardThenGrantResumesExactlyOnce() {
        var steps = 0; var deniedCardObserved = false
        val agent = AgentRun(context) { _, _ -> if (steps++ == 0) Replies.openAiCall("find_contacts", JSONObject().put("query", "A")) else Replies.openAiText("Готово") }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { agent.controller.state.collect { state ->
            if (state.permission != null) {
                agent.controller.resumeAccess() // Android returned a denial
                assertNotNull(agent.controller.state.value.permission); deniedCardObserved = true
                shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
                agent.controller.resumeAccess()
            }
        } }
        try { assertEquals("Готово", agent.run("Найди контакт").last().text); assertTrue(deniedCardObserved); assertEquals(2, steps) }
        finally { scope.cancel(); agent.close() }
    }

    @Test fun callFallbackOpensDialerAndNeverRequestsCallPermission() {
        var steps = 0; var opened = false
        val agent = AgentRun(context) { _, request -> if (steps++ == 0) Replies.openAiCall("call_number", JSONObject().put("number", "+79991234567")) else {
            assertTrue(request.bodyText.contains("needs_confirmation")); Replies.openAiText("Подтвердите звонок в звонилке")
        } }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { agent.controller.state.collect { state ->
            if (state.permission != null) { assertNotNull(state.permission.fallback); agent.controller.continueWithoutAccess() }
            state.systemAction?.let {
                assertEquals(Intent.ACTION_DIAL, it.intent?.action); assertEquals("+79991234567", it.intent?.data?.schemeSpecificPart); opened = true
                // A stale callback cannot complete the current request.
                agent.controller.completeSystemAction("stale-id", SystemToolResult(Activity.RESULT_OK))
                assertNotNull(agent.controller.state.value.systemAction)
                agent.controller.completeSystemAction(it.id, SystemToolResult(Activity.RESULT_CANCELED))
            }
        } }
        try { agent.run("Позвони"); assertTrue(opened); assertNotEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CALL_PHONE)) }
        finally { scope.cancel(); agent.close() }
    }

    @Test fun stopCancelsAPendingPermissionAndNeverCallsTheModelAgain() = runBlocking {
        val agent = AgentRun(context) { _, _ -> Replies.openAiCall("read_sms", JSONObject()) }
        val chat = agent.chats.ensure("SMS"); agent.chats.append(chat, "user", "SMS")
        val finished = CompletableDeferred<Unit>()
        agent.controller.start(chat, "SMS") { finished.complete(Unit) }
        withTimeout(5000) { agent.controller.state.firstPermission() }
        agent.controller.stop(); withTimeout(5000) { finished.await() }
        assertFalse(agent.controller.state.value.active); assertNull(agent.controller.state.value.permission); assertEquals(1, agent.requests.size)
        agent.close()
    }

    @Test fun storageCannotEscapeSharedStorage() {
        val tools = ExtendedTools(context) { SystemToolResult(Activity.RESULT_CANCELED) }
        for (path in listOf("/data/data/dev.luma.agent/secret", "/storage/emulated/0/../../data/secret", "/sdcard/Android/data/other.app/file")) {
            try { tools.storageFile(path); fail("Accepted $path") } catch (_: UserError) { }
        }
    }
}

private suspend fun kotlinx.coroutines.flow.StateFlow<RunState>.firstPermission() = first { it.permission != null }
