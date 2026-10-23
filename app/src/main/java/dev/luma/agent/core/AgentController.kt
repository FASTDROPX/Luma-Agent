package dev.luma.agent.core

import android.content.Context
import dev.luma.agent.AgentAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class AgentController(private val context: Context, private val scope: CoroutineScope, private val vault: SettingsVault,
    private val chats: ChatStore, private val api: ProviderClient) {
    private val mutable = MutableStateFlow(RunState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private var retry: CompletableDeferred<Unit>? = null
    private var stoppedMessage = "Задача остановлена."
    /** Screen access; the Accessibility service in the app, a fake in tests. */
    internal var screen: () -> ScreenDriver? = { AgentAccessibilityService.instance }
    /** Provider/model pairs whose server rejected native tool calls in this session; they use JSON commands. */
    private val jsonOnly = mutableSetOf<String>()

    fun start(chat: Long, goal: String, finished: () -> Unit) {
        if (job?.isActive == true) return
        val settings = vault.state.value; val connection = settings.connection()
        stoppedMessage = "Задача остановлена."
        mutable.value = RunState(active = true, stage = "Подготавливаю задачу", chatId = chat, startedAt = android.os.SystemClock.elapsedRealtime())
        job = scope.launch {
            try {
                withTimeout(if (settings.unlimitedAgent) Long.MAX_VALUE else settings.taskMinutes.coerceIn(2,15) * 60_000L) {
                    val history = chats.history(chat, 8).dropWhile { it.role != "user" }
                    val memory = AgentContext(history, goal)
                    val loopGuard = AgentLoopGuard()
                    // Gathered once: the prompt stays identical between steps, so providers can cache it.
                    val environment = withContext(Dispatchers.IO) { TaskEnvironment.capture(context, screen() != null, settings.folder) }
                    val modelKey = "${settings.provider}/${connection.model}"
                    var native = settings.toolCalling == "native" || (settings.toolCalling == "auto" && modelKey !in jsonOnly)
                    var lastUnknown: String? = null
                    var step = 0
                    // The token limit pauses the task; continuing allows one more portion of the same size.
                    val tokenPortion = settings.agentTokenLimit.coerceAtLeast(0) * 1000L
                    var nextTokenPause = tokenPortion
                    while (settings.unlimitedAgent || step < settings.maxSteps.coerceIn(5,30)) {
                        awaitRunning(); step = (step + 1).coerceAtLeast(1); update("Обдумываю следующий шаг", step)
                        val stepStarted = android.os.SystemClock.elapsedRealtime()
                        val generation = generateRecovering(settings, connection, step, native, { memory.turns(vault.state.value.screenshots) },
                            { useNative -> ModelPrompts.agent(vault.state.value, connection, screen() != null, environment, useNative) }) {
                            native = false; jsonOnly += modelKey
                        }
                        val raw = generation.text
                        chats.addUsage(chat, generation.stats)
                        val spent = mutable.value.tokens + (generation.stats?.let { it.inputTokens + it.outputTokens } ?: 0)
                        mutable.value = mutable.value.copy(tokens = spent)
                        if (tokenPortion > 0 && spent >= nextTokenPause) {
                            nextTokenPause = spent + tokenPortion
                            mutable.value = mutable.value.copy(paused = true, stage = "Задача потратила ${"%,d".format(spent).replace(',', ' ')} токенов — это предел из настроек. Агент на паузе: можно продолжить ещё на столько же или остановить.")
                        }
                        awaitRunning()
                        when (val reply = reply(generation, native)) {
                            is AgentReply.Final -> { chats.append(chat, "assistant", reply.text, reasoning = generation.reasoning, stats = generation.stats); update("Готово", step); return@withTimeout }
                            is AgentReply.Tool -> {
                                val label = describe(reply)
                                update(label, step)
                                val invalid = try { ToolRegistry.validate(reply.name, reply.args); null } catch (e: ToolFailure) { e }
                                val fingerprint = fingerprint(reply)
                                val repeatUnknown = invalid == null && lastUnknown == fingerprint
                                if (invalid == null) {
                                    val blocked = screen()?.sensitive(reply.name, reply.args, vault.state.value.blockedWords)
                                    if (repeatUnknown || AgentApprovalPolicy.requiresApproval(reply, vault.state.value.confirmActions, blocked != null)) {
                                        val details = confirmationDetails(reply) + blocked?.let { "\n$it" }.orEmpty() +
                                            if (repeatUnknown) "\nРезультат такого же предыдущего шага неизвестен: он мог уже выполниться. Повторить?" else ""
                                        val accepted = requestApproval(Approval(label, details, ToolRegistry[reply.name]?.screen == true || reply.name in setOf("tap", "tap_point", "scroll", "swipe", "set_text")))
                                        if (!accepted) { chats.append(chat, "assistant", "Действие отменено вами. Задача остановлена."); return@withTimeout }
                                    }
                                }
                                awaitRunning()
                                val result = if (invalid != null) ToolResult(invalid.json()) else try {
                                    when (reply.name) {
                                        "remember" -> { memory.remember(reply.args.requiredString("notes", 4000)); ToolResult(JSONObject().put("status", ToolStatus.DONE).put("saved", true)) }
                                        "describe_tool" -> ToolResult(describeTool(reply.args.getString("name"), environment))
                                        else -> execute(reply)
                                    }
                                }
                                    catch (e: ToolFailure) { ToolResult(e.json()) }
                                    catch (e: UserError) { ToolResult(JSONObject().put("status", ToolStatus.ERROR).put("error", e.message).put("note", "Не считайте действие выполненным. Обновите экран или объясните ограничение.")) }
                                    catch (e: TimeoutCancellationException) {
                                        // Only the task's own time limit may end the task; a tool's inner timeout is a step with an unknown result.
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        ToolResult(JSONObject().put("status", ToolStatus.UNKNOWN).put("message", "Действие не завершилось вовремя; оно могло выполниться. Проверьте состояние, не повторяя его."))
                                    }
                                val status = normalize(result.json)
                                lastUnknown = if (status == ToolStatus.UNKNOWN && ToolRegistry[reply.name]?.sideEffect != false) fingerprint else null
                                if (reply.skipped > 0) result.json.put("protocolNote", "Выполнена только первая команда, ещё ${reply.skipped} отброшено. Присылай одну команду за ответ и планируй следующий шаг по новому результату.")
                                val took = android.os.SystemClock.elapsedRealtime() - stepStarted
                                val ok = status == ToolStatus.DONE
                                chats.append(chat, "tool", label + " · " + outcome(status, result.json) + " · ${formatSeconds(took)}")
                                mutable.value = mutable.value.copy(log = (mutable.value.log + StepLog(step, label, took, ok, generation.stats?.outputPerSecond)).takeLast(200))
                                memory.record(reply, raw, result.json, result.image)
                                // Without a workspace no file step can work; the user gets the way to pick a folder at once, without further model calls.
                                if (result.json.optString("code") == FolderTools.WORKSPACE_UNAVAILABLE) {
                                    chats.append(chat, "assistant", result.json.optString("message") + "\n\n" + result.json.optString("userAction") + " Затем повторите задачу. Уже выполненные шаги сохранены.")
                                    update("Нужна рабочая папка", step); return@withTimeout
                                }
                                if (reply.name != "remember" && loopGuard.repeated(reply, result.json)) {
                                    mutable.value = mutable.value.copy(paused = true, stage = "Повторяются одинаковые шаги без изменений. Агент на паузе, чтобы сохранить квоту. Можно продолжить или остановить задачу.")
                                }
                            }
                        }
                    }
                    throw UserError("Достигнут предел ${settings.maxSteps.coerceIn(5,30)} шагов. Уточните задачу и продолжите новым сообщением.")
                }
            } catch (e: TimeoutCancellationException) {
                chats.append(chat, "assistant", "Время задачи истекло. Выполненные действия сохранены; продолжите новой командой.")
            } catch (e: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) { chats.append(chat, "assistant", stoppedMessage) }
            } catch (e: Exception) {
                val message = safeError(e); chats.append(chat, "assistant", message); mutable.value = mutable.value.copy(error = message)
            } finally {
                approval?.cancel(); approval = null;retry?.cancel();retry=null
                mutable.value = mutable.value.copy(active = false, paused = false, approval = null,retryPending=false, stage = mutable.value.error?.let { "Нужна помощь" } ?: "Готово")
                finished()
            }
        }
    }
    fun stop(message: String = "Задача остановлена. Уже выполненные действия не отменяются.") { stoppedMessage = message; job?.cancel() }
    fun togglePause() { if (mutable.value.active) mutable.value = mutable.value.copy(paused = !mutable.value.paused) }
    fun decide(accepted: Boolean) { approval?.complete(accepted) }
    fun retryRequest() {retry?.complete(Unit)}

    /** Native calls first; a text reply in native mode is either a JSON command (servers without tool templates) or the final answer. */
    private fun reply(generation: Generation, native: Boolean): AgentReply = when {
        generation.toolCalls.isNotEmpty() -> AgentProtocol.fromCalls(generation.toolCalls, generation.text)
        native -> try { AgentProtocol.parse(generation.text) } catch (e: UserError) { AgentReply.Final(generation.text.take(20_000)) }
        else -> AgentProtocol.parse(generation.text)
    }
    private suspend fun generateRecovering(settings: AppSettings, connection: Connection, step: Int, startNative: Boolean, turns: () -> List<Turn>,
        system: (Boolean) -> String, nativeRejected: () -> Unit): Generation {
        var native = startNative
        while(true) {
            awaitRunning()
            try {
                val current = turns().let { list -> if (vault.state.value.screenshots) list else list.map { it.copy(image = null) } }
                return api.generateDetailed(settings.provider, connection, current, system(native), true, { attempt, wait ->
                    update("Провайдер временно недоступен · повтор $attempt/2 через ${wait/1000} с",step)
                }, if (native) ToolRegistry.functions() else null)
            } catch(e: ProviderFailure) {
                // A server that does not support function calling answers 400; in «Авто» the task goes on with JSON commands.
                if (native && e.code == 400 && settings.toolCalling == "auto") { native = false; nativeRejected(); update("Сервер не поддерживает вызов функций — перехожу на JSON-команды", step); continue }
                if(!e.retryable) throw e
                val decision=CompletableDeferred<Unit>();retry=decision
                mutable.value=mutable.value.copy(retryPending=true,stage="${e.message} Выполненные шаги сохранены.")
                try {decision.await()} finally {retry=null;mutable.value=mutable.value.copy(retryPending=false)}
                update("Повторяю запрос модели",step)
            }
        }
    }
    internal suspend fun awaitAction() { awaitRunning(); if(!mutable.value.active) throw CancellationException("Задача остановлена") }
    private suspend fun awaitRunning() { while (mutable.value.paused) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); delay(100) }; kotlinx.coroutines.currentCoroutineContext().ensureActive() }
    private fun update(stage: String, step: Int) { mutable.value = mutable.value.copy(stage = stage, step = step) }
    private suspend fun requestApproval(value: Approval): Boolean {
        val decision = CompletableDeferred<Boolean>(); approval = decision; mutable.value = mutable.value.copy(approval = value)
        return try { decision.await() } finally { approval = null; mutable.value = mutable.value.copy(approval = null) }
    }
    private data class ToolResult(val json: JSONObject, val image: String? = null)
    private fun fingerprint(tool: AgentReply.Tool) = tool.name + JSONObject(tool.args.toString()).apply { remove("snapshot") }.toString()
    /** Every result carries a status; older tool outputs without one are done unless they report an error. */
    private fun normalize(json: JSONObject): String {
        val status = json.optString("status").takeIf { it in ToolStatus.all } ?: if (json.has("error")) ToolStatus.ERROR else ToolStatus.DONE
        json.put("status", status); return status
    }
    private fun outcome(status: String, json: JSONObject) = when (status) {
        ToolStatus.DONE -> if (json.optBoolean("verified", true)) "готово" else "готово, без проверки"
        ToolStatus.NEEDS_CONFIRMATION -> "ждёт вашего подтверждения"
        ToolStatus.NEEDS_USER_ACTION -> "нужно ваше действие"
        ToolStatus.UNKNOWN -> "результат не подтверждён"
        ToolStatus.UNSUPPORTED -> "недоступно"
        ToolStatus.DENIED -> "нет доступа"
        else -> json.optString("error").ifBlank { "ошибка" }
    }
    private fun describeTool(name: String, environment: TaskEnvironment): JSONObject {
        val spec = ToolRegistry[name] ?: return JSONObject().put("status", ToolStatus.ERROR).put("error", "Нет инструмента «${name.take(60)}».")
            .put("tools", org.json.JSONArray(ToolRegistry.names.sorted()))
        return spec.details().put("status", ToolStatus.DONE).put("availability", org.json.JSONArray(DeviceCapabilities.forTool(environment.capabilities, name).map {
            JSONObject().put("feature", it.feature).put("level", it.level).put("note", it.note) }))
    }
    private fun driver(): ScreenDriver = screen() ?: throw ToolFailure(ToolStatus.NEEDS_USER_ACTION, "Служба управления Luma выключена.",
        "Включите «Luma · управление телефоном» в специальных возможностях Android (Luma → «Доступ» → «Управление экраном»).", "accessibility")
    private suspend fun execute(tool: AgentReply.Tool): ToolResult {
        val args = tool.args
        val spec = ToolRegistry[tool.name] ?: throw ToolFailure(ToolStatus.ERROR, "Неизвестный инструмент.")
        return when {
            spec.group == "files" -> ToolResult(FolderTools(context, vault.state.value.folder).run(tool.name, args))
            tool.name in setOf("create_reminder", "list_reminders", "cancel_reminder") -> ToolResult(withContext(Dispatchers.IO) { ReminderStore(context).run(tool.name, args) })
            spec.group == "device" || spec.group == "time" || spec.group == "apps" -> {
                val result = DeviceTools(context).run(tool.name, args)
                // Opening an app or a link changes the screen; its fresh tree comes with the result.
                if (tool.name == "open_app" || tool.name == "open_url") { delay(800); withScreen(result) } else ToolResult(result)
            }
            tool.name == "screen" -> {
                val vision = args.optBoolean("image", false) && vault.state.value.screenshots && vault.state.value.connection().let { c -> c.models.firstOrNull { it.id == c.model }?.vision == true }
                val observed = driver().observe(vision)
                ToolResult(ScreenPayload.compact(observed.json, args.optString("query").take(200), args.optBoolean("detailed")).put("status", ToolStatus.DONE), observed.image)
            }
            tool.name == "wait" -> { delay(args.requiredInt("ms").coerceIn(0, 2000).toLong()); ToolResult(JSONObject().put("status", ToolStatus.DONE).put("ok", true)) }
            else -> {
                val result = driver().execute(tool.name, args)
                if (tool.name == "global_action" && args.optString("action") in setOf("lock_screen", "screenshot", "power_dialog")) ToolResult(result) else withScreen(result)
            }
        }
    }
    private suspend fun withScreen(result: JSONObject): ToolResult {
        val driver = screen() ?: return ToolResult(result)
        awaitRunning(); delay(250)
        try {
            val observed = driver.observe(false)
            result.put("screen", ScreenPayload.compact(observed.json))
        } catch (e: UserError) { result.put("screenError", e.message) }
        return ToolResult(result)
    }
    private fun describe(tool: AgentReply.Tool): String = when (tool.name) {
        "tap", "tap_point", "scroll", "swipe", "set_text" -> screen()?.actionLabel(tool.name, tool.args) ?: tool.message.ifBlank { "Действие на экране" }
        else -> if (tool.name in ToolRegistry.names) ToolRegistry.label(tool.name, tool.args) else tool.message.ifBlank { tool.name.take(40) }
    }
    private fun confirmationDetails(tool: AgentReply.Tool): String = when (tool.name) {
        "delete_file" -> "Файл: ${tool.args.optString("path")}\nУдаление окончательное, без корзины."
        "write_text" -> "Файл: ${tool.args.optString("path")}\n${if (tool.args.optBoolean("overwrite")) "Содержимое будет заменено.\n" else ""}${tool.args.optString("text").take(600)}"
        "append_text" -> "Файл: ${tool.args.optString("path")}\nБудет дописано:\n${tool.args.optString("text").take(600)}"
        "move_file", "copy_file" -> "Из: ${tool.args.optString("from")}\nВ: ${tool.args.optString("to")}"
        "rename_file" -> "${tool.args.optString("path")} → ${tool.args.optString("name")}"
        "clipboard" -> if (tool.args.optString("action") == "get") "Текст из буфера обмена будет передан модели (провайдеру). В буфере могут быть пароли или коды." else describe(tool)
        "open_url" -> "Ссылка: ${tool.args.optString("url").take(300)}"
        else -> "${describe(tool)}\nРазрешите этот шаг или остановите задачу."
    }
}
