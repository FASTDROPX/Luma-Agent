package dev.luma.agent.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import org.json.JSONArray
import org.json.JSONObject

class AgentController(private val context: Context, private val scope: CoroutineScope, private val vault: SettingsVault,
    private val chats: ChatStore, private val api: ProviderClient) {
    private val mutable = MutableStateFlow(RunState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private var retry: CompletableDeferred<Unit>? = null
    private var stoppedMessage = "Задача остановлена."
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
                    var step = 0
                    // The token limit pauses the task; continuing allows one more portion of the same size.
                    val tokenPortion = settings.agentTokenLimit.coerceAtLeast(0) * 1000L
                    var nextTokenPause = tokenPortion
                    while (settings.unlimitedAgent || step < settings.maxSteps.coerceIn(5,30)) {
                        awaitRunning(); step = (step + 1).coerceAtLeast(1); update("Обдумываю следующий шаг", step)
                        val stepStarted = android.os.SystemClock.elapsedRealtime()
                        val generation = generateRecovering(settings,connection,memory.turns(vault.state.value.screenshots),step)
                        val raw = generation.text
                        chats.addUsage(chat, generation.stats)
                        val spent = mutable.value.tokens + (generation.stats?.let { it.inputTokens + it.outputTokens } ?: 0)
                        mutable.value = mutable.value.copy(tokens = spent)
                        if (tokenPortion > 0 && spent >= nextTokenPause) {
                            nextTokenPause = spent + tokenPortion
                            mutable.value = mutable.value.copy(paused = true, stage = "Задача потратила ${"%,d".format(spent).replace(',', ' ')} токенов — это предел из настроек. Агент на паузе: можно продолжить ещё на столько же или остановить.")
                        }
                        awaitRunning()
                        when (val reply = AgentProtocol.parse(raw)) {
                            is AgentReply.Final -> { chats.append(chat, "assistant", reply.text, reasoning = generation.reasoning, stats = generation.stats); update("Готово", step); return@withTimeout }
                            is AgentReply.Tool -> {
                                val label = describe(reply)
                                update(label, step)
                                val blocked = AgentAccessibilityService.instance?.sensitive(reply.name, reply.args, vault.state.value.blockedWords)
                                if (AgentApprovalPolicy.requiresApproval(reply,vault.state.value.confirmActions,blocked != null)) {
                                    val details = confirmationDetails(reply) + blocked?.let { "\n$it" }.orEmpty()
                                    val accepted = requestApproval(Approval(label, details, reply.name in setOf("tap", "tap_point", "scroll", "swipe", "set_text", "back", "home")))
                                    if (!accepted) { chats.append(chat, "assistant", "Действие отменено вами. Задача остановлена."); return@withTimeout }
                                }
                                awaitRunning()
                                val result = try {
                                    if (reply.name == "remember") {
                                        memory.remember(reply.args.requiredString("notes", 4000))
                                        ToolResult(JSONObject().put("saved", true))
                                    } else execute(reply, settings, connection)
                                }
                                    catch (e: UserError) { ToolResult(JSONObject().put("error", e.message).put("note", "Не считайте действие выполненным. Обновите экран или объясните ограничение.")) }
                                    catch (e: TimeoutCancellationException) {
                                        // Only the task's own time limit may end the task; a tool's inner timeout is a failed step.
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        ToolResult(JSONObject().put("error", "Действие не завершилось вовремя.").put("note", "Не считайте действие выполненным. Обновите экран."))
                                    }
                                if (reply.skipped > 0) result.json.put("protocolNote", "Выполнена только первая команда, ещё ${reply.skipped} отброшено. Присылай одну команду за ответ и планируй следующий шаг по новому экрану.")
                                val took = android.os.SystemClock.elapsedRealtime() - stepStarted
                                val ok = !result.json.has("error")
                                chats.append(chat, "tool", label + (if (ok) " · готово" else " · ${result.json.optString("error")}") + " · ${formatSeconds(took)}")
                                mutable.value = mutable.value.copy(log = (mutable.value.log + StepLog(step, label, took, ok, generation.stats?.outputPerSecond)).takeLast(200))
                                memory.record(reply, raw, result.json, result.image)
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
    private suspend fun generateRecovering(settings: AppSettings,connection: Connection,turns: List<Turn>,step: Int): Generation {
        while(true) {
            awaitRunning()
            try {
                val currentTurns = if (vault.state.value.screenshots) turns else turns.map { it.copy(image = null) }
                return api.generateDetailed(settings.provider,connection,currentTurns,
                    ModelPrompts.agent(vault.state.value,connection,AgentAccessibilityService.connected.value),true) {attempt,wait ->
                    update("Провайдер временно недоступен · повтор $attempt/2 через ${wait/1000} с",step)
                }
            } catch(e: ProviderFailure) {
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
    private suspend fun execute(tool: AgentReply.Tool, settings: AppSettings, connection: Connection): ToolResult {
        val args = tool.args
        if (tool.name in setOf("list_files", "read_text", "write_text", "rename_file", "copy_file", "move_file", "delete_file"))
            return ToolResult(FolderTools(context, vault.state.value.folder).run(tool.name, args))
        return when (tool.name) {
            "screen" -> {
                val service = AgentAccessibilityService.instance ?: throw UserError("Включите Luma в специальных возможностях Android.")
                val vision = args.optBoolean("image", false) && vault.state.value.screenshots && connection.models.firstOrNull { it.id == connection.model }?.vision == true
                val screen = service.observe(vision)
                ToolResult(ScreenPayload.compact(screen.json, args.optString("query").take(200), args.optBoolean("detailed")), screen.image)
            }
            "list_apps" -> ToolResult(withContext(Dispatchers.IO) {
                val query = args.requiredString("query", 100)
                val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != context.packageName }
                    .filter { query.isBlank() || it.loadLabel(context.packageManager).contains(query, true) || it.activityInfo.packageName.contains(query, true) }
                    .take(60).map { JSONObject().put("name", it.loadLabel(context.packageManager)).put("package", it.activityInfo.packageName) }
                JSONObject().put("apps", JSONArray(apps))
            })
            "open_app" -> {
                val pkg = args.requiredString("package", 240)
                if (pkg == context.packageName || !pkg.matches(Regex("[A-Za-z0-9_.]+"))) throw UserError("Нельзя открыть этот пакет.")
                val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: throw UserError("Приложение не найдено. Проверьте list_apps.")
                withContext(Dispatchers.Main) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; delay(800)
                withScreen(JSONObject().put("opened", pkg))
            }
            "wait" -> { delay(args.requiredInt("ms").coerceIn(0, 2000).toLong()); ToolResult(JSONObject().put("ok", true)) }
            else -> {
                val service = AgentAccessibilityService.instance ?: throw UserError("Служба управления телефоном отключена.")
                val result = service.execute(tool.name, args)
                if (tool.name in setOf("tap", "tap_point", "scroll", "swipe", "set_text", "back", "home")) withScreen(result) else ToolResult(result)
            }
        }
    }
    private suspend fun withScreen(result: JSONObject): ToolResult {
        val service = AgentAccessibilityService.instance ?: return ToolResult(result)
        awaitRunning(); delay(250)
        try {
            val screen = service.observe(false)
            result.put("screen", ScreenPayload.compact(screen.json))
        } catch (e: UserError) { result.put("screenError", e.message) }
        return ToolResult(result)
    }
    private fun describe(tool: AgentReply.Tool): String = when (tool.name) {
        "screen" -> "Читаю текущий экран"
        "remember" -> "Сохраняю заметки задачи"
        "list_apps" -> "Ищу приложение"
        "list_files" -> "Смотрю содержимое папки"
        "read_text" -> "Читаю ${tool.args.optString("path")}".take(160)
        "write_text" -> "Записать ${tool.args.optString("path")}".take(160)
        "delete_file" -> "Удалить ${tool.args.optString("path")}".take(160)
        "rename_file" -> "Переименовать в ${tool.args.optString("name")}".take(160)
        "copy_file", "move_file" -> (if (tool.name == "copy_file") "Скопировать" else "Переместить") + " ${tool.args.optString("from")}".take(150)
        "wait" -> "Жду обновления экрана"
        else -> AgentAccessibilityService.instance?.actionLabel(tool.name, tool.args) ?: tool.message.ifBlank { "Действие на экране" }
    }
    private fun confirmationDetails(tool: AgentReply.Tool): String = when (tool.name) {
        "delete_file" -> "Файл: ${tool.args.optString("path")}\nУдаление окончательное, без корзины."
        "write_text" -> "Файл: ${tool.args.optString("path")}\n${if (tool.args.optBoolean("overwrite")) "Содержимое будет заменено.\n" else ""}${tool.args.optString("text").take(600)}"
        "move_file", "copy_file" -> "Из: ${tool.args.optString("from")}\nВ: ${tool.args.optString("to")}"
        "rename_file" -> "${tool.args.optString("path")} → ${tool.args.optString("name")}"
        else -> "${AgentAccessibilityService.instance?.actionLabel(tool.name, tool.args) ?: tool.name}\nРазрешите этот шаг или остановите задачу."
    }
}
