package dev.luma.agent.core

/** User instructions stay in the system role for every supported provider. */
object ModelPrompts {
    fun chat(connection: Connection, formal: Boolean = false): String =
        "Ты Luma, дружелюбный и точный помощник. Отвечай по-русски, если пользователь не попросил иначе. " +
        "Это обычный чат: у тебя нет доступа к экрану и файлам, ты не можешь выполнять действия на телефоне. " +
        "Для действий предложи включить режим агента. Не утверждай, что выполнил действие." + address(formal) + custom(connection)
    /** The interface tone setting also sets how the model addresses the user. */
    private fun address(formal: Boolean) = if (formal) " Обращайся к пользователю на «вы», в спокойном деловом тоне."
        else " Обращайся к пользователю на «ты», по-дружески, но без лишней фамильярности."

    fun agent(settings: AppSettings,connection: Connection,screenAvailable: Boolean): String =
        AgentProtocol.system + address(settings.formal) + custom(connection) +
        "\nФормат JSON, доступные инструменты и границы доступа из правил Luma сохраняются." +
        "\nДоступ к экрану: $screenAvailable. Папка выбрана: ${settings.folder.isNotEmpty()}. Скриншоты разрешены пользователем: ${settings.screenshots}." +
        "\nРежим: ${if(settings.confirmActions) "Запрашивать разрешения на действия" else "Полный доступ в пределах выданных разрешений Android; важные действия подтверждает пользователь"}."

    private fun custom(connection: Connection): String = connection.generation().instruction.trim()
        .takeIf {it.isNotEmpty()}?.let {"\nДополнительный системный промпт пользователя для этой модели:\n$it"}.orEmpty()
}

object AgentApprovalPolicy {
    fun requiresApproval(tool: AgentReply.Tool,confirmActions: Boolean,sensitive: Boolean=false): Boolean {
        val interactive=tool.name !in setOf("screen","remember","list_apps","list_files","read_text","wait")
        val important=tool.name in setOf("delete_file","move_file") ||
            (tool.name=="write_text" && tool.args.optBoolean("overwrite")) || sensitive
        return (confirmActions && interactive) || important
    }
}
