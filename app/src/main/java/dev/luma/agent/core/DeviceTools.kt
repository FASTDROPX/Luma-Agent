package dev.luma.agent.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Direct phone actions through public Android APIs. Each one reads the state back and reports what
 * actually happened; where Android allows only a system panel or a settings page, it says so.
 */
class DeviceTools(private val context: Context) {
    private fun <T> service(type: Class<T>): T? = context.getSystemService(type)

    suspend fun run(name: String, args: JSONObject): JSONObject = when (name) {
        "flashlight" -> flashlight(args.getString("state"))
        "volume" -> volume(args)
        "ringer_mode" -> ringer(args.getString("mode"))
        "brightness" -> brightness(args)
        "toggle_setting" -> toggle(args.getString("setting"), args.getString("state"), args.optString("mode"))
        "media" -> media(args.getString("action"))
        "clipboard" -> clipboard(args.getString("action"), args.optString("text"))
        "device_status" -> status(args.optJSONArray("include"))
        "list_apps" -> listApps(args.getString("query"))
        "open_app" -> openApp(args)
        "open_url" -> openUrl(args.getString("url"))
        "open_settings" -> openSettings(args.getString("page"))
        "set_timer" -> timer(args)
        "set_alarm" -> alarm(args)
        "show_alarms" -> { val handler = launch(Intent(if (args.optString("view") == "timers") AlarmClock.ACTION_SHOW_TIMERS else AlarmClock.ACTION_SHOW_ALARMS))
            done(null, message = "Открыто приложение часов.").put("handler", handler) }
        "create_event" -> event(args)
        else -> throw ToolFailure(ToolStatus.ERROR, "Неизвестное действие устройства.")
    }

    private fun done(verified: Boolean?, state: JSONObject? = null, message: String? = null) = JSONObject().put("status", ToolStatus.DONE).apply {
        verified?.let { put("verified", it) }; state?.let { put("state", it) }; message?.let { put("message", it) }
    }
    private fun waiting(status: String, message: String, userAction: String, state: JSONObject? = null, code: String? = null) =
        JSONObject().put("status", status).put("message", message).put("userAction", userAction).apply { state?.let { put("state", it) }; code?.let { put("code", it) } }
    private fun onOff(value: Boolean?): Any = when (value) { null -> JSONObject.NULL; true -> "on"; false -> "off" }
    private fun target(state: String, current: Boolean?): Boolean? = when (state) { "on" -> true; "off" -> false; "toggle" -> current?.not(); else -> null }

    /** Starts an activity from Luma (allowed while Luma's screen or its Accessibility service is active). Returns the handler package when visible. */
    internal suspend fun launch(intent: Intent): String? = withContext(Dispatchers.Main.immediate) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handler = runCatching { context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        try { context.startActivity(intent) }
        catch (e: ActivityNotFoundException) { throw ToolFailure(ToolStatus.UNSUPPORTED, "На телефоне нет приложения для этого действия.") }
        catch (e: SecurityException) { throw ToolFailure(ToolStatus.DENIED, "Android не разрешил открыть это окно.") }
        handler
    }

