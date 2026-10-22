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
    val uiMessage = MutableStateFlow<String?>(null)
    val overlayVisible=MutableStateFlow(false)
    internal var orbAnchor: OrbAnchor?=null
    private var pendingHandoff: OrbHandoff?=null
    internal fun takeOrbHandoff(): OrbHandoff?=pendingHandoff.also {pendingHandoff=null}
    private var catalogJob: Job? = null; private var catalogRevision = 0
    private var chatJob: Job? = null
    private var chatRevision = 0
    private var pendingAgentRequest: String? = null
    init { scope.launch { try { chats.init() } catch (e: Exception) { uiMessage.value = "Не удалось открыть историю чатов." } }; vault.loadError?.let { uiMessage.value = it } }
    fun update(change: (AppSettings) -> AppSettings) { scope.launch { try { vault.update(change) } catch (e: Exception) { uiMessage.value = safeError(e) } } }
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
    fun send(text: String, asAgent: Boolean,files: List<Attachment> = emptyList()): Boolean {
        val clean = text.trim().ifBlank {if(files.isNotEmpty()) "Проанализируй вложенные файлы" else ""}
        if (clean.isBlank() || chatBusy.value || agent.state.value.active) return false
        if (clean.length > 12_000) { uiMessage.value = "Сообщение слишком длинное. Сократите его до 12 000 символов."; return false }
        if(files.size>4 || files.sumOf {it.size}>16_000_000) {uiMessage.value="Не больше 4 файлов и 16 МБ в одном сообщении.";return false}
        val settings = vault.state.value; val connection = settings.connection()
        if (connection.model.isBlank()) { uiMessage.value = "Сначала подключите провайдера и выберите модель."; return false }
        if(asAgent) pendingHandoff=orbAnchor?.let {OrbHandoff(it.x,it.y,it.radius,it.seeds())}
        chatBusy.value = true
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
                } else {
                    val answer = api.generate(settings.provider, connection, chats.history(id, connection.generation().contextTurns).dropWhile { it.role != "user" },ModelPrompts.chat(connection))
                    chats.append(id, "assistant", answer)
                }
            } catch (e: CancellationException) { }
            // The error belongs to the chat that sent the message, even if the user has opened another one.
            catch (e: Exception) { uiMessage.value = safeError(e); chatId?.let { runCatching { chats.append(it, "assistant", safeError(e)) } } }
            finally { if (revision == chatRevision) { pendingAgentRequest = null; chatBusy.value = false } }
        }
        return true
    }
    internal fun consumeAgentRequest(token: String?): Boolean {
        if (token == null || pendingAgentRequest != token) return false
        pendingAgentRequest = null; return true
    }
    fun cancelChat() { chatRevision++; pendingAgentRequest = null; chatJob?.cancel(); chatBusy.value = false }
}
