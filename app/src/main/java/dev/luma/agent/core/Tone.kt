package dev.luma.agent.core

/**
 * «ты» or «вы» in interface texts. Strings may be written in either form; [adapt] rewrites the
 * known imperatives and pronouns into the form chosen in the settings. Only whole words change,
 * so «Выберите» never touches «Выбранная».
 */
object Tone {
    private val pairs: List<Pair<String, String>> = listOf(
        // formal to friendly
        "Напишите" to "Напиши", "Выберите" to "Выбери", "Откройте" to "Открой", "Оставьте" to "Оставь", "Укажите" to "Укажи",
        "Включите" to "Включи", "Разрешите" to "Разреши", "Нажмите" to "Нажми", "Проверьте" to "Проверь", "Сократите" to "Сократи",
        "Подключите" to "Подключи", "Попробуйте" to "Попробуй", "Опишите" to "Опиши", "Добавьте" to "Добавь", "Обновите" to "Обнови",
        "Вставьте" to "Вставь", "Поручите" to "Поручи", "Перейдите" to "Перейди", "Найдите" to "Найди", "Запустите" to "Запусти",
        "Сохраните" to "Сохрани", "Повторите" to "Повтори", "Выделите" to "Выдели", "Прочитайте" to "Прочитай", "Перетащите" to "Перетащи",
        "Остановите" to "Останови", "Отключите" to "Отключи", "Удалите" to "Удали", "Введите" to "Введи", "Измените" to "Измени",
        "Дождитесь" to "Дождись", "Вернитесь" to "Вернись", "Скачайте" to "Скачай", "Используйте" to "Используй", "Сбросьте" to "Сбрось",
        "Задайте" to "Задай", "Подтвердите" to "Подтверди", "Отмените" to "Отмени", "Выдайте" to "Выдай", "Дайте" to "Дай",
        "Посмотрите" to "Посмотри", "Уточните" to "Уточни", "Смените" to "Смени", "Отправьте" to "Отправь", "Скопируйте" to "Скопируй",
        "Установите" to "Установи", "Открывайте" to "Открывай",
        "Можете" to "Можешь", "Хотите" to "Хочешь",
        "включаете вы" to "включаешь ты", "за вас" to "за тебя", "в вашем" to "в твоём", "вашего" to "твоего", "вашей" to "твоей",
        "ваши" to "твои", "ваша" to "твоя", "ваше" to "твоё", "ваш" to "твой", "вас" to "тебя", "вам" to "тебе",
    )
    private const val L = "А-Яа-яЁё"
    private fun lower(s: String) = s.replaceFirstChar { it.lowercaseChar() }
    private fun rules(from: (Pair<String, String>) -> String, to: (Pair<String, String>) -> String): List<Pair<Regex, String>> =
        pairs.flatMap { p -> listOf(from(p) to to(p), lower(from(p)) to lower(to(p))).distinct() }
            .map { (a, b) -> Regex("(?<![$L])" + Regex.escape(a) + "(?![$L])") to b }
    private val toFriendly = rules({ it.first }, { it.second })
    private val toFormal = rules({ it.second }, { it.first })

    fun adapt(text: String, formal: Boolean): String {
        if (text.isEmpty()) return text
        var out = text
        for ((pattern, value) in if (formal) toFormal else toFriendly) out = pattern.replace(out, Regex.escapeReplacement(value))
        return out
    }
}
