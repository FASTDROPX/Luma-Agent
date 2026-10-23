package dev.luma.agent.core

/** Public, bounded actions corresponding to the optional accesses in the Access tab. */
object ExtendedToolSpecs {
    private fun str(name: String, required: Boolean = false, note: String = "", choices: List<String> = emptyList(), max: Int = 2000) = Arg(name, ArgType.STRING, note, required, choices, maxLength = max)
    private fun number(name: String, min: Long, max: Long, required: Boolean = false) = Arg(name, ArgType.INT, required = required, min = min, max = max)
    private fun spec(name: String, summary: String, args: List<Arg> = emptyList(), read: Boolean = false, confirm: Boolean = false) =
        ToolSpec(name, "access", summary, args, "status; данные или системное подтверждение", sideEffect = !read, important = { confirm })
    val specs = listOf(
        spec("request_access", "запросить конкретный доступ карточкой в чате; Android подтверждает пользователь", listOf(str("access", true, "id из access_status"))),
        spec("access_status", "список доступов: id, назначение и текущее состояние", read = true),
        spec("background_status", "проверить ограничения батареи, фоновой активности и трафика", read = true),
        spec("prepare_background", "настроить необязательные доступы для долгой работы в фоне"),
        spec("capture_photo", "снять фото через видимый интерфейс камеры; пользователь нажимает затвор", confirm = true),
        spec("capture_video", "записать видео через интерфейс камеры; пользователь начинает и завершает запись", confirm = true),
        spec("open_capture", "открыть фото, видео или аудиозапись Luma из captures по возвращённому uri", listOf(str("uri", true)), read = true),
        spec("record_audio", "записать микрофон, пока Luma открыта; сохранить файл в captures", listOf(number("seconds", 1, 30)), confirm = true),
        spec("get_background_location", "однократно получить координаты с разрешением местоположения в фоне; не включает слежение", read = true),
        spec("add_contact", "создать контакт в локальной адресной книге", listOf(str("name", true, max = 200), str("number", true, max = 40)), confirm = true),
        spec("delete_contact", "удалить контакт по raw_contact_id", listOf(number("raw_contact_id", 1, Long.MAX_VALUE, true)), confirm = true),
        spec("insert_calendar_event", "сохранить событие в выбранный календарь по calendar_id", listOf(number("calendar_id", 1, Long.MAX_VALUE, true), str("title", true, max = 300), str("start", true, "ISO local date-time"), str("end", true), str("time_zone", note = "IANA, например Europe/Moscow"), str("location"), str("description")), confirm = true),
        spec("list_calendars", "доступные календари и их id", read = true),
        spec("delete_calendar_event", "удалить событие по event_id", listOf(number("event_id", 1, Long.MAX_VALUE, true)), confirm = true),
        spec("phone_info", "состояние SIM, оператор и доступные Android номера телефона", read = true),
        spec("wait_sms", "ждать входящее SMS до seconds; вернёт текст только полученного во время ожидания сообщения", listOf(number("seconds", 1, 30)), confirm = true),
        spec("read_notifications", "прочитать текущие активные уведомления других приложений", listOf(number("limit", 1, 30)), read = true),
        spec("dismiss_notification", "убрать уведомление по key из read_notifications", listOf(str("key", true)), confirm = true),
        spec("read_sensor", "получить один замер: счётчик шагов с перезагрузки, движение шага или пульс", listOf(str("sensor", choices = listOf("steps", "step_detector", "heart_rate"))), read = true),
        spec("scan_bluetooth", "искать Bluetooth LE устройства в течение 5 секунд", read = true),
        spec("bluetooth_discoverable", "открыть системный запрос видимости Bluetooth на 120 секунд", confirm = true),
        spec("wifi_scan", "сети Wi-Fi из результатов системного сканирования; нужен доступ к местоположению", read = true),
        spec("wifi_connect", "подключиться к Wi-Fi через системное подтверждение Android (пока задача активна)", listOf(str("ssid", true, max = 32), str("password", max = 63)), confirm = true),
        spec("storage_list", "список файлов в общей памяти телефона; абсолютный path внутри общей памяти", listOf(str("path")), read = true),
        spec("storage_read", "прочитать UTF-8 файл из общей памяти до 64 КБ", listOf(str("path", true)), read = true),
        spec("storage_write", "записать UTF-8 файл в общей памяти; существующий файл заменяется только при overwrite", listOf(str("path", true), str("text", true, max = 65536), Arg("overwrite", ArgType.BOOL)), confirm = true),
        spec("storage_delete", "удалить файл или пустую папку из общей памяти", listOf(str("path", true)), confirm = true),
        spec("install_apk", "открыть системный установщик APK из общей памяти; установку подтверждает пользователь", listOf(str("path", true)), confirm = true),
        spec("delete_media", "удалить фото/видео/аудио по id из list_media", listOf(number("id", 1, Long.MAX_VALUE, true), str("type", choices = listOf("image", "video", "audio"))), confirm = true),
        spec("show_notification", "показать уведомление Luma; full_screen — важное уведомление на экране блокировки, Android решает как показать", listOf(str("title", true, max = 200), str("text", true, max = 1000), Arg("full_screen", ArgType.BOOL)), confirm = true),
        spec("show_floating_note", "показать заметку поверх приложений на 10 секунд; также есть кнопка закрытия", listOf(str("text", true, max = 500)), confirm = true)
    )
    val names = specs.map { it.name }.toSet()
}
