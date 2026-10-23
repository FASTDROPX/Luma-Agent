package dev.luma.agent.core

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * What this phone lets Luma do right now, computed once per task: directly, through a system confirmation,
 * only by opening settings for the user, or not at all. It goes into the prompt as a few short lines.
 */
object DeviceCapabilities {
    const val DIRECT = "direct"; const val CONFIRM = "confirm"; const val SETTINGS = "settings"; const val UNAVAILABLE = "unavailable"
    data class Entry(val feature: String, val level: String, val tools: List<String>, val note: String = "")

    fun scan(context: Context, screen: Boolean, workspace: WorkspaceInfo): List<Entry> {
        val sdk = Build.VERSION.SDK_INT
        fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        fun handles(intent: Intent) = runCatching { context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null }.getOrDefault(false)
        val flash = runCatching { context.getSystemService(CameraManager::class.java)?.let { m -> m.cameraIdList.any { m.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } } }.getOrNull() == true
        val write = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)
        val dnd = runCatching { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }.getOrDefault(false)
        val connect = sdk < 31 || has(Manifest.permission.BLUETOOTH_CONNECT)
        val bluetooth = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull() != null
        val nfc = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull() != null
        val exact = Build.VERSION.SDK_INT < 31 || runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
        val notify = sdk < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        val clock = handles(Intent(AlarmClock.ACTION_SET_ALARM)) || handles(Intent(AlarmClock.ACTION_SET_TIMER))
        val calendar = handles(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI))
        val service = if (screen) DIRECT else UNAVAILABLE
        return listOf(
            Entry("фонарик", if (flash) DIRECT else UNAVAILABLE, listOf("flashlight"), if (flash) "" else "нет вспышки"),
            Entry("громкость, режим звонка", DIRECT, listOf("volume", "ringer_mode"), if (dnd) "" else "звонок при «Не беспокоить» — нужен доступ к режиму"),
            Entry("воспроизведение", DIRECT, listOf("media")),
            Entry("состояние телефона", DIRECT, listOf("device_status")),
            Entry("яркость, автоповорот", if (write) DIRECT else SETTINGS, listOf("brightness", "toggle_setting"), if (write) "" else "нужно разрешение «Изменение системных настроек»"),
            Entry("«Не беспокоить»", if (dnd) DIRECT else SETTINGS, listOf("toggle_setting"), if (dnd) "" else "нужен доступ к режиму"),
            Entry("Wi‑Fi", if (sdk < 29) DIRECT else CONFIRM, listOf("toggle_setting"), if (sdk < 29) "" else "панель Wi‑Fi"),
            Entry("Bluetooth", when { !bluetooth -> UNAVAILABLE; sdk <= 32 && connect -> DIRECT; sdk >= 33 && connect -> CONFIRM; else -> SETTINGS }, listOf("toggle_setting"),
                when { !bluetooth -> "нет Bluetooth"; sdk >= 33 && connect -> "включение — системный запрос, выключение — в настройках"; !connect -> "нет разрешения «Устройства поблизости»"; else -> "" }),
            Entry("мобильный интернет", if (sdk >= 29) CONFIRM else SETTINGS, listOf("toggle_setting"), if (sdk >= 29) "панель «Интернет»" else ""),
            Entry("NFC", if (!nfc) UNAVAILABLE else if (sdk >= 29) CONFIRM else SETTINGS, listOf("toggle_setting")),
            Entry("геолокация, авиарежим", SETTINGS, listOf("toggle_setting"), "переключает только система"),
            Entry("точка доступа", SETTINGS, listOf("toggle_setting"), "нет публичного API ни для переключения, ни для состояния"),
            Entry("шторка, быстрые настройки, недавние, домой, назад, стандартные свайпы", service, listOf("open_notifications", "open_quick_settings", "global_action", "home", "back", "swipe_screen"),
                if (screen) "" else "включите службу Luma в специальных возможностях"),
            Entry("блокировка экрана, снимок экрана", if (screen && sdk >= 28) DIRECT else UNAVAILABLE, listOf("global_action"), if (sdk < 28) "нужен Android 9+" else ""),
            Entry("приложения, ссылки, страницы настроек", DIRECT, listOf("open_app", "open_url", "open_settings", "list_apps")),
            Entry("будильник, таймер", if (clock) DIRECT else UNAVAILABLE, listOf("set_alarm", "set_timer", "show_alarms"), if (clock) "таймер без проверки: Android не показывает список таймеров" else "нет приложения часов"),
            Entry("событие календаря", if (calendar) CONFIRM else UNAVAILABLE, listOf("create_event"), if (calendar) "заполненная форма, пользователь сохраняет" else "нет приложения календаря"),
            Entry("напоминания Luma", if (notify) DIRECT else SETTINGS, listOf("create_reminder", "list_reminders", "cancel_reminder"),
                listOfNotNull(if (!notify) "уведомления Luma выключены" else null, if (!exact) "время приблизительное (до 10 мин)" else null).joinToString("; ")),
            Entry("буфер обмена: запись", DIRECT, listOf("clipboard")),
            Entry("буфер обмена: чтение", if (sdk >= 29) UNAVAILABLE else DIRECT, listOf("clipboard"), if (sdk >= 29) "Android 10+ даёт читать буфер только приложению на экране" else ""),
            Entry("файлы workspace:/", when { !workspace.usable -> UNAVAILABLE; workspace.canWrite -> DIRECT; else -> SETTINGS }, listOf("write_text", "read_text", "list_files"),
                when (workspace.status) { WorkspaceInfo.NOT_SELECTED -> "папка не выбрана"; WorkspaceInfo.LOST -> "доступ потерян"; WorkspaceInfo.READ_ONLY -> "только чтение"; else -> "" }),
            Entry("управление экраном (запасной путь)", service, listOf("screen", "tap", "long_press", "set_text", "scroll", "swipe", "tap_point"), if (screen) "" else "служба Luma выключена"),
        )
    }
    fun prompt(entries: List<Entry>): String {
        fun line(level: String, title: String) = entries.filter { it.level == level }.takeIf { it.isNotEmpty() }
            ?.joinToString("; ", "$title: ") { it.feature + if (it.note.isNotEmpty()) " (${it.note})" else "" }
        return listOfNotNull(line(DIRECT, "Напрямую"), line(CONFIRM, "Через системное подтверждение или панель (needs_confirmation)"),
            line(SETTINGS, "Только открыть настройки, переключает пользователь (needs_user_action)"), line(UNAVAILABLE, "Недоступно")).joinToString("\n")
    }
    fun forTool(entries: List<Entry>, tool: String): List<Entry> = entries.filter { tool in it.tools }
}

/** Everything about the phone that the agent's prompt needs, gathered once when a task starts. */
data class TaskEnvironment(val workspace: WorkspaceInfo, val capabilities: List<DeviceCapabilities.Entry>, val now: ZonedDateTime) {
    fun prompt(): String = "Сейчас: ${now.toLocalDateTime().toString().take(16)}, ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))}, часовой пояс ${now.zone.id} (UTC${now.offset.id.replace("Z", "+00:00")})." +
        "\nУстройство: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})." +
        "\n" + workspace.prompt() + "\nВозможности устройства:\n" + DeviceCapabilities.prompt(capabilities)
    companion object {
        fun capture(context: Context, screen: Boolean, folder: String): TaskEnvironment {
            val workspace = Workspace.describe(context, folder)
            return TaskEnvironment(workspace, DeviceCapabilities.scan(context, screen, workspace), ZonedDateTime.now())
        }
    }
}
