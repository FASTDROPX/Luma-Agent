package dev.luma.agent.core

import org.json.JSONArray
import org.json.JSONObject

/** Result statuses every agent tool reports; the model and the step log read them the same way. */
object ToolStatus {
    /** Done. With verified=false the request was delivered, but Android gives no way to check the effect. */
    const val DONE = "done"
    /** A system dialog or panel is open; only the user can confirm or switch it there. */
    const val NEEDS_CONFIRMATION = "needs_confirmation"
    /** The user has to do something first (grant access, pick a folder, switch a setting). */
    const val NEEDS_USER_ACTION = "needs_user_action"
    const val UNSUPPORTED = "unsupported"
    const val DENIED = "denied"
    const val ERROR = "error"
    /** It is not known whether the action happened; check the state instead of repeating it. */
    const val UNKNOWN = "unknown"
    val all = setOf(DONE, NEEDS_CONFIRMATION, NEEDS_USER_ACTION, UNSUPPORTED, DENIED, ERROR, UNKNOWN)
}

/** A tool that did not complete; [status] tells the model why, [userAction] what the user can do. */
open class ToolFailure(val status: String, message: String, val userAction: String? = null, val code: String? = null,
    val extra: JSONObject? = null) : UserError(message) {
    fun json(): JSONObject = (extra?.let { JSONObject(it.toString()) } ?: JSONObject()).put("status", status).apply {
        // "error" keeps older consumers (the step log, loop guard) treating a failure as a failure.
        if (status == ToolStatus.ERROR || status == ToolStatus.DENIED || status == ToolStatus.UNSUPPORTED) put("error", message) else put("message", message)
        userAction?.let { put("userAction", it) }; code?.let { put("code", it) }
    }
}

enum class ArgType(val schema: String) { STRING("string"), INT("integer"), BOOL("boolean"), STRINGS("array") }

data class Arg(val name: String, val type: ArgType, val description: String = "", val required: Boolean = false,
    val choices: List<String> = emptyList(), val min: Long? = null, val max: Long? = null, val maxLength: Int = 4000)

/**
 * One agent tool. [sideEffect] tools change something and wait for approval in the confirmation mode;
 * [important] ones always wait for the user. [screen] tools act on the current app through Accessibility.
 */
data class ToolSpec(val name: String, val group: String, val summary: String, val args: List<Arg> = emptyList(),
    val result: String = "status", val permission: String = "", val checks: String = "",
    val sideEffect: Boolean = true, val screen: Boolean = false, val important: (JSONObject) -> Boolean = { false },
    /** An OptionalAccess id this tool needs; the agent asks for it (a chat card) before the first use. Empty = none. */
    val access: String = "",
    val label: (JSONObject) -> String = { summary }) {
    /** Compact signature for the JSON-command catalog: `volume {stream?:media|ring, level?:int}`. */
    fun signature(): String = name + if (args.isEmpty()) " {}" else args.joinToString(", ", " {", "}") { arg ->
        arg.name + (if (arg.required) "" else "?") + ":" + when {
            arg.choices.isNotEmpty() && arg.type == ArgType.STRINGS -> "[" + arg.choices.joinToString("|") + "]"
            arg.choices.isNotEmpty() -> arg.choices.joinToString("|")
            arg.type == ArgType.INT -> "int" + if (arg.min != null && arg.max != null) "${arg.min}..${arg.max}" else ""
            arg.type == ArgType.BOOL -> "bool"
            arg.type == ArgType.STRINGS -> "[str]"
            else -> "str"
        }
    }
    fun schema(gemini: Boolean = false): JSONObject = JSONObject().put("type", "object").apply {
        val properties = JSONObject()
        args.forEach { arg ->
            properties.put(arg.name, JSONObject().put("type", arg.type.schema).apply {
                if (arg.description.isNotEmpty()) put("description", arg.description)
                if (arg.type == ArgType.STRINGS) put("items", JSONObject().put("type", "string").apply { if (arg.choices.isNotEmpty()) put("enum", JSONArray(arg.choices)) })
                else if (arg.choices.isNotEmpty()) put("enum", JSONArray(arg.choices))
                if (!gemini) { arg.min?.let { put("minimum", it) }; arg.max?.let { put("maximum", it) } }
            })
        }
        put("properties", properties)
        val required = args.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) put("required", JSONArray(required))
        if (!gemini) put("additionalProperties", false)
    }
    fun details(): JSONObject = JSONObject().put("name", name).put("group", group).put("summary", summary)
        .put("args", JSONArray(args.map { a -> JSONObject().put("name", a.name).put("type", a.type.schema).put("required", a.required)
            .put("description", a.description).apply { if (a.choices.isNotEmpty()) put("choices", JSONArray(a.choices)); a.min?.let { put("min", it) }; a.max?.let { put("max", it) } } }))
        .put("result", result).put("permission", permission.ifEmpty { "нет" }).put("checks", checks)
        .put("sideEffect", sideEffect).put("alwaysConfirmed", important(JSONObject()))
}