    // ---------- Flashlight ----------
    private suspend fun flashlight(state: String): JSONObject = withContext(Dispatchers.Main.immediate) {
        val cameras = service(CameraManager::class.java) ?: throw ToolFailure(ToolStatus.UNSUPPORTED, "Камера недоступна.")
        val id = runCatching { cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
            ?: throw ToolFailure(ToolStatus.UNSUPPORTED, "На этом устройстве нет вспышки для фонарика.")
        val torch = MutableStateFlow<Boolean?>(null); val unavailable = MutableStateFlow(false)
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) { unavailable.value = false; torch.value = enabled } }
            override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == id) unavailable.value = true }
        }
        cameras.registerTorchCallback(callback, null)
        try {
            // Android reports the current torch state right after registration.
            val current = withTimeoutOrNull(1000) { torch.first { it != null } }
            val wanted = target(state, current)
            if (state == "status") return@withContext done(current != null, JSONObject().put("flashlight", onOff(current)))
            if (wanted == null) return@withContext JSONObject().put("status", ToolStatus.UNKNOWN).put("message", "Не удалось узнать состояние фонарика для переключения. Укажите on или off.")
            if (current == wanted) return@withContext done(true, JSONObject().put("flashlight", onOff(current)), "Фонарик уже ${if (wanted) "включён" else "выключен"}.")
            try { cameras.setTorchMode(id, wanted) }
            catch (e: Exception) { throw ToolFailure(ToolStatus.DENIED, "Фонарик сейчас недоступен: камеру использует другое приложение или его ограничил Android.") }
            val after = withTimeoutOrNull(1500) { torch.first { it == wanted } }
            if (after == wanted) done(true, JSONObject().put("flashlight", onOff(after)))
            else JSONObject().put("status", ToolStatus.UNKNOWN).put("state", JSONObject().put("flashlight", onOff(torch.value)))
                .put("message", "Команда отправлена, но Android не подтвердил новое состояние фонарика.")
        } finally { cameras.unregisterTorchCallback(callback) }
    }

    // ---------- Sound ----------
    private val streams = mapOf("media" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING, "notification" to AudioManager.STREAM_NOTIFICATION,
        "alarm" to AudioManager.STREAM_ALARM, "call" to AudioManager.STREAM_VOICE_CALL, "system" to AudioManager.STREAM_SYSTEM)
    private fun audio() = service(AudioManager::class.java) ?: throw ToolFailure(ToolStatus.UNSUPPORTED, "Управление звуком недоступно.")
    private fun range(audio: AudioManager, stream: Int): IntRange {
        val min = if (Build.VERSION.SDK_INT >= 28) runCatching { audio.getStreamMinVolume(stream) }.getOrDefault(0) else 0
        return min..audio.getStreamMaxVolume(stream).coerceAtLeast(min)
    }
    private fun volumeState(audio: AudioManager, name: String, stream: Int): JSONObject {
        val r = range(audio, stream); val index = audio.getStreamVolume(stream)
        val percent = if (r.last == r.first) 100 else ((index - r.first) * 100.0 / (r.last - r.first)).roundToInt()
        return JSONObject().put("stream", name).put("level", percent).put("index", index).put("max", r.last)
            .put("muted", runCatching { audio.isStreamMute(stream) }.getOrDefault(index == 0))
    }
    private fun dndDenied() = ToolFailure(ToolStatus.DENIED, "Android не даёт менять этот звук в режиме «Не беспокоить» без доступа Luma к этому режиму.",
        "Разрешите доступ: Luma → «Доступ» → «Системные действия» → «Не беспокоить» (или open_settings page=dnd_access).", "dnd_access")
    private suspend fun volume(args: JSONObject): JSONObject {
        val audio = audio(); val name = args.optString("stream").ifEmpty { "media" }; val stream = streams.getValue(name)
        val before = volumeState(audio, name, stream)
        val actions = listOf("level", "step").count(args::has)
        if (actions == 0 && !args.has("mute")) return done(true, before)
        if (actions > 1) throw ToolFailure(ToolStatus.ERROR, "Укажите либо level, либо step.")
        if (audio.isVolumeFixed) throw ToolFailure(ToolStatus.UNSUPPORTED, "На этом устройстве громкость фиксирована.")
        val r = range(audio, stream); var wanted: Int? = null
        try {
            if (args.has("level")) {
                wanted = r.first + ((r.last - r.first) * args.getInt("level") / 100.0).roundToInt()
                audio.setStreamVolume(stream, wanted, AudioManager.FLAG_SHOW_UI)
                if (wanted > 0 && !args.has("mute")) runCatching { if (audio.isStreamMute(stream)) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
            }
            if (args.has("step")) audio.adjustStreamVolume(stream, if (args.getString("step") == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            if (args.has("mute")) audio.adjustStreamVolume(stream, if (args.getBoolean("mute")) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) { throw dndDenied() }
        delay(150)
        val after = volumeState(audio, name, stream)
        val verified = (wanted == null || after.getInt("index") == wanted) && (!args.has("mute") || after.getBoolean("muted") == args.getBoolean("mute")) &&
            (!args.has("step") || after.getInt("index") != before.getInt("index") || before.getInt("index") in setOf(r.first, r.last))
        return done(verified, after, if (verified) null else "Android установил ${after.getInt("level")} % — ограничение системы (например, безопасная громкость в наушниках).")
    }
    private fun ringerName(mode: Int) = when (mode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
    private suspend fun ringer(mode: String): JSONObject {
        val audio = audio()
        if (mode == "status") return done(true, JSONObject().put("ringer", ringerName(audio.ringerMode)))
        val value = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        try { audio.ringerMode = value } catch (e: SecurityException) { throw dndDenied() }
        delay(150)
        val actual = ringerName(audio.ringerMode)
        return done(actual == mode, JSONObject().put("ringer", actual), if (actual == mode) null else "Android оставил режим $actual.")
    }

    // ---------- Display settings that need «Изменение системных настроек» ----------
    private fun canWrite() = Settings.System.canWrite(context)
    private fun writeSettingsNeeded(state: JSONObject) = waiting(ToolStatus.NEEDS_USER_ACTION, "Для этого Luma нужно разрешение «Изменение системных настроек». Сейчас ничего не изменено.",
        "Разрешите: Luma → «Доступ» → «Системные действия» → «Изменение системных настроек» (или open_settings page=write_settings).", state, "write_settings")
    private fun brightnessState(): JSONObject = JSONObject()
        .put("level", runCatching { (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100.0 / 255).roundToInt() }.getOrNull() ?: JSONObject.NULL)
        .put("auto", runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }.getOrNull() ?: JSONObject.NULL)
    private suspend fun brightness(args: JSONObject): JSONObject {
        val before = brightnessState()
        if (!args.has("level") && !args.has("auto")) return done(true, before.put("note", "Шкала яркости зависит от устройства; проценты приблизительные."))
        if (!canWrite()) return writeSettingsNeeded(before)
        val resolver = context.contentResolver
        val auto = if (args.has("auto")) args.getBoolean("auto") else false
        // A manual level is ignored while automatic brightness is on, so a level without "auto" switches to manual.
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val raw = if (args.has("level")) (args.getInt("level") * 255 / 100.0).roundToInt().coerceIn(1, 255) else null
        raw?.let { Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, it) }
        delay(100)
        val after = brightnessState()
        val verified = after.opt("auto") == auto && (raw == null || runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull() == raw)
        return done(verified, after.put("note", "Шкала яркости зависит от устройства; проценты приблизительные."))
    }

    // ---------- Toggles ----------
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private suspend fun poll(wanted: Boolean, millis: Long, read: () -> Boolean?): Boolean? {
        val until = SystemClock.elapsedRealtime() + millis
        while (true) { val value = read(); if (value == wanted || SystemClock.elapsedRealtime() >= until) return value; delay(250) }
    }
    @SuppressLint("MissingPermission")
    internal fun toggleState(setting: String): Boolean? = runCatching {
        when (setting) {
            "wifi" -> service(WifiManager::class.java)?.isWifiEnabled
            "bluetooth" -> bluetooth()?.isEnabled
            "mobile_data" -> if (Build.VERSION.SDK_INT >= 26) service(TelephonyManager::class.java)?.isDataEnabled else null
            "location" -> if (Build.VERSION.SDK_INT >= 28) service(LocationManager::class.java)?.isLocationEnabled else legacyLocation()
            "airplane_mode" -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            "nfc" -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
            "do_not_disturb" -> service(NotificationManager::class.java)?.currentInterruptionFilter?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            "auto_rotate" -> Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            else -> null // hotspot: no public API reports it
        }
    }.getOrNull()
    private fun bluetooth(): BluetoothAdapter? = service(BluetoothManager::class.java)?.adapter
    @Suppress("DEPRECATION") private fun legacyLocation() = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
    /** Works only on Android 9 and older; newer versions ignore it for apps. */
    @Suppress("DEPRECATION") private fun legacyWifi(wifi: WifiManager, on: Boolean) = Build.VERSION.SDK_INT < 29 && wifi.setWifiEnabled(on)
    /** Works only on Android 12L and older (Android 12+ also needs «Устройства поблизости»). */
    @SuppressLint("MissingPermission") @Suppress("DEPRECATION")
    private fun legacyBluetooth(adapter: BluetoothAdapter, on: Boolean) = Build.VERSION.SDK_INT <= 32 && runCatching { if (on) adapter.enable() else adapter.disable() }.getOrDefault(false)
    private fun stateJson(setting: String, value: Boolean? = toggleState(setting)) = JSONObject().put(setting, onOff(value))

    @SuppressLint("MissingPermission")
    private suspend fun toggle(setting: String, state: String, mode: String): JSONObject {
        val current = toggleState(setting)
        if (state == "status") return done(current != null, stateJson(setting, current), if (current == null) "Android не сообщает это состояние." else null)
        val wanted = target(state, current)
            ?: return JSONObject().put("status", ToolStatus.UNKNOWN).put("message", "Текущее состояние неизвестно, поэтому переключить нельзя. Укажите on или off.").put("state", stateJson(setting, null))
        if (current == wanted && !(setting == "do_not_disturb" && wanted && mode.isNotEmpty())) return done(true, stateJson(setting, current), "Уже ${if (wanted) "включено" else "выключено"}.")
        val verb = if (wanted) "включите" else "выключите"
        return when (setting) {
            "wifi" -> {
                val wifi = service(WifiManager::class.java) ?: throw ToolFailure(ToolStatus.UNSUPPORTED, "Wi‑Fi недоступен.")
                if (legacyWifi(wifi, wanted)) verifiedToggle(setting, wanted, 5000)
                else { launch(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
                    waiting(ToolStatus.NEEDS_CONFIRMATION, "Android 10+ не позволяет приложениям переключать Wi‑Fi. Открыта системная панель Wi‑Fi.", "В панели $verb Wi‑Fi.", stateJson(setting, current)) }
            }
            "bluetooth" -> {
                val adapter = bluetooth() ?: throw ToolFailure(ToolStatus.UNSUPPORTED, "На устройстве нет Bluetooth.")
                val connect = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
                when {
                    connect && legacyBluetooth(adapter, wanted) -> verifiedToggle(setting, wanted, 6000)
                    wanted && connect -> { launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        waiting(ToolStatus.NEEDS_CONFIRMATION, "Android спросит разрешение включить Bluetooth.", "Нажмите «Разрешить» в системном окне.", stateJson(setting, current)) }
                    else -> { launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        waiting(ToolStatus.NEEDS_USER_ACTION, if (!connect) "У Luma нет разрешения «Устройства поблизости», поэтому открыты настройки Bluetooth."
                            else "Android 13+ не позволяет приложениям выключать Bluetooth. Открыты настройки Bluetooth.", "В настройках $verb Bluetooth." +
                            if (!connect) " Чтобы Luma включала его одним нажатием, разрешите «Устройства поблизости» в разделе «Доступ»." else "", stateJson(setting, current)) }
                }
            }
            "mobile_data" -> { launch(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS))
                waiting(if (Build.VERSION.SDK_INT >= 29) ToolStatus.NEEDS_CONFIRMATION else ToolStatus.NEEDS_USER_ACTION,
                    "Мобильный интернет переключает только система. Открыта ${if (Build.VERSION.SDK_INT >= 29) "панель «Интернет»" else "страница настроек сети"}.", "$verb «Мобильный интернет».", stateJson(setting, current)) }
            "location" -> { launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                waiting(ToolStatus.NEEDS_USER_ACTION, "Android не позволяет приложениям переключать геолокацию. Открыты настройки местоположения.", "В настройках $verb геолокацию.", stateJson(setting, current)) }
            "airplane_mode" -> { launch(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
                waiting(ToolStatus.NEEDS_USER_ACTION, "Авиарежим переключает только система. Открыты настройки сети.", "$verb «Режим полёта».", stateJson(setting, current)) }
            "hotspot" -> { launch(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                waiting(ToolStatus.NEEDS_USER_ACTION, "Обычное приложение не может включать точку доступа и не видит её состояние. Открыты настройки сети.",
                    "Откройте «Точка доступа и модем» и $verb точку доступа.", stateJson(setting, null), "system_only") }
            "nfc" -> {
                NfcAdapter.getDefaultAdapter(context) ?: throw ToolFailure(ToolStatus.UNSUPPORTED, "На устройстве нет NFC.")
                launch(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS))
                waiting(if (Build.VERSION.SDK_INT >= 29) ToolStatus.NEEDS_CONFIRMATION else ToolStatus.NEEDS_USER_ACTION, "NFC переключает только система. Открыта панель NFC.", "$verb NFC.", stateJson(setting, current))
            }
            "do_not_disturb" -> {
                val notifications = service(NotificationManager::class.java) ?: throw ToolFailure(ToolStatus.UNSUPPORTED, "Режим «Не беспокоить» недоступен.")
                if (!notifications.isNotificationPolicyAccessGranted) return waiting(ToolStatus.NEEDS_USER_ACTION, "Luma нужен доступ к режиму «Не беспокоить». Сейчас ничего не изменено.",
                    "Разрешите: Luma → «Доступ» → «Системные действия» → «Не беспокоить» (или open_settings page=dnd_access).", stateJson(setting, current), "dnd_access")
                val filter = if (!wanted) NotificationManager.INTERRUPTION_FILTER_ALL else when (mode) {
                    "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS; "silence" -> NotificationManager.INTERRUPTION_FILTER_NONE; else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY }
                notifications.setInterruptionFilter(filter)
                val actual = withTimeoutOrNull(2000) { while (notifications.currentInterruptionFilter != filter) delay(150); filter }
                done(actual == filter, stateJson(setting).put("filter", dndName(notifications.currentInterruptionFilter)))
            }
            "auto_rotate" -> {
                if (!canWrite()) return writeSettingsNeeded(stateJson(setting, current))
                Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (wanted) 1 else 0)
                verifiedToggle(setting, wanted, 1000)
            }
            else -> throw ToolFailure(ToolStatus.UNSUPPORTED, "Неизвестная настройка.")
        }
    }
    private suspend fun verifiedToggle(setting: String, wanted: Boolean, millis: Long): JSONObject {
        val actual = poll(wanted, millis) { toggleState(setting) }
        return if (actual == wanted) done(true, stateJson(setting, actual))
        else JSONObject().put("status", ToolStatus.UNKNOWN).put("state", stateJson(setting, actual)).put("message", "Android принял команду, но новое состояние не подтвердилось. Проверьте device_status, не повторяя действие.")
    }
    private fun dndName(filter: Int) = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"; NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"; NotificationManager.INTERRUPTION_FILTER_NONE -> "silence"; else -> "unknown" }

    // ---------- Media and clipboard ----------
    private suspend fun media(action: String): JSONObject {
        val audio = audio()
        if (action == "status") return done(true, JSONObject().put("playing", audio.isMusicActive))
        val code = when (action) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "stop" -> KeyEvent.KEYCODE_MEDIA_STOP; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
        val was = audio.isMusicActive
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        val expected = when (action) { "play" -> true; "pause", "stop" -> false; "play_pause" -> !was; else -> null }
        if (expected == null) return done(false, JSONObject().put("playing", audio.isMusicActive), "Команда передана активному плееру; смену трека Android проверить не даёт.")
        val actual = poll(expected, 1500) { audio.isMusicActive }
        return done(actual == expected, JSONObject().put("playing", actual), if (actual == expected) null else "Плеер не отреагировал или не сообщает состояние.")
    }
    private suspend fun clipboard(action: String, text: String): JSONObject = withContext(Dispatchers.Main.immediate) {
        val clip = service(ClipboardManager::class.java) ?: throw ToolFailure(ToolStatus.UNSUPPORTED, "Буфер обмена недоступен.")
        when (action) {
            "set" -> {
                if (text.isEmpty()) throw ToolFailure(ToolStatus.ERROR, "Укажите text для буфера обмена.")
                clip.setPrimaryClip(ClipData.newPlainText("Luma", text))
                // Android 10+ may hide the clipboard from an app that is not on screen, so the check can be unavailable.
                val back = runCatching { clip.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
                done(back == text, JSONObject().put("length", text.length), if (back == text) null else "Текст записан; Android не даёт перечитать буфер для проверки.")
            }
            "clear" -> {
                if (Build.VERSION.SDK_INT >= 28) clip.clearPrimaryClip() else clip.setPrimaryClip(ClipData.newPlainText("", ""))
                done(Build.VERSION.SDK_INT >= 28 && !clip.hasPrimaryClip())
            }
            else -> {
                val value = runCatching { clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
                when {
                    value != null -> done(true, JSONObject().put("text", value.take(20_000)).put("truncated", value.length > 20_000))
                    // Android 10+ hides the clipboard from apps that are not on screen; an empty answer can mean either.
                    Build.VERSION.SDK_INT >= 29 -> throw ToolFailure(ToolStatus.DENIED, "Android не дал прочитать буфер обмена: на Android 10+ его читает только приложение на экране (или буфер пуст).",
                        "Вставьте нужный текст в сообщение для Luma.")
                    else -> done(true, JSONObject().put("text", ""), "Буфер обмена пуст.")
                }
            }
        }
    }

    // ---------- Device status ----------
    @SuppressLint("MissingPermission")
    private suspend fun status(include: JSONArray?): JSONObject {
        val wanted = (0 until (include?.length() ?: 0)).map { include!!.getString(it) }.toSet().ifEmpty { setOf("battery", "network", "time", "sound", "display", "toggles", "storage", "device", "alarm") }
        val out = JSONObject().put("status", ToolStatus.DONE)
        fun section(name: String, block: () -> Any) { if (name in wanted) out.put(name, runCatching(block).getOrElse { JSONObject.NULL }) }
        section("battery") {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            JSONObject().put("percent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
                .put("charging", service(BatteryManager::class.java)?.isCharging ?: JSONObject.NULL)
                .put("plugged", when (plugged) { BatteryManager.BATTERY_PLUGGED_AC -> "ac"; BatteryManager.BATTERY_PLUGGED_USB -> "usb"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; 0 -> "no"; else -> "other" })
                .put("temperatureC", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 } ?: JSONObject.NULL)
                .put("powerSave", service(PowerManager::class.java)?.isPowerSaveMode ?: JSONObject.NULL)
        }
        section("network") {
            val connectivity = service(ConnectivityManager::class.java)
            val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            JSONObject().put("online", caps != null).apply {
                if (caps != null) {
                    put("transport", JSONArray(listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                        NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet", NetworkCapabilities.TRANSPORT_VPN to "vpn", NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth")
                        .filter { caps.hasTransport(it.first) }.map { it.second }))
                    put("internetVerified", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                }
            }
        }
        section("time") { timeJson() }
        section("sound") { val audio = audio(); JSONObject().put("ringer", ringerName(audio.ringerMode)).put("playing", audio.isMusicActive)
            .put("media", volumeState(audio, "media", AudioManager.STREAM_MUSIC).getInt("level")).put("ring", volumeState(audio, "ring", AudioManager.STREAM_RING).getInt("level"))
            .put("alarm", volumeState(audio, "alarm", AudioManager.STREAM_ALARM).getInt("level"))
            .put("doNotDisturb", service(NotificationManager::class.java)?.currentInterruptionFilter?.let(::dndName) ?: JSONObject.NULL) }
        section("display") { brightnessState().put("autoRotate", onOff(toggleState("auto_rotate")))
            .put("screenOn", service(PowerManager::class.java)?.isInteractive ?: JSONObject.NULL)
            .put("locked", service(KeyguardManager::class.java)?.isKeyguardLocked ?: JSONObject.NULL) }
        section("toggles") { JSONObject().apply { listOf("wifi", "bluetooth", "mobile_data", "location", "airplane_mode", "nfc", "do_not_disturb").forEach { put(it, onOff(toggleState(it))) }
            put("hotspot", JSONObject.NULL) } }
        section("storage") { val fs = StatFs(Environment.getDataDirectory().path)
            JSONObject().put("freeGb", "%.1f".format(java.util.Locale.ROOT, fs.availableBytes / 1e9).toDouble()).put("totalGb", "%.1f".format(java.util.Locale.ROOT, fs.totalBytes / 1e9).toDouble()) }
        section("device") { JSONObject().put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT) }
        section("alarm") { service(AlarmManager::class.java)?.nextAlarmClock?.let { JSONObject().put("next", iso(it.triggerTime)) } ?: JSONObject().put("next", JSONObject.NULL) }
        if ("toggles" in wanted || "display" in wanted) out.put("note", "null — Android не сообщает это значение обычному приложению.")
        return out
    }
    internal fun timeJson(now: ZonedDateTime = ZonedDateTime.now()): JSONObject = JSONObject().put("now", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16))
        .put("weekday", now.dayOfWeek.name.lowercase().take(3)).put("timeZone", now.zone.id).put("utcOffset", now.offset.id)
        .put("format24h", runCatching { android.text.format.DateFormat.is24HourFormat(context) }.getOrDefault(true))
    private fun iso(millis: Long) = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16)

    // ---------- Apps, links, settings ----------
    private data class App(val name: String, val pkg: String)
    private fun apps(): List<App> = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != context.packageName }
        .map { App(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
    private suspend fun listApps(query: String): JSONObject = withContext(Dispatchers.IO) {
        val found = apps().filter { query.isBlank() || it.name.contains(query, true) || it.pkg.contains(query, true) }
        JSONObject().put("status", ToolStatus.DONE).put("apps", JSONArray(found.take(60).map { JSONObject().put("name", it.name).put("package", it.pkg) })).put("truncated", found.size > 60)
    }
    private suspend fun openApp(args: JSONObject): JSONObject {
        val requested = args.optString("package").trim(); val name = args.optString("name").trim()
        if (requested.isEmpty() && name.isEmpty()) throw ToolFailure(ToolStatus.ERROR, "Укажите name или package приложения.")
        val pkg = requested.ifEmpty {
            val all = withContext(Dispatchers.IO) { apps() }
            val exact = all.filter { it.name.equals(name, true) }
            val partial = exact.ifEmpty { all.filter { it.name.contains(name, true) || it.pkg.contains(name, true) } }
            when (partial.size) {
                0 -> throw ToolFailure(ToolStatus.ERROR, "Приложение «${name.take(60)}» не найдено. Проверьте list_apps.", code = "not_found")
                1 -> partial.single().pkg
                else -> throw ToolFailure(ToolStatus.ERROR, "Под «${name.take(60)}» подходит несколько приложений. Повторите open_app с package.", code = "ambiguous",
                    extra = JSONObject().put("candidates", JSONArray(partial.take(10).map { JSONObject().put("name", it.name).put("package", it.pkg) })))
            }
        }
        if (pkg == context.packageName || !pkg.matches(Regex("[A-Za-z0-9_.]+"))) throw ToolFailure(ToolStatus.DENIED, "Нельзя открыть этот пакет.")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: throw ToolFailure(ToolStatus.ERROR, "Приложение $pkg не найдено. Проверьте list_apps.", code = "not_found")
        launch(intent)
        return JSONObject().put("status", ToolStatus.DONE).put("opened", pkg)
    }
    private suspend fun openUrl(raw: String): JSONObject {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase().orEmpty()
        if (uri == null || scheme.isEmpty()) throw ToolFailure(ToolStatus.ERROR, "Нужна полная ссылка со схемой, например https://…")
        if (scheme in setOf("file", "content", "intent", "javascript", "data", "about", "android-app", "jar", "ftp"))
            throw ToolFailure(ToolStatus.DENIED, "Ссылки со схемой $scheme: Luma не открывает.")
        if ((scheme == "http" || scheme == "https") && uri.host.isNullOrBlank()) throw ToolFailure(ToolStatus.ERROR, "В ссылке нет адреса сайта.")
        val intent = when (scheme) {
            // Only drafts: the user presses «Позвонить» or «Отправить» themselves.
            "tel" -> Intent(Intent.ACTION_DIAL, uri)
            "mailto", "sms", "smsto", "mms", "mmsto" -> Intent(Intent.ACTION_SENDTO, uri)
            else -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val handler = launch(intent)
        if (handler == context.packageName) throw ToolFailure(ToolStatus.DENIED, "Ссылка ведёт в Luma.")
        return JSONObject().put("status", ToolStatus.DONE).put("opened", raw.take(300)).put("handler", handler ?: JSONObject.NULL)
            .apply { if (scheme == "tel" || scheme.startsWith("sms") || scheme.startsWith("mms") || scheme == "mailto") put("message", "Открыт черновик; звонок или отправку пользователь подтверждает сам.") }
    }
    internal fun settingsIntent(page: String): Intent {
        val pkg = Uri.fromParts("package", context.packageName, null)
        return when (page) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "wifi_panel" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            "internet_panel" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "volume_panel" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_VOLUME) else Intent(Settings.ACTION_SOUND_SETTINGS)
            "nfc_panel" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_NFC) else Intent(Settings.ACTION_NFC_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "network", "hotspot" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "mobile_data" -> Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "airplane_mode" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "dnd_access" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            "exact_alarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "date_time" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "luma" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
    }
    private suspend fun openSettings(page: String): JSONObject {
        try { launch(settingsIntent(page)) } catch (e: ToolFailure) { if (e.status != ToolStatus.UNSUPPORTED) throw e; launch(Intent(Settings.ACTION_SETTINGS)) }
        return JSONObject().put("status", ToolStatus.DONE).put("page", page).put("message", "Настройки открыты. Само изменение в них делает пользователь.")
    }

    // ---------- Clock and calendar ----------
    private suspend fun timer(args: JSONObject): JSONObject {
        val seconds = args.getInt("seconds")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, !args.optBoolean("show_ui", false))
        args.optString("label").takeIf(String::isNotBlank)?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val handler = launch(intent)
        return done(false, JSONObject().put("seconds", seconds).put("label", args.optString("label")), "Таймер передан приложению часов. Android не даёт прочитать список таймеров, поэтому проверить его нельзя.")
            .put("handler", handler ?: JSONObject.NULL)
    }
    private fun zone(name: String): ZoneId = if (name.isBlank()) ZoneId.systemDefault() else runCatching { ZoneId.of(name) }.getOrNull()
        ?: throw ToolFailure(ToolStatus.ERROR, "Неизвестный часовой пояс «${name.take(60)}». Нужен IANA, например Europe/Moscow.")
    private suspend fun alarm(args: JSONObject): JSONObject {
        val hour = args.getInt("hour"); val minute = args.getInt("minute")
        val days = args.optJSONArray("days")?.let { list -> (0 until list.length()).map { list.getString(it) } }.orEmpty().distinct()
        val plan = AlarmPlan.of(hour, minute, days, zone(args.optString("time_zone")), ZoneId.systemDefault(), ZonedDateTime.now())
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, plan.hour).putExtra(AlarmClock.EXTRA_MINUTES, plan.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, !args.optBoolean("show_ui", false))
        args.optString("label").takeIf(String::isNotBlank)?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (args.has("vibrate")) intent.putExtra(AlarmClock.EXTRA_VIBRATE, args.getBoolean("vibrate"))
        if (plan.days.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(plan.days.map { WEEK_DAYS.indexOf(it).let { i -> (i + 1) % 7 + 1 } }))
        val handler = launch(intent)
        // The clock app registers its alarm with AlarmManager; the next alarm shows whether ours is the soonest.
        val alarms = service(AlarmManager::class.java)
        val seen = withTimeoutOrNull(2500) {
            var hit: Long? = null
            while (hit == null) { val next = alarms?.nextAlarmClock?.triggerTime; if (next != null && kotlin.math.abs(next - plan.nextMillis) < 60_000) hit = next else delay(250) }
            hit
        }
        val state = JSONObject().put("localTime", "%02d:%02d".format(plan.hour, plan.minute)).put("days", JSONArray(plan.days)).put("label", args.optString("label"))
            .put("next", iso(plan.nextMillis)).put("converted", plan.converted).put("timeZone", ZoneId.systemDefault().id)
        return done(seen != null, state, if (seen != null) null else "Будильник передан приложению часов; Android подтверждает только ближайший будильник, а он другой или ещё не появился.")
            .put("handler", handler ?: JSONObject.NULL)
    }
    private suspend fun event(args: JSONObject): JSONObject {
        val zone = zone(args.optString("time_zone"))
        val start = parseMoment(args.getString("start"), zone); val end = args.optString("end").takeIf(String::isNotBlank)?.let { parseMoment(it, zone) }
        val allDay = args.optBoolean("all_day", start.second)
        if (end != null && end.first.toInstant().toEpochMilli() < start.first.toInstant().toEpochMilli()) throw ToolFailure(ToolStatus.ERROR, "Конец события раньше начала.")
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.first.toInstant().toEpochMilli())
            .putExtra(CalendarContract.Events.TITLE, args.getString("title")).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .putExtra(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        end?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.first.toInstant().toEpochMilli()) }
        args.optString("location").takeIf(String::isNotBlank)?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        args.optString("description").takeIf(String::isNotBlank)?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        val rule = when (args.optString("repeat")) { "daily" -> "FREQ=DAILY"; "weekly" -> "FREQ=WEEKLY"; "monthly" -> "FREQ=MONTHLY"; "yearly" -> "FREQ=YEARLY"; else -> null }
        rule?.let { intent.putExtra(CalendarContract.Events.RRULE, it) }
        launch(intent)
        return waiting(ToolStatus.NEEDS_CONFIRMATION, "Открыта заполненная форма события в календаре. Событие появится после сохранения.", "Проверьте событие и нажмите «Сохранить».",
            JSONObject().put("title", args.getString("title")).put("start", start.first.toLocalDateTime().toString()).put("end", end?.first?.toLocalDateTime()?.toString() ?: JSONObject.NULL)
                .put("allDay", allDay).put("timeZone", zone.id).put("repeat", rule ?: "none"))
    }
}

