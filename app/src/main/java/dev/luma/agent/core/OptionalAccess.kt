package dev.luma.agent.core

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings

/**
 * Every optional access Luma can ask Android for, in three groups: ones single Luma actions need, ones that keep tasks
 * running in the background, and the rest Android offers to apps, granted in advance. A permission alone adds nothing:
 * the agent can use it only through a Luma tool, so each item says whether one uses it now.
 */
object OptionalAccess {
    enum class Group(val title: String, val note: String) {
        ACTIONS("Нужны отдельным действиям Luma", "Без них Luma откроет нужные настройки и попросит вас переключить вручную."),
        BACKGROUND("Работа в фоне", "Чтобы Android не приостанавливал долгую задачу или ответ локальной модели, когда экран выключен."),
        RESERVE("Данные и возможности телефона", "Агент запрашивает доступ перед конкретным действием. Камеру, запись, звонки, отправку и удаление подтверждаете вы. Каждый доступ можно отозвать в Android.")
    }

    /**
     * One access. Runtime ones are [permissions] (by Android version) asked in Android's dialog; special ones are
     * checked with [special] and granted on the system [screens] for Luma.
     */
    class Item(val id: String, val title: String, val purpose: String, val group: Group, val minSdk: Int = 1,
        val permissions: (Int) -> List<String> = { emptyList() }, val special: ((Context) -> Boolean)? = null,
        val screens: (Context) -> List<Intent> = { appDetails(it) }) {
        fun runtime(sdk: Int = Build.VERSION.SDK_INT) = permissions(sdk)
        fun granted(context: Context): Boolean = special?.let { runCatching { it(context) }.getOrDefault(false) }
            ?: runtime().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun app(context: Context) = Uri.fromParts("package", context.packageName, null)
    fun appDetails(context: Context) = listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app(context)))
    private fun screen(context: Context, action: String, withPackage: Boolean = true) =
        listOf(if (withPackage) Intent(action, app(context)) else Intent(action)) + appDetails(context)

    private val all = listOf(
        // Used by Luma's actions now.
        Item("notifications", "Уведомления", "Статус задачи и «Стоп» в шторке, напоминания Luma", Group.ACTIONS, 33,
            permissions = { listOf(Manifest.permission.POST_NOTIFICATIONS) }),
        Item("write_settings", "Изменение системных настроек", "Яркость и автоповорот", Group.ACTIONS,
            special = { Settings.System.canWrite(it) }, screens = { screen(it, Settings.ACTION_MANAGE_WRITE_SETTINGS) }),
        Item("dnd", "«Не беспокоить»", "Режим «Не беспокоить» и звонок в нём", Group.ACTIONS,
            special = { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            screens = { screen(it, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, withPackage = false) }),
        Item("bluetooth", "Устройства поблизости: Bluetooth", "Включение Bluetooth одним запросом", Group.ACTIONS, 31,
            permissions = { listOf(Manifest.permission.BLUETOOTH_CONNECT) }),
        Item("exact_alarms", "Будильники и напоминания", "Напоминания Luma точно в срок", Group.ACTIONS, 31,
            special = { Build.VERSION.SDK_INT < 31 || it.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() },
            screens = { screen(it, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }),
        // Background work.
        Item("battery", "Без ограничений батареи", "Отключить энергосбережение для Luma", Group.BACKGROUND,
            special = { BackgroundAccess.status(it).unrestrictedBattery }, screens = { BackgroundAccess.batteryRequest(it) }),
        Item("background", "Фоновая активность", "Не ограничена в настройках Android", Group.BACKGROUND, 28,
            special = { !BackgroundAccess.status(it).backgroundRestricted }, screens = { BackgroundAccess.appSettings(it) }),
        Item("data_saver", "Интернет в фоне при «Экономии трафика»", "Исключение для Luma", Group.BACKGROUND,
            special = { !BackgroundAccess.status(it).dataSaverBlocks }, screens = { BackgroundAccess.dataSaver(it) }),
        // Everything else Android lets an app have.
        Item("camera", "Камера", "Фото и видео", Group.RESERVE, permissions = { listOf(Manifest.permission.CAMERA) }),
        Item("microphone", "Микрофон", "Запись звука и голосовой ввод", Group.RESERVE, permissions = { listOf(Manifest.permission.RECORD_AUDIO) }),
        Item("location", "Местоположение", "Точное и приблизительное", Group.RESERVE,
            special = { c -> c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED },
            permissions = { listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) }),
        Item("background_location", "Местоположение в фоне", "Всегда, а не только при открытой Luma; сначала нужно обычное местоположение", Group.RESERVE, 29,
            permissions = { listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }),
        Item("contacts", "Контакты", "Чтение и изменение", Group.RESERVE,
            permissions = { listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS) }),
        Item("calendar", "Календарь", "Чтение и изменение событий", Group.RESERVE,
            permissions = { listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) }),
        Item("phone", "Телефон", "Звонки и состояние телефона", Group.RESERVE,
            permissions = { listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS) }),
        Item("call_log", "Журнал звонков", "Чтение", Group.RESERVE, permissions = { listOf(Manifest.permission.READ_CALL_LOG) }),
        Item("sms", "SMS", "Отправка и чтение", Group.RESERVE,
            permissions = { listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS) }),
        Item("photos", "Фото и видео", "Медиатека телефона", Group.RESERVE,
            permissions = { sdk -> if (sdk >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE) }),
        Item("audio", "Музыка и аудио", "Аудиофайлы телефона", Group.RESERVE, 33, permissions = { listOf(Manifest.permission.READ_MEDIA_AUDIO) }),
        Item("activity", "Физическая активность", "Шаги и тип движения", Group.RESERVE, 29, permissions = { listOf(Manifest.permission.ACTIVITY_RECOGNITION) }),
        Item("sensors", "Датчики тела", "Пульс и другие датчики часов и браслетов", Group.RESERVE, permissions = { listOf(Manifest.permission.BODY_SENSORS) }),
        Item("nearby_scan", "Устройства поблизости: поиск", "Поиск и видимость Bluetooth-устройств", Group.RESERVE, 31,
            permissions = { listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE) }),
        Item("nearby_wifi", "Wi‑Fi поблизости", "Устройства и сети Wi‑Fi рядом", Group.RESERVE, 33, permissions = { listOf(Manifest.permission.NEARBY_WIFI_DEVICES) }),
        Item("all_files", "Доступ ко всем файлам", "Вся память телефона, а не только выбранная папка", Group.RESERVE, 30,
            special = { Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager() }, screens = { screen(it, Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) }),
        Item("overlay", "Поверх других приложений", "Окна Luma поверх других приложений (агенту хватает специальных возможностей)", Group.RESERVE,
            special = { Settings.canDrawOverlays(it) }, screens = { screen(it, Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }),
        Item("notification_listener", "Доступ к уведомлениям", "Чтение уведомлений других приложений", Group.RESERVE,
            special = { c -> Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty().split(':').any { it.startsWith(c.packageName + "/") } },
            screens = { c -> (if (Build.VERSION.SDK_INT >= 30) listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, "dev.luma.agent.LumaNotificationListener").flattenToString())) else emptyList()) +
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) + appDetails(c) }),
        Item("usage", "Статистика использования", "Какие приложения и сколько времени открыты", Group.RESERVE,
            special = { c -> val ops = c.getSystemService(AppOpsManager::class.java)
                @Suppress("DEPRECATION") (if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName)
                    else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName)) == AppOpsManager.MODE_ALLOWED },
            screens = { screen(it, Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackage = false) }),
        Item("install", "Установка приложений", "Установка APK, например обновлений Luma", Group.RESERVE,
            special = { it.packageManager.canRequestPackageInstalls() }, screens = { screen(it, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }),
        Item("manage_media", "Управление медиафайлами", "Изменение и удаление фото и видео без отдельного вопроса", Group.RESERVE, 31,
            special = { Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(it) }, screens = { screen(it, Settings.ACTION_REQUEST_MANAGE_MEDIA) }),
        Item("full_screen", "Полноэкранные уведомления", "Важные уведомления поверх экрана блокировки", Group.RESERVE, 34,
            special = { Build.VERSION.SDK_INT < 34 || it.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() },
            screens = { screen(it, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }),
    )

    fun item(id: String): Item? = (all + listOf(Item("accessibility", "Управление экраном", "Чтение экрана, нажатия и ввод текста", Group.ACTIONS,
        special = { dev.luma.agent.AgentAccessibilityService.instance != null }, screens = { listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
        Item("workspace", "Выбранная папка", "Выберите папку для файлов задачи", Group.ACTIONS, special = { c ->
            (c.applicationContext as? dev.luma.agent.LumaApp)?.graph?.vault?.state?.value?.folder?.let { Workspace.describe(c, it).usable } == true }),
        Item("screenshots", "Снимки для модели", "Передать изображение экрана выбранной модели", Group.ACTIONS, special = { c ->
            (c.applicationContext as? dev.luma.agent.LumaApp)?.graph?.vault?.state?.value?.screenshots == true }))).firstOrNull { it.id == id }
    /** The access a tool needs, or null when it is already granted or unknown on this version. */
    fun need(context: Context, id: String): PermissionNeed? {
        val item = items().firstOrNull { it.id == id } ?: return null
        if (item.granted(context)) return null
        return PermissionNeed(item.id, item.title, item.purpose, item.runtime())
    }
    fun granted(context: Context, id: String): Boolean = if (id in setOf("accessibility", "workspace", "screenshots")) item(id)!!.granted(context)
        else items().firstOrNull { it.id == id }?.granted(context) ?: (item(id) != null)
    /** Whether a tool's exact permissions are granted: its minimal [perms], or the access's special check when it has none. */
    fun toolGranted(context: Context, id: String, perms: List<String>): Boolean =
        if (perms.isNotEmpty()) perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } else granted(context, id)
    /** The card to show for a blocked tool: the access's name and purpose, but only the permissions that tool needs. */
    fun toolNeed(context: Context, id: String, perms: List<String>): PermissionNeed? {
        if (toolGranted(context, id, perms)) return null
        val item = item(id) ?: throw UserError("Неизвестный доступ: $id")
        return PermissionNeed(id, item.title, item.purpose, perms)
    }
    /** The accesses this Android version has. */
    fun items(sdk: Int = Build.VERSION.SDK_INT): List<Item> = all.filter { sdk >= it.minSdk && (it.special != null || it.permissions(sdk).isNotEmpty()) }
    /** Runtime permissions of [group] still to ask, for «Запросить все» (background location must come after the rest). */
    fun missing(context: Context, group: Group): List<String> = items().filter { it.group == group && it.runtime().isNotEmpty() && it.id != "background_location" }
        .flatMap { it.runtime() }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.distinct()
}