/** The single description of everything the agent can call: catalog, schemas, validation and approvals. */
object ToolRegistry {
    private const val S = "status, verified, state"
    private val onOff = listOf("on", "off", "toggle", "status")
    private fun a(name: String, type: ArgType, description: String = "", required: Boolean = false, choices: List<String> = emptyList(),
        min: Long? = null, max: Long? = null, maxLength: Int = 4000) = Arg(name, type, description, required, choices, min, max, maxLength)
    private fun JSONObject.s(name: String) = optString(name).take(120)

    val specs: List<ToolSpec> = listOf(
        // Direct device actions: one call, no screen reading.
        ToolSpec("flashlight", "device", "фонарик", listOf(a("state", ArgType.STRING, required = true, choices = onOff)), S,
            checks = "Состояние читается из CameraManager до и после", label = { "Фонарик: ${it.s("state")}" }),
        ToolSpec("volume", "device", "громкость канала; level в процентах, или step, или mute",
            listOf(a("stream", ArgType.STRING, choices = listOf("media", "ring", "notification", "alarm", "call", "system")),
                a("level", ArgType.INT, "проценты", min = 0, max = 100), a("step", ArgType.STRING, choices = listOf("up", "down")),
                a("mute", ArgType.BOOL)), S, "Для звонка и уведомлений при «Не беспокоить» нужен доступ к режиму",
            "Новое значение читается из AudioManager", label = { "Громкость ${it.optString("stream", "media")}: " + listOf("level", "step", "mute").filter(it::has).joinToString { k -> "$k ${it.opt(k)}" } }),
        ToolSpec("ringer_mode", "device", "режим звонка", listOf(a("mode", ArgType.STRING, required = true, choices = listOf("normal", "vibrate", "silent", "status"))), S,
            "Беззвучный может требовать доступ к «Не беспокоить»", label = { "Режим звонка: ${it.s("mode")}" }),
        ToolSpec("brightness", "device", "яркость экрана в процентах и/или автояркость",
            listOf(a("level", ArgType.INT, "проценты", min = 0, max = 100), a("auto", ArgType.BOOL)), S,
            "«Изменение системных настроек» для Luma", "Значение читается из Settings.System; шкала зависит от устройства",
            label = { "Яркость: " + listOf("level", "auto").filter(it::has).joinToString { k -> "$k ${it.opt(k)}" } }),
        ToolSpec("toggle_setting", "device", "Wi‑Fi, Bluetooth, мобильные данные, геолокация, авиарежим, точка доступа, NFC, «Не беспокоить», автоповорот",
            listOf(a("setting", ArgType.STRING, required = true, choices = listOf("wifi", "bluetooth", "mobile_data", "location", "airplane_mode", "hotspot", "nfc", "do_not_disturb", "auto_rotate")),
                a("state", ArgType.STRING, required = true, choices = onOff), a("mode", ArgType.STRING, "только do_not_disturb", choices = listOf("priority", "alarms", "silence"))),
            S + ", userAction", "Зависит от настройки: см. capabilities", "Текущее состояние читается до и после; если Android запрещает переключение, открывается панель или настройки",
            label = { "${it.s("setting")}: ${it.s("state")}" }),
        ToolSpec("media", "device", "управление воспроизведением", listOf(a("action", ArgType.STRING, required = true, choices = listOf("play", "pause", "play_pause", "next", "previous", "stop", "status"))), S,
            checks = "Для play/pause проверяется, идёт ли воспроизведение", label = { "Медиа: ${it.s("action")}" }),
        ToolSpec("clipboard", "device", "буфер обмена: записать, прочитать, очистить",
            listOf(a("action", ArgType.STRING, required = true, choices = listOf("set", "get", "clear")), a("text", ArgType.STRING, "для set", maxLength = 20_000)),
            "status, text", "Чтение на Android 10+ разрешено только приложению на экране", important = { it.optString("action") == "get" },
            label = { if (it.optString("action") == "set") "Скопировать в буфер: ${it.optString("text").take(80)}" else "Буфер обмена: ${it.s("action")}" }),
        ToolSpec("device_status", "device", "батарея, сеть, время, звук, экран, переключатели, память, следующий будильник",
            listOf(a("include", ArgType.STRINGS, choices = listOf("battery", "network", "time", "sound", "display", "toggles", "storage", "device", "alarm"))),
            "состояние по разделам; null — Android не сообщает", sideEffect = false, label = { "Проверяю состояние телефона" }),
        // System UI through Accessibility global actions and standard gestures.
        ToolSpec("open_notifications", "system", "открыть шторку уведомлений", emptyList(), S, "Служба Luma в специальных возможностях",
            "Проверяется окно системного интерфейса", screen = true, label = { "Открыть шторку уведомлений" }),
        ToolSpec("open_quick_settings", "system", "открыть быстрые настройки", emptyList(), S, "Служба Luma в специальных возможностях",
            "Проверяется окно системного интерфейса", screen = true, label = { "Открыть быстрые настройки" }),
        ToolSpec("global_action", "system", "системное действие", listOf(a("action", ArgType.STRING, required = true,
            choices = listOf("recents", "lock_screen", "power_dialog", "screenshot", "close_shade", "all_apps"))), S, "Служба Luma",
            "lock_screen проверяет блокировку", screen = true, label = { "Системное действие: ${it.s("action")}" }),
        ToolSpec("home", "system", "главный экран", screen = true, label = { "Открыть главный экран" }),
        ToolSpec("back", "system", "назад", screen = true, label = { "Вернуться назад" }),
        ToolSpec("swipe_screen", "system", "стандартный свайп по размерам экрана; edge — от края (edge=top с direction=down открывает шторку жестом)",
            listOf(a("direction", ArgType.STRING, "куда движется палец", true, listOf("up", "down", "left", "right")),
                a("edge", ArgType.STRING, choices = listOf("top", "bottom", "left", "right")), a("length", ArgType.STRING, choices = listOf("short", "medium", "long"))),
            S + ", screen", "Служба Luma", "Без снимка и vision; координаты считаются от размеров экрана", screen = true,
            label = { "Свайп ${it.s("direction")}" + (it.optString("edge").takeIf(String::isNotEmpty)?.let { e -> " от края $e" } ?: "") }),
        // Apps, links and settings.
        ToolSpec("list_apps", "apps", "установленные приложения", listOf(a("query", ArgType.STRING, "часть имени или пакета", true, maxLength = 100)), "apps", sideEffect = false, label = { "Ищу приложение" }),
        ToolSpec("open_app", "apps", "запустить приложение по name или package", listOf(a("name", ArgType.STRING, maxLength = 100), a("package", ArgType.STRING, maxLength = 240)),
            "status, opened, screen; при неоднозначном имени — candidates", label = { "Открыть ${it.optString("name").ifEmpty { it.s("package") }}" }),
        ToolSpec("open_url", "apps", "открыть https-ссылку или deep link (tel: открывает набор номера, mailto:/sms: — черновик)",
            listOf(a("url", ArgType.STRING, required = true, maxLength = 2000)), "status, handler, screen", checks = "file:, content:, intent:, javascript: запрещены",
            label = { "Открыть ссылку ${it.optString("url").take(100)}" }),
        ToolSpec("open_settings", "apps", "открыть страницу настроек Android (только открыть; переключает пользователь)",
            listOf(a("page", ArgType.STRING, required = true, choices = SETTINGS_PAGES)), "status, page", label = { "Открыть настройки: ${it.s("page")}" }),
        // Time: alarms, timers, calendar and Luma's own reminders.
        ToolSpec("set_timer", "time", "таймер в приложении «Часы»", listOf(a("seconds", ArgType.INT, required = true, min = 1, max = 86_400),
            a("label", ArgType.STRING, maxLength = 200), a("show_ui", ArgType.BOOL)), "status, verified, handler", "Приложение часов с ACTION_SET_TIMER",
            "Android не даёт прочитать список таймеров: verified=false", label = { "Таймер на ${it.optInt("seconds")} с" + it.optString("label").takeIf(String::isNotEmpty)?.let { l -> ": $l" }.orEmpty() }),
        ToolSpec("set_alarm", "time", "будильник в приложении «Часы»; время местное либо в time_zone",
            listOf(a("hour", ArgType.INT, required = true, min = 0, max = 23), a("minute", ArgType.INT, required = true, min = 0, max = 59),
                a("label", ArgType.STRING, maxLength = 200), a("days", ArgType.STRINGS, "повтор по дням", choices = WEEK_DAYS),
                a("time_zone", ArgType.STRING, "IANA, например Europe/Moscow", maxLength = 60), a("vibrate", ArgType.BOOL), a("show_ui", ArgType.BOOL)),
            "status, verified, localTime, days", "Приложение часов с ACTION_SET_ALARM", "Следующий будильник сверяется с AlarmManager",
            label = { "Будильник на %02d:%02d".format(it.optInt("hour"), it.optInt("minute")) + it.optString("label").takeIf(String::isNotEmpty)?.let { l -> " «$l»" }.orEmpty() }),
        ToolSpec("show_alarms", "time", "открыть список будильников или таймеров", listOf(a("view", ArgType.STRING, choices = listOf("alarms", "timers"))), "status", label = { "Открыть будильники" }),
        ToolSpec("create_event", "time", "событие календаря: открывает заполненную форму, пользователь сохраняет",
            listOf(a("title", ArgType.STRING, required = true, maxLength = 300), a("start", ArgType.STRING, "YYYY-MM-DDTHH:MM или YYYY-MM-DD", true, maxLength = 40),
                a("end", ArgType.STRING, maxLength = 40), a("all_day", ArgType.BOOL), a("time_zone", ArgType.STRING, maxLength = 60),
                a("location", ArgType.STRING, maxLength = 300), a("description", ArgType.STRING, maxLength = 2000),
                a("repeat", ArgType.STRING, choices = listOf("none", "daily", "weekly", "monthly", "yearly"))),
            "status=needs_confirmation, event", "Приложение календаря", label = { "Событие «${it.optString("title").take(80)}» ${it.s("start")}" }),
        ToolSpec("create_reminder", "time", "напоминание Luma: уведомление в указанное время",
            listOf(a("text", ArgType.STRING, required = true, maxLength = 500), a("at", ArgType.STRING, "YYYY-MM-DDTHH:MM", maxLength = 40),
                a("in_minutes", ArgType.INT, min = 1, max = 525_600), a("time_zone", ArgType.STRING, maxLength = 60),
                a("repeat", ArgType.STRING, choices = listOf("none", "daily", "weekly"))),
            "status, verified, reminder", "Уведомления Luma; точное время — «Будильники и напоминания»", "Напоминание сохраняется и планируется в AlarmManager",
            label = { "Напоминание «${it.optString("text").take(80)}»" }),
        ToolSpec("list_reminders", "time", "напоминания Luma", emptyList(), "reminders", sideEffect = false, label = { "Смотрю напоминания" }),
        ToolSpec("cancel_reminder", "time", "отменить напоминание Luma", listOf(a("id", ArgType.STRING, required = true, maxLength = 40)), S, label = { "Отменить напоминание ${it.s("id")}" }),
        // Workspace files.
        ToolSpec("list_files", "files", "содержимое папки workspace", listOf(a("path", ArgType.STRING, "\"\" — корень")), "files", sideEffect = false, label = { "Смотрю содержимое папки" }),
        ToolSpec("file_info", "files", "существует ли файл или папка, размер, тип, дата", listOf(a("path", ArgType.STRING, required = true)), "exists, ...", sideEffect = false, label = { "Проверяю ${it.s("path")}" }),
        ToolSpec("search_files", "files", "поиск по имени во всей рабочей папке", listOf(a("query", ArgType.STRING, required = true, maxLength = 100), a("path", ArgType.STRING)),
            "matches", sideEffect = false, label = { "Ищу файл «${it.s("query")}»" }),
        ToolSpec("read_text", "files", "прочитать текстовый файл до 64 КБ", listOf(a("path", ArgType.STRING, required = true)), "text", sideEffect = false, label = { "Читаю ${it.s("path")}" }),
        ToolSpec("write_text", "files", "создать текстовый файл (папки создаются); существование проверяется внутри",
            listOf(a("path", ArgType.STRING, required = true), a("text", ArgType.STRING, required = true, maxLength = 65_536), a("overwrite", ArgType.BOOL, "заменить существующий; с подтверждением")),
            "status, path, actualName", checks = "Без overwrite существующий файл не меняется; при замене делается резервная копия",
            important = { it.optBoolean("overwrite") }, label = { "Записать ${it.s("path")}" }),
        ToolSpec("append_text", "files", "дописать текст в конец файла (создаёт, если нет)", listOf(a("path", ArgType.STRING, required = true), a("text", ArgType.STRING, required = true, maxLength = 65_536)),
            "status, path, bytes", checks = "Размер проверяется после записи, при сбое файл восстанавливается", label = { "Дописать в ${it.s("path")}" }),
        ToolSpec("create_folder", "files", "создать папку (вместе с недостающими)", listOf(a("path", ArgType.STRING, required = true)), "status, path", label = { "Создать папку ${it.s("path")}" }),
        ToolSpec("rename_file", "files", "переименовать файл или папку", listOf(a("path", ArgType.STRING, required = true), a("name", ArgType.STRING, required = true, maxLength = 240)),
            "status, name", label = { "Переименовать в ${it.s("name")}" }),
        ToolSpec("copy_file", "files", "копировать файл до 20 МБ без перезаписи", listOf(a("from", ArgType.STRING, required = true), a("to", ArgType.STRING, required = true)), "status", label = { "Скопировать ${it.s("from")}" }),
        ToolSpec("move_file", "files", "переместить файл до 20 МБ без перезаписи", listOf(a("from", ArgType.STRING, required = true), a("to", ArgType.STRING, required = true)), "status",
            important = { true }, label = { "Переместить ${it.s("from")}" }),
        ToolSpec("delete_file", "files", "удалить файл или пустую папку (окончательно)", listOf(a("path", ArgType.STRING, required = true)), "status",
            important = { true }, label = { "Удалить ${it.s("path")}" }),
        // Screen control: the fallback when no direct tool fits.
        ToolSpec("screen", "screen", "дерево элементов текущего экрана, snapshot и размеры",
            listOf(a("query", ArgType.STRING, maxLength = 200), a("detailed", ArgType.BOOL), a("image", ArgType.BOOL, "JPEG, только если разрешено и модель с vision")),
            "snapshot, width, height, nodes", "Служба Luma", sideEffect = false, label = { "Читаю текущий экран" }),
        ToolSpec("tap", "screen", "нажать элемент из последнего screen", listOf(a("node", ArgType.INT, required = true), a("snapshot", ArgType.STRING)), "status, screen", screen = true),
        ToolSpec("long_press", "screen", "долгое нажатие на элемент: меню действий (копировать, поделиться, открыть в браузере)",
            listOf(a("node", ArgType.INT, required = true), a("snapshot", ArgType.STRING)), "status, screen", screen = true),
        ToolSpec("tap_point", "screen", "нажать точку; она должна попадать в элемент дерева или быть видна на снимке",
            listOf(a("x", ArgType.INT, required = true), a("y", ArgType.INT, required = true), a("snapshot", ArgType.STRING)), "status, screen", screen = true),
        ToolSpec("scroll", "screen", "прокрутить элемент", listOf(a("node", ArgType.INT, required = true), a("direction", ArgType.STRING, required = true, choices = listOf("down", "up")), a("snapshot", ArgType.STRING)), "status, screen", screen = true),
        ToolSpec("swipe", "screen", "свайп по координатам последнего screen", listOf(a("x1", ArgType.INT, required = true), a("y1", ArgType.INT, required = true),
            a("x2", ArgType.INT, required = true), a("y2", ArgType.INT, required = true), a("snapshot", ArgType.STRING)), "status, screen", screen = true),
        ToolSpec("set_text", "screen", "ввести текст в поле", listOf(a("node", ArgType.INT, required = true), a("text", ArgType.STRING, required = true), a("snapshot", ArgType.STRING)), "status, screen", screen = true),
        ToolSpec("wait", "screen", "подождать загрузки", listOf(a("ms", ArgType.INT, required = true, min = 0, max = 2000)), "ok", sideEffect = false, label = { "Жду обновления экрана" }),
        // Agent bookkeeping.
        ToolSpec("remember", "meta", "заменить рабочие заметки задачи (до 4000 символов)", listOf(a("notes", ArgType.STRING, required = true, maxLength = 4000)), "saved", sideEffect = false, label = { "Сохраняю заметки задачи" }),
        // Tools that use the optional permissions. Each declares the access it needs; the agent asks for it (a chat card)
        // before the first use. Acting on the world — a call, an SMS, a new event — waits for the user's confirmation.
        ToolSpec("find_contacts", "phone", "найти контакты по имени: имена и номера", listOf(a("query", ArgType.STRING, "часть имени", required = true, maxLength = 100), a("limit", ArgType.INT, min = 1, max = 30)),
            "contacts", "Контакты", sideEffect = false, access = "contacts", label = { "Ищу контакт «${it.s("query")}»" }),
        ToolSpec("read_call_log", "phone", "последние звонки: кто, когда, тип, длительность", listOf(a("limit", ArgType.INT, min = 1, max = 50)),
            "calls", "Журнал звонков", sideEffect = false, access = "call_log", label = { "Читаю журнал звонков" }),
        ToolSpec("read_sms", "phone", "последние SMS (можно по контакту)", listOf(a("contact", ArgType.STRING, "имя или номер", maxLength = 100), a("limit", ArgType.INT, min = 1, max = 50)),
            "messages", "SMS", sideEffect = false, access = "sms", label = { "Читаю SMS" }),
        ToolSpec("send_sms", "phone", "отправить SMS на номер", listOf(a("number", ArgType.STRING, required = true, maxLength = 40), a("text", ArgType.STRING, required = true, maxLength = 1000)),
            "status", "SMS", access = "sms", important = { true }, label = { "Отправить SMS на ${it.s("number")}" }),
        ToolSpec("call_number", "phone", "позвонить на номер (найди его через find_contacts)", listOf(a("number", ArgType.STRING, required = true, maxLength = 40)),
            "status", "Телефон", access = "phone", important = { true }, label = { "Позвонить ${it.s("number")}" }),
        ToolSpec("get_location", "phone", "текущие координаты телефона и ссылка на карту", listOf(),
            "latitude, longitude", "Местоположение", sideEffect = false, access = "location", label = { "Определяю местоположение" }),
        ToolSpec("list_calendar", "phone", "ближайшие события календаря", listOf(a("days", ArgType.INT, min = 1, max = 60)),
            "events", "Календарь", sideEffect = false, access = "calendar", label = { "Смотрю календарь" }),
        ToolSpec("list_media", "phone", "список фото, видео или аудио телефона (только чтение)", listOf(a("type", ArgType.STRING, choices = listOf("image", "video", "audio")), a("limit", ArgType.INT, min = 1, max = 50)),
            "files", "Медиатека", sideEffect = false, label = { "Смотрю медиатеку" }),
        ToolSpec("app_usage", "phone", "сколько времени открыты приложения", listOf(a("days", ArgType.INT, min = 1, max = 30), a("limit", ArgType.INT, min = 1, max = 30)),
            "apps", "Статистика использования", sideEffect = false, access = "usage", label = { "Смотрю статистику приложений" }),
        ToolSpec("describe_tool", "meta", "подробности инструмента: аргументы, результат, разрешения, доступность", listOf(a("name", ArgType.STRING, required = true, maxLength = 60)), "details", sideEffect = false, label = { "Уточняю инструмент ${it.s("name")}" }),
    ) + ExtendedToolSpecs.specs + SearchingChat.TOOLS
    private val byName = specs.associateBy { it.name }
    /** Tools that act on the screen or open another app: before the first of them the agent's overlay appears. */
    fun controlsScreen(name: String): Boolean = name != "wait" && (byName[name]?.group in setOf("screen", "system") ||
        name in setOf("open_app", "open_url", "open_settings", "show_alarms", "create_event"))
    val names: Set<String> = byName.keys
    operator fun get(name: String): ToolSpec? = byName[name]

