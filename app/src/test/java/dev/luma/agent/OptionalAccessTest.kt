package dev.luma.agent

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.OptionalAccess
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** «Необязательные доступы»: the list for each Android version, statuses, requests, and the manifest behind them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OptionalAccessTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun everyAndroidVersionGetsOnlyTheAccessesItHas() {
        val newest = OptionalAccess.items(35).map { it.id }
        assertTrue(newest.containsAll(listOf("notifications", "write_settings", "dnd", "bluetooth", "exact_alarms", "battery", "background", "data_saver",
            "camera", "microphone", "location", "background_location", "contacts", "calendar", "phone", "call_log", "sms", "photos", "audio", "activity",
            "sensors", "nearby_scan", "nearby_wifi", "all_files", "overlay", "notification_listener", "usage", "install", "manage_media", "full_screen")))
        val oreo = OptionalAccess.items(26).map { it.id }
        assertFalse(oreo.any { it in listOf("notifications", "bluetooth", "background_location", "audio", "all_files", "full_screen", "manage_media") })
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), OptionalAccess.items(30).first { it.id == "photos" }.runtime(30))
        assertEquals(OptionalAccess.Group.ACTIONS, OptionalAccess.items(35).first().group)
    }

    @Test fun statusesAreReadAndEveryAccessOpensAScreenOrADialog() {
        OptionalAccess.items().forEach { item ->
            item.granted(context) // never throws on a phone without the feature
            assertTrue(item.id, item.runtime().isNotEmpty() || item.screens(context).isNotEmpty())
        }
        val missing = OptionalAccess.missing(context, OptionalAccess.Group.RESERVE)
        assertTrue(Manifest.permission.CAMERA in missing); assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in missing)
        shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        assertTrue(OptionalAccess.items().first { it.id == "camera" }.granted(context))
        assertFalse(Manifest.permission.CAMERA in OptionalAccess.missing(context, OptionalAccess.Group.RESERVE))
    }

    @Test fun everyRuntimePermissionIsDeclaredInTheManifest() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).firstOrNull { it.exists() }
        assumeTrue("manifest not found from ${File(".").absolutePath}", manifest != null)
        val text = manifest!!.readText()
        val asked = (listOf(35, 32, 30, 28, 26).flatMap { sdk -> OptionalAccess.items(sdk).flatMap { it.runtime(sdk) } }).distinct()
        asked.forEach { assertTrue(it, text.contains("android:name=\"$it\"")) }
        listOf("MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS", "REQUEST_INSTALL_PACKAGES", "MANAGE_MEDIA", "USE_FULL_SCREEN_INTENT",
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS").forEach { assertTrue(it, text.contains("android.permission.$it")) }
        assertTrue(text.contains(".LumaNotificationListener"))
    }
}
