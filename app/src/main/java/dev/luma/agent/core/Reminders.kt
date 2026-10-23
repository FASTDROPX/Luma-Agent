package dev.luma.agent.core

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A Luma reminder. Repeats keep the wall-clock [time] in [zone], so they follow daylight saving changes. */
data class Reminder(val id: String, val text: String, val at: Long, val zone: String, val time: String, val repeat: String) {
    fun json() = JSONObject().put("id", id).put("text", text).put("at", at).put("zone", zone).put("time", time).put("repeat", repeat)
    fun describe(): JSONObject = JSONObject().put("id", id).put("text", text).put("repeat", repeat).put("timeZone", zone)
        .put("at", ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.of(zone)).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16))
    /** The next time after [now] for a repeating reminder, or null for a one-time one. */
    fun next(now: Long): Reminder? {
        if (repeat == "none") return null
        val zoneId = ZoneId.of(zone); val clock = LocalTime.parse(time)
        var date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zoneId).toLocalDate()
        var guard = 0
        while (guard++ < 800) {
            date = date.plusDays(if (repeat == "weekly") 7 else 1)
            val candidate = date.atTime(clock).atZone(zoneId).toInstant().toEpochMilli()
            if (candidate > now) return copy(at = candidate)
        }
        return null
    }
    companion object {
        fun parse(j: JSONObject) = Reminder(j.getString("id"), j.getString("text"), j.getLong("at"), j.getString("zone"), j.getString("time"), j.optString("repeat", "none"))
    }
}

class ReminderStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "luma-reminders.json"))
    private val lock = Any()
    fun all(): List<Reminder> = synchronized(lock) {
        if (!file.baseFile.exists()) return emptyList()
        runCatching { JSONArray(String(file.readFully(), Charsets.UTF_8)).let { a -> (0 until a.length()).map { Reminder.parse(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    }
    private fun save(items: List<Reminder>) = synchronized(lock) {
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(JSONArray(items.map { it.json() }).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw ToolFailure(ToolStatus.ERROR, "Не удалось сохранить напоминание. Проверьте свободное место.") }
    }
    fun put(reminder: Reminder) = synchronized(lock) { save(all().filter { it.id != reminder.id } + reminder) }
    fun remove(id: String): Reminder? = synchronized(lock) { val items = all(); val found = items.firstOrNull { it.id == id }; if (found != null) save(items - found); found }

    /** Exact time when Android allows it; otherwise a 10-minute window, reported to the model. */
    fun schedule(reminder: Reminder): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        val pending = PendingIntent.getBroadcast(context, reminder.id.hashCode(), fireIntent(context, reminder.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val allowed = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        // The permission can be revoked between the check and the call; then the reminder is still planned approximately.
        val exact = allowed && try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, pending); true } catch (e: SecurityException) { false }
        if (!exact) alarms.setWindow(AlarmManager.RTC_WAKEUP, reminder.at, 10 * 60_000L, pending)
        return exact
    }
    fun unschedule(id: String) {
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), fireIntent(context, id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pending); pending.cancel()
    }
    fun rescheduleAll() { val now = System.currentTimeMillis(); all().forEach { if (it.at > now) schedule(it) else ReminderReceiver.deliver(context, it, late = true) } }

    /** The create_reminder / list_reminders / cancel_reminder tools. */
    fun run(name: String, args: JSONObject, now: ZonedDateTime = ZonedDateTime.now()): JSONObject = when (name) {
        "list_reminders" -> JSONObject().put("status", ToolStatus.DONE).put("reminders", JSONArray(all().sortedBy { it.at }.take(100).map { it.describe() }))
        "cancel_reminder" -> {
            val id = args.getString("id"); val removed = remove(id) ?: throw ToolFailure(ToolStatus.ERROR, "Напоминание $id не найдено. Проверьте list_reminders.", code = "not_found")
            unschedule(id); JSONObject().put("status", ToolStatus.DONE).put("verified", all().none { it.id == id }).put("cancelled", removed.describe())
        }
        else -> create(args, now)
    }
    private fun create(args: JSONObject, now: ZonedDateTime): JSONObject {
        val zone = args.optString("time_zone").takeIf(String::isNotBlank)?.let { runCatching { ZoneId.of(it) }.getOrNull() ?: throw ToolFailure(ToolStatus.ERROR, "Неизвестный часовой пояс «${it.take(60)}».") }
            ?: ZoneId.systemDefault()
        val hasAt = args.optString("at").isNotBlank(); val hasIn = args.has("in_minutes")
        if (hasAt == hasIn) throw ToolFailure(ToolStatus.ERROR, "Укажите время напоминания: at (YYYY-MM-DDTHH:MM) или in_minutes — одно из двух. Если пользователь не назвал время, спросите его.")
        val moment = if (hasIn) now.withZoneSameInstant(zone).plusMinutes(args.getInt("in_minutes").toLong()).withSecond(0).withNano(0)
            else parseMoment(args.getString("at"), zone).let { (value, dateOnly) -> if (dateOnly) throw ToolFailure(ToolStatus.ERROR, "Укажите время, а не только дату. Спросите пользователя, во сколько напомнить.") else value }
        if (!moment.toInstant().isAfter(now.toInstant())) throw ToolFailure(ToolStatus.ERROR, "Это время уже прошло (сейчас ${now.withZoneSameInstant(zone).toLocalDateTime().toString().take(16)} в ${zone.id}).")
        val reminder = Reminder(java.util.UUID.randomUUID().toString().take(8), args.getString("text").trim(), moment.toInstant().toEpochMilli(), zone.id,
            moment.toLocalTime().toString().take(5), args.optString("repeat").ifEmpty { "none" })
        put(reminder)
        val exact = schedule(reminder)
        val notificationsAllowed = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val result = JSONObject().put("verified", all().any { it.id == reminder.id }).put("reminder", reminder.describe()).put("exactTime", exact)
        if (!exact) result.put("note", "Android разрешает Luma только приблизительное время (до 10 минут). Точное время включается в «Доступ» → «Системные действия».")
        return if (notificationsAllowed) result.put("status", ToolStatus.DONE)
        else result.put("status", ToolStatus.NEEDS_USER_ACTION).put("message", "Напоминание сохранено, но уведомления Luma выключены — оно не появится.")
            .put("userAction", "Разрешите уведомления Luma: «Доступ» → «Уведомления».")
    }
    companion object {
        internal fun fireIntent(context: Context, id: String) = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.FIRE).setData(Uri.parse("luma-reminder://$id"))
    }
}

/** Shows a due reminder and plans the next repeat. Internal only; boot and clock changes go through [ReminderBootReceiver]. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FIRE) return
        val id = intent.data?.host ?: return
        val store = ReminderStore(context)
        val reminder = store.all().firstOrNull { it.id == id } ?: return
        deliver(context, reminder, late = false)
    }
    companion object {
        const val FIRE = "dev.luma.agent.REMINDER"
        fun deliver(context: Context, reminder: Reminder, late: Boolean) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("reminders", "Напоминания", NotificationManager.IMPORTANCE_HIGH))
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
            manager.notify(reminder.id.hashCode(), Notification.Builder(context, "reminders").setSmallIcon(dev.luma.agent.R.drawable.ic_luma)
                .setContentTitle(if (late) "Пропущенное напоминание" else "Напоминание").setContentText(reminder.text)
                .setStyle(Notification.BigTextStyle().bigText(reminder.text)).setAutoCancel(true).setContentIntent(open).build())
            val store = ReminderStore(context)
            val next = reminder.next(System.currentTimeMillis())
            if (next == null) store.remove(reminder.id) else { store.put(next); store.schedule(next) }
        }
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) ReminderStore(context).rescheduleAll()
    }
}