    private val groupTitles = linkedMapOf("device" to "Устройство (сразу, без экрана)", "system" to "Система", "apps" to "Приложения и ссылки",
        "time" to "Время", "files" to "Файлы workspace:/", "screen" to "Экран (запасной путь)", "meta" to "Служебные", "phone" to "Телефон", "access" to "Доступы и действия", "web" to "Интернет")
    /** One line per tool for models that use JSON commands; details come from describe_tool. */
    fun catalog(): String = groupTitles.entries.joinToString("\n") { (group, title) ->
        "$title: " + specs.filter { it.group == group }.joinToString("; ") { it.signature() }
    }
    /** OpenAI-style function list; also the source for Claude tools and Gemini declarations. */
    fun functions(): List<ToolSpec> = specs

    /** Checks types, required fields, choices and ranges before anything runs. Unknown fields are ignored. */
    fun validate(name: String, args: JSONObject) {
        val spec = byName[name] ?: throw ToolFailure(ToolStatus.ERROR, "Неизвестный инструмент «${name.take(40)}».")
        args.optString("__invalid").takeIf { args.has("__invalid") }?.let { throw ToolFailure(ToolStatus.ERROR, "Аргументы $name — некорректный JSON.") }
        for (arg in spec.args) {
            val value = args.opt(arg.name)
            if (value == null || value == JSONObject.NULL) { if (arg.required) throw ToolFailure(ToolStatus.ERROR, "Не указан аргумент «${arg.name}» для $name."); continue }
            fun bad(what: String): Nothing = throw ToolFailure(ToolStatus.ERROR, "Аргумент «${arg.name}» для $name: $what.")
            when (arg.type) {
                ArgType.STRING -> {
                    val text = value as? String ?: bad("нужна строка")
                    if (text.length > arg.maxLength) bad("длиннее ${arg.maxLength} символов")
                    if (arg.choices.isNotEmpty() && text !in arg.choices) bad("допустимо ${arg.choices.joinToString("|")}")
                }
                ArgType.INT -> {
                    val number = value as? Number ?: bad("нужно целое число")
                    val d = number.toDouble()
                    if (!d.isFinite() || d != Math.rint(d)) bad("нужно целое число")
                    arg.min?.let { if (d < it) bad("не меньше $it") }; arg.max?.let { if (d > it) bad("не больше $it") }
                }
                ArgType.BOOL -> if (value !is Boolean) bad("нужно true или false")
                ArgType.STRINGS -> {
                    val list = value as? JSONArray ?: bad("нужен массив строк")
                    if (list.length() > 50) bad("слишком много значений")
                    for (i in 0 until list.length()) { val item = list.opt(i) as? String ?: bad("нужен массив строк"); if (arg.choices.isNotEmpty() && item !in arg.choices) bad("допустимо ${arg.choices.joinToString("|")}") }
                }
            }
        }
    }
    fun label(name: String, args: JSONObject): String = byName[name]?.label?.invoke(args)?.take(160) ?: name
}
val WEEK_DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
val SETTINGS_PAGES = listOf("settings", "wifi", "wifi_panel", "internet_panel", "bluetooth", "network", "mobile_data", "hotspot", "location", "airplane_mode",
    "nfc", "nfc_panel", "volume_panel", "display", "sound", "battery", "notifications", "dnd_access", "write_settings", "exact_alarms",
    "accessibility", "date_time", "apps", "storage", "security", "luma")
