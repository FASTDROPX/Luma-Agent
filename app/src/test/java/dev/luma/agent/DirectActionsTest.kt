package dev.luma.agent

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DirectActionsTest {
    private lateinit var context: Application
    @Before fun setup() { TestKeys.install(); context = ApplicationProvider.getApplicationContext(); File(context.noBackupFilesDir, "luma-vault.bin").delete() }
    @After fun teardown() { TestKeys.restore() }
    private fun device(name: String, args: JSONObject) = runBlocking { DeviceTools(context).run(name, args) }
    private fun started(): Intent? = shadowOf(context).nextStartedActivity
    private fun calls(request: WireRequest) = request.body!!.getJSONArray("tools").let { t -> (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function").getString("name") } }

    @Test fun textOnlyLocalModelOpensTheShadeAndSwipesWithoutAScreenshot() {
        val screen = FakeScreen()
        val agent = AgentRun(context, Provider.LOCAL, ModelInfo("qwen3:8b", vision = false), screen = screen) { i, _ -> when (i) {
            0 -> Replies.openAiCall("open_notifications", JSONObject(), "call_1")
            1 -> Replies.openAiCall("swipe_screen", JSONObject().put("direction", "down").put("edge", "top"), "call_2")
            else -> Replies.openAiText("Шторка открыта.")
        } }
        try {
            val messages = agent.run("Открой шторку")
            assertEquals(listOf("open_notifications", "swipe_screen"), screen.executed.map { it.first })
            assertEquals(0, screen.observedWithImage) // no picture is needed for either action
            assertEquals(2, screen.observed)          // only the fresh tree attached after each action, never before
            assertTrue(calls(agent.requests[0]).containsAll(listOf("open_notifications", "open_quick_settings", "swipe_screen", "flashlight")))
            assertTrue(agent.requests.none { it.bodyText.contains("image_url") })
            val replay = agent.requests[1].body!!.getJSONArray("messages")
            val toolCall = (0 until replay.length()).map { replay.getJSONObject(it) }.first { it.has("tool_calls") }
            assertEquals("call_1", toolCall.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            val answer = (0 until replay.length()).map { replay.getJSONObject(it) }.first { it.optString("role") == "tool" }
            assertEquals("call_1", answer.getString("tool_call_id")); assertTrue(answer.getString("content").contains("\"status\":\"done\""))
            assertEquals("Шторка открыта.", messages.last().text)
        } finally { agent.close() }
    }

    @Test fun localServerWithoutToolSupportFallsBackToJsonCommandsInTheSameTask() {
        val screen = FakeScreen()
        val agent = AgentRun(context, screen = screen) { i, request ->
            if (request.body!!.has("tools")) throw ProviderClient.httpError(400)
            if (i == 2) Replies.openAiText(Replies.command("open_quick_settings")) else Replies.openAiText(Replies.final("Готово"))
        }
        try {
            agent.run("Открой быстрые настройки")
            // The local server is first asked once more without streaming; then the task drops native tools.
            val plain = agent.requests.indexOfFirst { !it.body!!.has("tools") }
            assertEquals(2, plain); assertEquals(4, agent.requests.size); assertTrue(agent.requests.drop(plain).none { it.body!!.has("tools") })
            val system = agent.requests[plain].body!!.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("Формат ответа")); assertTrue(system.contains("open_quick_settings {}"))
            assertEquals(listOf("open_quick_settings"), screen.executed.map { it.first })
        } finally { agent.close() }
    }

    @Test fun volumeIsOneDirectCallWithoutReadingTheScreen() {
        val audio = context.getSystemService(AudioManager::class.java)
        val screen = FakeScreen()
        val agent = AgentRun(context, configure = { it.copy(confirmActions = false, toolCalling = "json") }, screen = screen) { i, _ ->
            if (i == 0) Replies.openAiText(Replies.command("volume", JSONObject().put("stream", "media").put("level", 40))) else Replies.openAiText(Replies.final("Громкость 40 %"))
        }
        try {
            agent.run("Сделай громкость 40%")
            assertEquals(2, agent.requests.size); assertEquals(0, screen.observed); assertTrue(screen.executed.isEmpty())
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            assertEquals(Math.round(max * .4).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            val observation = agent.requests[1].bodyText
            assertTrue(observation.contains("\\\"status\\\":\\\"done\\\"")); assertTrue(observation.contains("\\\"verified\\\":true"))
            assertTrue(agent.chats.messages.value.any { it.role == "tool" && it.text.contains("готово") })
        } finally { agent.close() }
    }

    @Test fun androidOnlySettingsOpenAPanelOrSettingsAndNeverClaimSuccess() {
        val wifi = context.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") wifi.isWifiEnabled = false
        val panel = device("toggle_setting", JSONObject().put("setting", "wifi").put("state", "on"))
        assertEquals(ToolStatus.NEEDS_CONFIRMATION, panel.getString("status")); assertEquals(Settings.Panel.ACTION_WIFI, started()!!.action)
        assertTrue(panel.getString("userAction").contains("Wi‑Fi"))

        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        val bluetooth = device("toggle_setting", JSONObject().put("setting", "bluetooth").put("state", "off"))
        assertEquals(ToolStatus.NEEDS_USER_ACTION, bluetooth.getString("status")); assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, started()!!.action)

        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        val ask = device("toggle_setting", JSONObject().put("setting", "bluetooth").put("state", "on"))
        assertEquals(ToolStatus.NEEDS_CONFIRMATION, ask.getString("status")); assertEquals(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE, started()!!.action)

        val hotspot = device("toggle_setting", JSONObject().put("setting", "hotspot").put("state", "on"))
        assertEquals(ToolStatus.NEEDS_USER_ACTION, hotspot.getString("status")); assertEquals("system_only", hotspot.getString("code"))
        assertEquals(JSONObject.NULL, hotspot.getJSONObject("state").get("hotspot"))
        for ((setting, action) in listOf("location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "airplane_mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS)) {
            val result = device("toggle_setting", JSONObject().put("setting", setting).put("state", "toggle"))
            assertEquals(setting, ToolStatus.NEEDS_USER_ACTION, result.getString("status")); assertEquals(action, started()!!.action)
        }
        val appOps = shadowOf(context.getSystemService(android.app.AppOpsManager::class.java))
        appOps.setMode(android.app.AppOpsManager.OPSTR_WRITE_SETTINGS, android.os.Process.myUid(), context.packageName, android.app.AppOpsManager.MODE_ERRORED)
        val before = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        val brightness = device("brightness", JSONObject().put("level", 80))
        assertEquals(ToolStatus.NEEDS_USER_ACTION, brightness.getString("status")); assertEquals("write_settings", brightness.getString("code"))
        assertEquals(before, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1))
        assertEquals(ToolStatus.NEEDS_USER_ACTION, device("toggle_setting", JSONObject().put("setting", "auto_rotate").put("state", "on")).getString("status"))
        // Once the user grants it, the same call changes the setting and reads it back.
        appOps.setMode(android.app.AppOpsManager.OPSTR_WRITE_SETTINGS, android.os.Process.myUid(), context.packageName, android.app.AppOpsManager.MODE_ALLOWED)
        val changed = device("brightness", JSONObject().put("level", 80))
        assertEquals(ToolStatus.DONE, changed.getString("status")); assertTrue(changed.getBoolean("verified"))
        assertEquals(204, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1))
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1))
        assertEquals(ToolStatus.UNSUPPORTED, try { device("flashlight", JSONObject().put("state", "on")).getString("status") } catch (e: ToolFailure) { e.status })
    }

    @Test fun doNotDisturbIsDirectOnlyWithTheUsersAccessGrant() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(false)
        val refused = device("toggle_setting", JSONObject().put("setting", "do_not_disturb").put("state", "on"))
        assertEquals(ToolStatus.NEEDS_USER_ACTION, refused.getString("status")); assertEquals("dnd_access", refused.getString("code"))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        val done = device("toggle_setting", JSONObject().put("setting", "do_not_disturb").put("state", "on").put("mode", "alarms"))
        assertEquals(ToolStatus.DONE, done.getString("status")); assertTrue(done.getBoolean("verified"))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, notifications.currentInterruptionFilter)
        assertEquals(ToolStatus.DONE, device("toggle_setting", JSONObject().put("setting", "do_not_disturb").put("state", "status")).getString("status"))
    }

    @Config(sdk = [28]) @Test fun android9StillTogglesWifiDirectly() {
        val wifi = context.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") wifi.isWifiEnabled = false
        val result = device("toggle_setting", JSONObject().put("setting", "wifi").put("state", "on"))
        assertEquals(ToolStatus.DONE, result.getString("status")); assertTrue(result.getBoolean("verified")); assertNull(started())
    }

    @Test fun mediaAndClipboardUsePublicApisAndReportWhatWasChecked() {
        val play = device("media", JSONObject().put("action", "next"))
        assertEquals(ToolStatus.DONE, play.getString("status")); assertFalse(play.getBoolean("verified"))
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, shadowOf(context.getSystemService(AudioManager::class.java)).dispatchedMediaKeyEvents.last().keyCode)
        val copied = device("clipboard", JSONObject().put("action", "set").put("text", "код 1234"))
        assertEquals(ToolStatus.DONE, copied.getString("status"))
        assertEquals("код 1234", context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text)
    }

    @Test fun clockCalendarAndRemindersKeepTimeLabelZoneAndRepeat() {
        val timer = device("set_timer", JSONObject().put("seconds", 300).put("label", "Чай"))
        val timerIntent = started()!!
        assertEquals(AlarmClock.ACTION_SET_TIMER, timerIntent.action); assertEquals(300, timerIntent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertEquals("Чай", timerIntent.getStringExtra(AlarmClock.EXTRA_MESSAGE)); assertTrue(timerIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertEquals(ToolStatus.DONE, timer.getString("status")); assertFalse(timer.getBoolean("verified")) // Android cannot list timers

        device("set_alarm", JSONObject().put("hour", 7).put("minute", 30).put("label", "Работа").put("days", JSONArray(listOf("mon", "fri"))))
        val alarm = started()!!
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarm.action); assertEquals(30, alarm.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals(arrayListOf(java.util.Calendar.MONDAY, java.util.Calendar.FRIDAY), alarm.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
        assertEquals("Работа", alarm.getStringExtra(AlarmClock.EXTRA_MESSAGE))

        val event = device("create_event", JSONObject().put("title", "Встреча").put("start", "2026-11-03T15:00").put("end", "2026-11-03T16:30")
            .put("time_zone", "Europe/Moscow").put("repeat", "weekly").put("location", "Офис"))
        val insert = started()!!
        assertEquals(ToolStatus.NEEDS_CONFIRMATION, event.getString("status")); assertEquals(Intent.ACTION_INSERT, insert.action)
        assertEquals(ZonedDateTime.of(2026, 11, 3, 15, 0, 0, 0, ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(), insert.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertEquals("FREQ=WEEKLY", insert.getStringExtra(CalendarContract.Events.RRULE)); assertEquals("Europe/Moscow", insert.getStringExtra(CalendarContract.Events.EVENT_TIMEZONE))
        try { device("create_event", JSONObject().put("title", "x").put("start", "2026-11-03T15:00").put("end", "2026-11-03T14:00")); fail() } catch (e: ToolFailure) { }

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = ReminderStore(context)
        val now = ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        val created = store.run("create_reminder", JSONObject().put("text", "Позвонить маме").put("in_minutes", 30).put("time_zone", "Europe/Moscow"), now)
        assertEquals(ToolStatus.DONE, created.getString("status")); assertTrue(created.getBoolean("verified"))
        assertEquals("2026-10-22T12:30", created.getJSONObject("reminder").getString("at"))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(), alarms.peekNextScheduledAlarm()!!.triggerAtTime)
        val id = created.getJSONObject("reminder").getString("id")
        assertEquals(id, store.run("list_reminders", JSONObject()).getJSONArray("reminders").getJSONObject(0).getString("id"))
        assertTrue(store.run("cancel_reminder", JSONObject().put("id", id)).getBoolean("verified")); assertTrue(store.all().isEmpty())
        for (bad in listOf(JSONObject().put("text", "Без времени"), JSONObject().put("text", "Прошлое").put("at", "2026-10-21T09:00"))) {
            try { store.run("create_reminder", bad, now); fail(bad.toString()) } catch (e: ToolFailure) { assertEquals(ToolStatus.ERROR, e.status) }
        }
    }

    @Test fun alarmTimeZonesAndRepeatsFollowTheWallClock() {
        val now = ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        val tokyo = AlarmPlan.of(7, 30, listOf("mon"), ZoneId.of("Asia/Tokyo"), ZoneId.of("Europe/Moscow"), now)
        assertEquals(1, tokyo.hour); assertEquals(30, tokyo.minute); assertEquals(listOf("mon"), tokyo.days); assertTrue(tokyo.converted)
        val evening = AlarmPlan.of(2, 0, listOf("mon", "sun"), ZoneId.of("Asia/Tokyo"), ZoneId.of("Europe/Moscow"), now)
        assertEquals(20, evening.hour); assertEquals(listOf("sun", "sat"), evening.days) // 02:00 Tokyo is the previous evening in Moscow
        assertFalse(AlarmPlan.of(9, 0, emptyList(), ZoneId.of("Europe/Moscow"), ZoneId.of("Europe/Moscow"), now).converted)
        val berlin = ZoneId.of("Europe/Berlin")
        val daily = Reminder("r", "t", ZonedDateTime.of(2026, 3, 28, 9, 0, 0, 0, berlin).toInstant().toEpochMilli(), berlin.id, "09:00", "daily")
        val next = daily.next(daily.at)!!
        assertEquals(ZonedDateTime.of(2026, 3, 29, 9, 0, 0, 0, berlin).toInstant().toEpochMilli(), next.at) // 23 hours later: DST began
        assertNull(daily.copy(repeat = "none").next(daily.at))
    }

    @Test fun approvalsStillGuardDirectActionsAndReadsStayFree() {
        fun tool(name: String, args: JSONObject = JSONObject()) = AgentReply.Tool(name, args, "")
        assertTrue(AgentApprovalPolicy.requiresApproval(tool("flashlight", JSONObject().put("state", "on")), confirmActions = true))
        assertFalse(AgentApprovalPolicy.requiresApproval(tool("flashlight", JSONObject().put("state", "status")), confirmActions = true))
        assertFalse(AgentApprovalPolicy.requiresApproval(tool("device_status"), confirmActions = true))
        assertFalse(AgentApprovalPolicy.requiresApproval(tool("open_notifications"), confirmActions = false))
        for (important in listOf(tool("clipboard", JSONObject().put("action", "get")), tool("delete_file", JSONObject().put("path", "a")), tool("move_file")))
            assertFalse(important.name, AgentApprovalPolicy.requiresApproval(important, confirmActions = false))

        val audio = context.getSystemService(AudioManager::class.java); val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val agent = AgentRun(context, configure = { it.copy(confirmActions = true) }) { _, _ -> Replies.openAiCall("volume", JSONObject().put("level", 0)) }
        try {
            var asked: Approval? = null
            agent.run("Выключи звук") { asked = it; false }
            assertTrue(asked!!.title.contains("Громкость")); assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(1, agent.requests.size); assertTrue(agent.chats.messages.value.last().text.contains("отменено"))
        } finally { agent.close() }
    }

    @Test fun stopCancelsAWaitingDirectAction() {
        val agent = AgentRun(context, configure = { it.copy(confirmActions = true) }) { _, _ -> Replies.openAiCall("flashlight", JSONObject().put("state", "on")) }
        try {
            agent.run("Включи фонарик", decide = null)
            assertNotNull(agent.controller.state.value.approval)
            agent.controller.stop(); runBlocking { kotlinx.coroutines.withTimeout(5000) { while (agent.controller.state.value.active) kotlinx.coroutines.delay(20) } }
            agent.controller.decide(true)
            assertFalse(agent.controller.state.value.active); assertNull(agent.controller.state.value.approval)
        } finally { agent.close() }
    }

    @Test fun anActionWithAnUnknownResultIsNotRepeatedWithoutTheUser() {
        val screen = FakeScreen(mapOf("swipe_screen" to ToolFailure(ToolStatus.UNKNOWN, "Android не подтвердил жест")))
        val swipe = JSONObject().put("direction", "up")
        val agent = AgentRun(context, screen = screen, configure={it.copy(confirmActions=true)}) { _, _ -> Replies.openAiCall("swipe_screen", swipe) }
        try {
            var asked: Approval? = null
            var approvals=0
            agent.run("Пролистай вверх") { if(++approvals==1) true else {asked=it;false} }
            assertEquals(1, screen.executed.size) // ask mode confirms the uncertain repeat before injecting it again
            assertTrue(asked!!.details.contains("неизвестен"))
        } finally { agent.close() }
    }

    @Test fun invalidArgumentsAreAnsweredBeforeAnythingRuns() {
        val audio = context.getSystemService(AudioManager::class.java); val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val agent = AgentRun(context) { i, _ -> when (i) {
            0 -> Replies.openAiCall("volume", JSONObject().put("level", 150))
            1 -> Replies.openAiCall("make_coffee", JSONObject())
            else -> Replies.openAiText("Не получилось")
        } }
        try {
            agent.run("Громкость 150")
            assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC)); assertEquals(3, agent.requests.size)
            assertTrue(agent.requests[1].bodyText.contains("не больше 100")); assertTrue(agent.requests[2].bodyText.contains("Неизвестный инструмент"))
        } finally { agent.close() }
    }

    @Test fun promptCarriesCapabilitiesTimeAndCompactCatalogOnly() {
        val environment = TaskEnvironment.capture(context, screen = false, folder = "")
        val prompt = ModelPrompts.agent(AppSettings(), Connection(model = "m"), false, environment, native = false)
        assertTrue(prompt.contains("Сейчас: ")); assertTrue(prompt.contains("Возможности устройства"))
        assertTrue(prompt.contains("Недоступно: ")); assertTrue(prompt.contains("шторка, быстрые настройки"))
        assertTrue(prompt.contains("Только открыть настройки")); assertTrue(prompt.contains("точка доступа"))
        assertTrue(prompt.contains("swipe_screen {direction:up|down|left|right"))
        val native = ModelPrompts.agent(AppSettings(), Connection(model = "m"), false, environment, native = true)
        assertFalse(native.contains("swipe_screen {")) // tool schemas travel separately in native mode
        assertTrue(native.length < prompt.length)
        val details = runBlocking { val agent = AgentRun(context) { i, _ -> if (i == 0) Replies.openAiCall("describe_tool", JSONObject().put("name", "brightness")) else Replies.openAiText("ok") }
            try { agent.run("?"); agent.requests[1].bodyText } finally { agent.close() } }
        assertTrue(details.contains("Изменение системных настроек")); assertTrue(details.contains("availability"))
    }
}
