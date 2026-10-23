package dev.luma.agent

import android.app.Application
import android.content.Context
import android.content.Intent
import dev.luma.agent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class CatalogState(val busy: Boolean = false, val error: String? = null, val provider: Provider? = null)
/** Text and files another app shared into Luma; the chat screen takes them into the composer. */
data class SharedContent(val text: String, val uris: List<android.net.Uri>)
/** Result of «Проверить подключение»: a short real request with its speed. */
data class ConnectionCheck(val busy: Boolean = false, val reply: String? = null, val stats: TokenStats? = null, val error: String? = null)
class LumaApp : Application() {
    lateinit var graph: AppGraph; private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val vault = SettingsVault(context)
    val chats = ChatStore(context)
    val api = ProviderClient()
    val hub = HuggingFaceClient()
    val downloads = HubDownloads(context)
    val agent = AgentController(context, scope, vault, chats, api)
    private val mutableCatalog = MutableStateFlow(CatalogState())
    val catalog = mutableCatalog.asStateFlow()
    val chatBusy = MutableStateFlow(false)
    /** SystemClock.elapsedRealtime() when the current chat request started; 0 when idle. */
    val chatStartedAt = MutableStateFlow(0L)
    val uiMessage = MutableStateFlow<String?>(null)
    val overlayVisible=MutableStateFlow(false)
    val sharedContent=MutableStateFlow<SharedContent?>(null)
    val connectionCheck=MutableStateFlow(ConnectionCheck())
    /** Luma screens currently started; a share can open a second one over the first. */
    val visibleScreens=java.util.concurrent.atomic.AtomicInteger(0)
    /** True while any Luma screen is visible; the agent notifies about a finished task only when none is. */
    val appVisible: Boolean get() = visibleScreens.get() > 0
    internal var orbAnchor: OrbAnchor?=null
    private var pendingHandoff: OrbHandoff?=null
    internal fun takeOrbHandoff(): OrbHandoff?=pendingHandoff.also {pendingHandoff=null}
    private var catalogJob: Job? = null; private var catalogRevision = 0
    private var chatJob: Job? = null
    private var chatRevision = 0
    private var pendingAgentRequest: String? = null
    init { scope.launch { try { chats.init() } catch (e: Exception) { uiMessage.value = "Не удалось открыть историю чатов." } }; vault.loadError?.let { uiMessage.value = it } }
    fun update(change: (AppSettings) -> AppSettings) { scope.launch { try { vault.update(change) } catch (e: Exception) { uiMessage.value = safeError(e) } } }
    /** «Чат» / «Агент» for the selected chat (or the next new one). */
    fun setAgentMode(agent: Boolean) { scope.launch { try { chats.setAgentMode(agent) } catch (e: Exception) { uiMessage.value = safeError(e) } } }
    fun switchProvider(provider: Provider) { cancelCatalog(); update { it.copy(provider = provider) } }
    fun connect(provider: Provider, key: String, endpoint: String) {
        cancelCatalog(); val revision = catalogRevision
        mutableCatalog.value = CatalogState(true, provider = provider)
        catalogJob = scope.launch {
            try {
                vault.saveConnection(provider, key, endpoint)
                val connection = vault.state.value.connection(provider)
                val models = api.models(provider, connection)
                vault.saveModels(provider, connection, models)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (catalogRevision == revision) mutableCatalog.value = CatalogState(false, safeError(e), provider) }
            finally { if (catalogRevision == revision) mutableCatalog.value = mutableCatalog.value.copy(busy = false) }
        }
    }
    fun cancelCatalog() { catalogRevision++; catalogJob?.cancel(); catalogJob = null; mutableCatalog.value = CatalogState() }
    fun chooseModel(provider: Provider, id: String) { scope.launch { try { vault.selectModel(provider, id) } catch (e: Exception) { uiMessage.value = safeError(e) } } }
    /** Quick switch from the chat header: provider and model in one step. */
    fun useModel(provider: Provider, id: String) { cancelCatalog(); scope.launch { try { vault.selectModel(provider, id); vault.update { it.copy(provider = provider) } } catch (e: Exception) { uiMessage.value = safeError(e) } } }
    /** Sends one short request to the selected model and reports the reply and its speed. */
    fun checkConnection() {
        if (connectionCheck.value.busy) return
        val settings = vault.state.value; val connection = settings.connection()
        if (connection.model.isBlank()) { connectionCheck.value = ConnectionCheck(error = "Сначала выберите модель."); return }
        connectionCheck.value = ConnectionCheck(busy = true)
        scope.launch {
            connectionCheck.value = try {
                val reply = api.generateDetailed(settings.provider, connection, listOf(Turn("user", "Ответь одним коротким словом: привет")), ModelPrompts.chat(connection, settings.formal))
                ConnectionCheck(reply = reply.text.ifBlank { if (reply.images.isNotEmpty()) "изображение" else "" }.take(200), stats = reply.stats)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ConnectionCheck(error = safeError(e)) }
        }
    }
    fun send(text: String, asAgent: Boolean,files: List<Attachment> = emptyList()): Boolean {
        val clean = text.trim().ifBlank {if(files.isNotEmpty()) "Проанализируй вложенные файлы" else ""}
        if (clean.isBlank() || chatBusy.value || agent.state.value.active) return false
        if (clean.length > 12_000) { uiMessage.value = "Сообщение слишком длинное. Сократите его до 12 000 символов."; return false }
        if(files.size>4 || files.sumOf {it.size}>16_000_000) {uiMessage.value="Не больше 4 файлов и 16 МБ в одном сообщении.";return false}
        val settings = vault.state.value; val connection = settings.connection()
        if (connection.model.isBlank()) { uiMessage.value = "Сначала подключите провайдера и выберите модель."; return false }
        if(asAgent) pendingHandoff=orbAnchor?.let {OrbHandoff(it.x,it.y,it.radius,it.seeds())}
        chatBusy.value = true; chatStartedAt.value = android.os.SystemClock.elapsedRealtime()
        val revision = ++chatRevision
        chatJob = scope.launch {
            var chatId: Long? = null
            try {
                val id = chats.ensure(clean); chatId = id; chats.append(id, "user", clean,files)
                if (asAgent) {
                    val token = java.util.UUID.randomUUID().toString()
                    pendingAgentRequest = token
                    context.startForegroundService(Intent(context, AgentTaskService::class.java).putExtra("chat", id).putExtra("goal", clean).putExtra("request", token))
                    withTimeout(5000) { agent.state.first { it.active && it.chatId == id } }
                } else answer(id)
            } catch (e: CancellationException) { }
            // The error belongs to the chat that sent the message, even if the user has opened another one.
            catch (e: Exception) { fail(chatId, e) }
            finally { if (revision == chatRevision) { pendingAgentRequest = null; chatBusy.value = false; chatStartedAt.value = 0L } }
        }
        return true
    }
    /** Asks the model for the next answer of [chat] from its history and stores text, reasoning and pictures. */
    private suspend fun answer(chat: Long) {
        val settings = vault.state.value; val connection = settings.connection()
        val reply = api.generateDetailed(settings.provider, connection, chats.history(chat, connection.generation().contextTurns).dropWhile { it.role != "user" }, ModelPrompts.chat(connection, settings.formal))
        val (text, inline) = GeneratedImage.extractInline(reply.text)
        val pictures = (reply.images + inline).take(8).mapIndexed { i, image -> chats.attachments.store("Изображение ${i + 1}.${image.extension}", image.mime, image.bytes) }
        chats.append(chat, "assistant", text, pictures, reply.reasoning, reply.stats)
        chats.addUsage(chat, reply.stats)
    }
    private suspend fun fail(chat: Long?, e: Exception) {
        uiMessage.value = safeError(e)
        chat?.let { runCatching { chats.append(it, "assistant", safeError(e), failed = true) } }
    }
    /** «Переотправить» / «Перегенерировать»: drops [replace] (a failed or unwanted answer) and asks again. */
    fun regenerate(chat: Long, replace: Long?) {
        if (chatBusy.value || agent.state.value.active) return
        if (vault.state.value.connection().model.isBlank()) { uiMessage.value = "Сначала подключите провайдера и выберите модель."; return }
        chatBusy.value = true; chatStartedAt.value = android.os.SystemClock.elapsedRealtime()
        val revision = ++chatRevision
        chatJob = scope.launch {
            try {
                replace?.let { chats.deleteMessage(it) }
                if (chats.lastUserMessage(chat) == null) throw UserError("В чате нет вопроса, на который можно ответить.")
                answer(chat)
            } catch (e: CancellationException) { }
            catch (e: Exception) { fail(chat, e) }
            finally { if (revision == chatRevision) { chatBusy.value = false; chatStartedAt.value = 0L } }
        }
    }
    fun deleteMessage(id: Long) { scope.launch { runCatching { chats.deleteMessage(id) }.onFailure { uiMessage.value = safeError(it) } } }
    internal fun consumeAgentRequest(token: String?): Boolean {
        if (token == null || pendingAgentRequest != token) return false
        pendingAgentRequest = null; return true
    }
    fun cancelChat() { chatRevision++; pendingAgentRequest = null; chatJob?.cancel(); chatBusy.value = false; chatStartedAt.value = 0L }
}