/** Parses "2026-10-23", "2026-10-23T15:00" (in [zone]) or a time with an offset. The flag marks a date without time. */
internal fun parseMoment(text: String, zone: ZoneId): Pair<ZonedDateTime, Boolean> {
    val value = text.trim().replace(' ', 'T')
    runCatching { return OffsetDateTime.parse(value).atZoneSameInstant(zone) to false }
    runCatching { return LocalDateTime.parse(value).atZone(zone) to false }
    runCatching { return LocalDate.parse(value).atStartOfDay(zone) to true }
    throw ToolFailure(ToolStatus.ERROR, "Не понял время «${text.take(40)}». Нужен формат YYYY-MM-DDTHH:MM.")
}

/** An alarm time given in [source] zone, converted to the device zone the clock app uses. */
internal data class AlarmPlan(val hour: Int, val minute: Int, val days: List<String>, val nextMillis: Long, val converted: Boolean) {
    companion object {
        fun of(hour: Int, minute: Int, days: List<String>, source: ZoneId, device: ZoneId, now: ZonedDateTime): AlarmPlan {
            val there = now.withZoneSameInstant(source)
            var first = there.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (days.isEmpty()) { if (!first.isAfter(there)) first = first.plusDays(1) }
            else { var guard = 0; while ((!first.isAfter(there) || WEEK_DAYS[first.dayOfWeek.value - 1] !in days) && guard++ < 8) first = first.plusDays(1) }
            val local = first.withZoneSameInstant(device)
            val shift = (local.toLocalDate().toEpochDay() - first.toLocalDate().toEpochDay()).toInt()
            val localDays = days.map { WEEK_DAYS[Math.floorMod(WEEK_DAYS.indexOf(it) + shift, 7)] }
            return AlarmPlan(local.hour, local.minute, localDays, local.toInstant().toEpochMilli(), source.rules != device.rules || shift != 0)
        }
    }
}
