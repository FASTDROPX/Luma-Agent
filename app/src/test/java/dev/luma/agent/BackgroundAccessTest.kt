package dev.luma.agent

import android.app.ActivityManager
import android.app.Application
import android.net.ConnectivityManager
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.BackgroundAccess
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The optional «Работа в фоне» card: what it reads from Android and which system screens it opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackgroundAccessTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun statusFollowsBatteryBackgroundAndDataSaverSettings() {
        assertFalse(BackgroundAccess.status(context).allGood)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(BackgroundAccess.status(context).unrestrictedBattery); assertTrue(BackgroundAccess.status(context).allGood)
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        assertTrue(BackgroundAccess.status(context).backgroundRestricted)
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        assertTrue(BackgroundAccess.status(context).dataSaverBlocks); assertFalse(BackgroundAccess.status(context).allGood)
    }

    @Test fun requestsOpenAndroidsOwnScreensForLuma() {
        assertTrue(BackgroundAccess.open(context, BackgroundAccess.batteryRequest(context)))
        val battery = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action); assertEquals("package:${context.packageName}", battery.dataString)
        assertTrue(BackgroundAccess.open(context, BackgroundAccess.dataSaver(context)))
        assertEquals(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, shadowOf(context).nextStartedActivity.action)
        assertTrue(BackgroundAccess.vendorHint("Xiaomi")!!.contains("Автозапуск")); assertTrue(BackgroundAccess.vendorHint("samsung")!!.contains("Спящих"))
        assertNull(BackgroundAccess.vendorHint("Google"))
    }
}
