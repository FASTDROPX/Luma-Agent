package dev.luma.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import dev.luma.agent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Robolectric
import java.io.File

/** Records what the agent asked the screen for; no Accessibility service is involved. */
class FakeScreen(private val failWith: Map<String, ToolFailure> = emptyMap()) : ScreenDriver {
    val executed = mutableListOf<Pair<String, JSONObject>>()
    var observed = 0
    var observedWithImage = 0
    override suspend fun observe(withImage: Boolean): ScreenResult {
        observed++; if (withImage) observedWithImage++
        return ScreenResult(JSONObject().put("snapshot", "s$observed").put("package", "com.example.app").put("width", 1080).put("height", 2400).put("nodes", JSONArray()))
    }
    override suspend fun execute(name: String, args: JSONObject): JSONObject {
        executed += name to args
        failWith[name]?.let { throw it }
        return JSONObject().put("status", ToolStatus.DONE).put("verified", true)
    }
    override fun sensitive(name: String, args: JSONObject, blockedWords: String): String? = null
    override fun actionLabel(name: String, args: JSONObject): String = name
}

object Replies {
    fun openAiText(text: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", text))))
    fun openAiCall(name: String, args: JSONObject, id: String = "call_$name") = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args.toString())))))))
    fun geminiText(text: String) = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
        .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))))
    fun command(name: String, args: JSONObject = JSONObject()) = JSONObject().put("type", "tool").put("tool", name).put("args", args).toString()
    fun final(text: String) = JSONObject().put("type", "final").put("text", text).toString()
}

/** One agent task against a scripted provider; [reply] answers the n-th request (0-based). */
internal class AgentRun(val context: Context, provider: Provider = Provider.LOCAL, model: ModelInfo = ModelInfo("qwen3:8b", vision = false),
    configure: (AppSettings) -> AppSettings = { it.copy(confirmActions = false) }, val screen: ScreenDriver? = FakeScreen(),
    private val reply: (Int, WireRequest) -> JSONObject) {
    val vault = SettingsVault(context)
    val chats = ChatStore(context)
    val requests = mutableListOf<WireRequest>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val controller: AgentController
    var chat = 0L; private set
    init {
        runBlocking {
            vault.saveConnection(provider, if (provider.keyRequired) "fake-key" else "", if (provider == Provider.LOCAL) "http://127.0.0.1:11434/v1/" else "")
            vault.saveModels(provider, vault.state.value.connection(provider), listOf(model)); vault.selectModel(provider, model.id)
            vault.update(configure)
        }
        controller = AgentController(context, scope, vault, chats, ProviderClient { request -> requests += request; reply(requests.size - 1, request) })
        controller.screen = { screen }
    }
    /** Starts the task; [decide] answers approvals (null = leave pending and return). */
    fun run(goal: String, decide: ((Approval) -> Boolean)? = { true }): List<ChatMessage> = runBlocking {
        chat = chats.ensure(goal); chats.append(chat, "user", goal)
        val done = CompletableDeferred<Unit>()
        controller.start(chat, goal) { done.complete(Unit) }
        withTimeout(10_000) {
            while (!done.isCompleted) {
                val state = controller.state.first { it.approval != null || !it.active }
                val approval = state.approval
                if (approval != null) { if (decide == null) return@withTimeout; controller.decide(decide(approval)) }
                else done.await()
                yield()
            }
        }
        chats.messages.value
    }
    fun close() { controller.stop(); scope.cancel(); chats.close() }
}
internal val WireRequest.bodyText: String get() = body?.toString().orEmpty()

/**
 * A documents provider over a temporary folder that speaks the Storage Access Framework protocol (document
 * and children queries, android:createDocument/renameDocument/deleteDocument calls, openFile), so FolderTools
 * goes through the same DocumentsContract calls as on a phone. It extends ContentProvider rather than
 * DocumentsProvider because Robolectric's resolver uses the pre-Android-O query call that DocumentsProvider rejects.
 */
class TestDocumentsProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        lateinit var root: File
        val createdTypes = mutableListOf<String>()
        fun install(dir: File, folderName: String = "Documents"): Uri {
            root = File(dir, folderName).apply { mkdirs() }; createdTypes.clear()
            Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(ProviderInfo().apply { authority = AUTHORITY; exported = true; grantUriPermissions = true })
            return DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:$folderName")
        }
    }
    private val rootId get() = "primary:${root.name}"
    private fun file(id: String): File = if (id == rootId) root else File(root, id.removePrefix("$rootId/"))
    private fun id(f: File): String = if (f == root) rootId else "$rootId/" + f.relativeTo(root).path
    private fun tree(uri: Uri) = DocumentsContract.buildTreeDocumentUri(AUTHORITY, DocumentsContract.getTreeDocumentId(uri))
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
    private fun add(cursor: MatrixCursor, f: File) {
        val dir = f.isDirectory
        cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id(f)).add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, f.name)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (dir) DocumentsContract.Document.MIME_TYPE_DIR else if (f.extension == "txt") "text/plain" else "application/octet-stream")
            .add(DocumentsContract.Document.COLUMN_SIZE, if (dir) null else f.length()).add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(DocumentsContract.Document.COLUMN_FLAGS, if (dir) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: columns)
        val target = file(DocumentsContract.getDocumentId(uri))
        if (uri.lastPathSegment == "children") target.listFiles()?.sortedBy { it.name }?.forEach { add(cursor, it) }
        else if (target.exists()) add(cursor, target)
        return cursor
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        @Suppress("DEPRECATION") val uri = extras?.getParcelable<Uri>("uri") ?: return null
        val target = file(DocumentsContract.getDocumentId(uri))
        return when (method) {
            "android:createDocument" -> {
                val mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)!!; createdTypes += mime
                val f = File(target, extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) f.mkdirs() else f.createNewFile()
                Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(tree(uri), id(f))) }
            }
            "android:renameDocument" -> {
                val renamed = File(target.parentFile, extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!); target.renameTo(renamed)
                Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(tree(uri), id(renamed))) }
            }
            "android:deleteDocument" -> { target.delete(); Bundle() }
            else -> null
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode))
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
