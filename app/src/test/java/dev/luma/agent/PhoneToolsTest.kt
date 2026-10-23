package dev.luma.agent

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The permission tools: SMS and calls are prepared correctly, reads come back as data, and failures are clear. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneToolsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private fun tools(sender: PhoneTools.SmsSender = PhoneTools.SmsSender { _, _, _ -> }) = PhoneTools(context, sender)

    @Test fun sendSmsCleansTheNumberAndHandsItToTheSender() = runBlocking {
        var sent: Pair<String, String>? = null
        val result = tools { _, number, text -> sent = number to text }.run("send_sms", JSONObject().put("number", "+7 (999) 123-45-67").put("text", "Привет"))
        assertEquals(ToolStatus.DONE, result.getString("status")); assertEquals(false, result.getBoolean("verified"))
        assertEquals("+79991234567" to "Привет", sent)
        try { tools().run("send_sms", JSONObject().put("number", "x").put("text", "a")); fail() } catch (e: ToolFailure) { assertEquals(ToolStatus.ERROR, e.status) }
    }

    @Test fun callStartsADialIntentWithTheCleanedNumber() = runBlocking {
        val result = tools().run("call_number", JSONObject().put("number", "8-800-555-35-35"))
        assertEquals(ToolStatus.DONE, result.getString("status"))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CALL, intent.action); assertEquals("tel:88005553535", intent.dataString)
    }

    @Test fun readsReturnDataAndEmptyProvidersAreNotAnError() = runBlocking {
        for (name in listOf("find_contacts" to JSONObject().put("query", "Аня"), "read_call_log" to JSONObject(), "read_sms" to JSONObject(), "list_media" to JSONObject().put("type", "image"))) {
            val result = tools().run(name.first, name.second)
            assertEquals(name.first, ToolStatus.DONE, result.getString("status"))
        }
        assertEquals(0, tools().run("find_contacts", JSONObject().put("query", "Аня")).getInt("count"))
    }

    @Test fun locationWithoutProvidersAsksTheUserAndUsageWithoutDataToo() = runBlocking {
        val lm = org.robolectric.Shadows.shadowOf(context.getSystemService(android.location.LocationManager::class.java))
        lm.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, false); lm.setProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER, false)
        try { tools().run("get_location", JSONObject()); fail() } catch (e: ToolFailure) { assertEquals(ToolStatus.NEEDS_USER_ACTION, e.status) }
        try { tools().run("app_usage", JSONObject()); fail() } catch (e: ToolFailure) { assertEquals(ToolStatus.NEEDS_USER_ACTION, e.status) }
    }

    @Test fun everyPhoneToolMapsToAnExistingOptionalAccess() {
        val ids = OptionalAccess.items(35).map { it.id }.toSet() + "audio"
        PhoneTools.ACCESS.values.forEach { assertTrue(it, it in ids) }
        assertTrue(PhoneTools.names.all { it in ToolRegistry.names })
        // list_media's access depends on the type asked for.
        assertEquals("photos", PhoneTools.mediaAccess("image")); assertEquals("audio", PhoneTools.mediaAccess("audio"))
        // Calls and SMS always wait for the user; reads do not.
        assertTrue(ToolRegistry["call_number"]!!.important(JSONObject())); assertTrue(ToolRegistry["send_sms"]!!.important(JSONObject()))
        assertFalse(ToolRegistry["find_contacts"]!!.important(JSONObject())); assertFalse(ToolRegistry["find_contacts"]!!.sideEffect)
        assertEquals("contacts", ToolRegistry["find_contacts"]!!.access)
    }

    @Test fun optionalAccessReportsWhatIsNeededUntilGranted() {
        assertNotNull(OptionalAccess.need(context, "contacts"))
        assertEquals("contacts", OptionalAccess.need(context, "contacts")!!.id)
        assertTrue(Manifest.permission.READ_CONTACTS in OptionalAccess.need(context, "contacts")!!.runtime)
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        assertNull(OptionalAccess.need(context, "contacts")); assertTrue(OptionalAccess.granted(context, "contacts"))
        // The tool-level check needs only READ_CONTACTS.
        assertTrue(OptionalAccess.toolGranted(context, "contacts", listOf(Manifest.permission.READ_CONTACTS)))
    }
}
