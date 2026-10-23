package dev.luma.agent.core

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.json.JSONObject

/** Permission requirements are per operation, never the entire settings bundle. */
object AccessPolicy {
    data class Requirement(val id: String, val permissions: List<String> = emptyList())
    fun requirements(name: String, args: JSONObject, sdk: Int = Build.VERSION.SDK_INT): List<Requirement> {
        fun runtime(id: String, vararg permissions: String) = listOf(Requirement(id, permissions.toList()))
        if (name in PhoneTools.names) {
            val id = if (name == "list_media") PhoneTools.mediaAccess(args.optString("type", "image")) else PhoneTools.ACCESS[name]!!
            return listOf(Requirement(id, PhoneTools.permissions(name, args, sdk)))
        }
        return when (name) {
            "request_access" -> buildList {
                if (args.optString("access") == "background_location") add(Requirement("location", listOf(Manifest.permission.ACCESS_COARSE_LOCATION)))
                add(Requirement(args.optString("access"), OptionalAccess.item(args.optString("access"))?.runtime(sdk).orEmpty()))
            }
            "flashlight" -> if (args.optString("state") != "status") runtime("camera", Manifest.permission.CAMERA) else emptyList()
            "brightness" -> if (args.has("level") || args.has("auto")) listOf(Requirement("write_settings")) else emptyList()
            "toggle_setting" -> if (args.optString("state") == "status") emptyList() else when (args.optString("setting")) {
                "auto_rotate" -> listOf(Requirement("write_settings"))
                "do_not_disturb" -> listOf(Requirement("dnd"))
                "bluetooth" -> if (sdk >= 31) runtime("bluetooth", Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
                else -> emptyList()
            }
            "ringer_mode" -> if (args.optString("mode") == "silent") listOf(Requirement("dnd")) else emptyList()
            "create_reminder" -> buildList { if (sdk >= 33) add(Requirement("notifications")); if (sdk >= 31) add(Requirement("exact_alarms")) }
            "capture_photo", "capture_video" -> runtime("camera", Manifest.permission.CAMERA)
            "record_audio" -> runtime("microphone", Manifest.permission.RECORD_AUDIO)
            "add_contact", "delete_contact" -> runtime("contacts", Manifest.permission.WRITE_CONTACTS)
            "insert_calendar_event", "delete_calendar_event" -> runtime("calendar", Manifest.permission.WRITE_CALENDAR)
            "list_calendars" -> runtime("calendar", Manifest.permission.READ_CALENDAR)
            "phone_info" -> runtime("phone", Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
            "wait_sms" -> runtime("sms", Manifest.permission.RECEIVE_SMS)
            "read_notifications", "dismiss_notification" -> listOf(Requirement("notification_listener"))
            "read_sensor" -> when (args.optString("sensor", "steps")) {
                "heart_rate" -> runtime("sensors", Manifest.permission.BODY_SENSORS)
                else -> if (sdk >= 29) runtime("activity", Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
            }
            "scan_bluetooth" -> if (sdk >= 31) runtime("nearby_scan", Manifest.permission.BLUETOOTH_SCAN) + runtime("bluetooth", Manifest.permission.BLUETOOTH_CONNECT)
                else runtime("location", Manifest.permission.ACCESS_FINE_LOCATION)
            "bluetooth_discoverable" -> if (sdk >= 31) runtime("nearby_scan", Manifest.permission.BLUETOOTH_ADVERTISE) else emptyList()
            "wifi_scan" -> runtime("location", Manifest.permission.ACCESS_FINE_LOCATION)
            "wifi_connect" -> if (sdk >= 33) runtime("nearby_wifi", Manifest.permission.NEARBY_WIFI_DEVICES) else runtime("location", Manifest.permission.ACCESS_FINE_LOCATION)
            "storage_list", "storage_read", "storage_write", "storage_delete", "install_apk" -> buildList {
                if (sdk >= 30) add(Requirement("all_files")) else add(Requirement("photos", listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)))
                if (name == "install_apk") add(Requirement("install"))
            }
            "delete_media" -> buildList {
                add(Requirement(PhoneTools.mediaAccess(args.optString("type", "image")), PhoneTools.permissions("list_media", args, sdk)))
                if (sdk >= 31) add(Requirement("manage_media"))
            }
            "show_notification" -> buildList {
                if (sdk >= 33) add(Requirement("notifications"))
                if (sdk >= 34 && args.optBoolean("full_screen")) add(Requirement("full_screen"))
            }
            "get_background_location" -> runtime("location", Manifest.permission.ACCESS_COARSE_LOCATION) +
                if (sdk >= 29) runtime("background_location", Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()
            "prepare_background" -> listOf("battery", "background", "data_saver").map { Requirement(it) }
            "show_floating_note" -> listOf(Requirement("overlay"))
            else -> emptyList()
        }
    }

    /** Only alternatives with an executable implementation get the extra button. */
    fun fallback(name: String, args: JSONObject, access: String): String? = when (name) {
        "screen" -> if (access == "screenshots") "Прочитать только элементы экрана без изображения" else null
        "call_number" -> "Открыть номер в звонилке; звонок подтвердите сами"
        "send_sms" -> "Открыть заполненный черновик SMS; отправку подтвердите сами"
        "insert_calendar_event" -> "Открыть форму события в календаре"
        "create_reminder" -> if (access == "exact_alarms") "Сохранить напоминание с приблизительным временем" else null
        "show_notification" -> if (access == "full_screen") "Показать обычное уведомление" else null
        "brightness", "ringer_mode", "toggle_setting" -> "Открыть системные настройки для ручного изменения"
        "prepare_background" -> "Продолжить с текущими ограничениями Android"
        "delete_media" -> if (Build.VERSION.SDK_INT >= 30 && access == "manage_media") "Подтвердить удаление в системном диалоге Android" else null
        else -> null
    }

    fun needed(context: Context, name: String, args: JSONObject): List<PermissionNeed> = requirements(name, args).mapNotNull { req ->
        OptionalAccess.toolNeed(context, req.id, req.permissions)?.copy(fallback = fallback(name, args, req.id))
    }
}
