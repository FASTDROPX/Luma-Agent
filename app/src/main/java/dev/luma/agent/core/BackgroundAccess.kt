package dev.luma.agent.core

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Optional: let Luma keep working in the background. Android (and some phone makers more so) may pause a long agent task
 * or a local-model reply when the screen is off or another app is open. Nothing here is required; each item is granted
 * by the user on a system screen.
 */
object BackgroundAccess {
    data class Status(
        /** Battery optimisation is off for Luma («Без ограничений»). */
        val unrestrictedBattery: Boolean,
        /** The user restricted Luma's background activity in its app settings (Android 9+). */
        val backgroundRestricted: Boolean,
        /** Data Saver is on and Luma is not allowed to use data in the background. */
        val dataSaverBlocks: Boolean,
    ) { val allGood: Boolean get() = unrestrictedBattery && !backgroundRestricted && !dataSaverBlocks }

    fun status(context: Context): Status {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return Status(
            unrestrictedBattery = runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false),
            backgroundRestricted = Build.VERSION.SDK_INT >= 28 && runCatching { activity.isBackgroundRestricted }.getOrDefault(false),
            dataSaverBlocks = runCatching { connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED }.getOrDefault(false))
    }

    private fun app(context: Context) = Uri.fromParts("package", context.packageName, null)

    /**
     * Android's own «Allow Luma to run in the background?» dialog. Luma is not on Google Play, whose policy limits this
     * request; the dialog only asks, the user decides. Some firmwares lack it: then the battery optimisation list opens.
     */
    @SuppressLint("BatteryLife")
    fun batteryRequest(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app(context)))
    /** Luma's app page, where «Батарея» → «Без ограничений» / «Фоновая активность» live. */
    fun appSettings(context: Context): List<Intent> = listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app(context)))
    /** Data Saver exception for Luma. */
    fun dataSaver(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, app(context)),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app(context)))

    /** Starts the first intent of [intents] that the phone can open; false if none could. */
    fun open(context: Context, intents: List<Intent>): Boolean = intents.any { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Phone makers known to stop background apps beyond stock Android; their own «autostart» settings need a manual step. */
    fun vendorHint(manufacturer: String = Build.MANUFACTURER): String? = when (manufacturer.lowercase()) {
        "xiaomi", "redmi", "poco" -> "На Xiaomi ещё включите «Автозапуск» и в «Контроле активности» выберите «Нет ограничений» (Настройки → Приложения → Luma)."
        "huawei", "honor" -> "На Huawei и Honor в «Запуске приложений» переключите Luma на ручное управление и разрешите работу в фоне."
        "samsung" -> "На Samsung уберите Luma из «Спящих приложений» (Обслуживание устройства → Батарея → Ограничения в фоне)."
        "oppo", "realme", "oneplus" -> "На OPPO, realme и OnePlus разрешите Luma «Автозапуск» и работу в фоне в настройках батареи."
        "vivo" -> "На vivo разрешите Luma работу в фоне с высоким энергопотреблением в настройках батареи."
        else -> null
    }
}
