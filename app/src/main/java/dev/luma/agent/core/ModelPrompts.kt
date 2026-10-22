package dev.luma.agent.core

/** User instructions stay in the system role for every supported provider. */
object ModelPrompts {
    fun chat(connection: Connection): String =
        "Ты Luma, дружелюбный и точный помощник. Отвечай по-русски, если пользователь не попросил иначе. " +
        "Это обычный чат: у тебя нет доступа к экрану и файлам, ты не можешь выполнять действия на телефоне. " +
        "Для действий предложи включить режим агента. Не утверждай, что выполнил действие." + custom(connection)

    fun agent(settings: AppSettings,connection: Connection,screenAvailable: Boolean): String =
        AgentProtocol.system + custom(connection) +
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
