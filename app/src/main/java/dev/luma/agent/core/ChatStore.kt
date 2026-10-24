package dev.luma.agent.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tokens spent in one chat and across all chats today. */
data class UsageSummary(val chatInput: Long = 0, val chatOutput: Long = 0, val dayInput: Long = 0, val dayOutput: Long = 0)

class ChatStore(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "conversations.db").path, null, 5) {
    val attachments=AttachmentStore(context)
    private val mutableConversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val mutableSelected = MutableStateFlow<Long?>(null)
    private val mutableUsage = MutableStateFlow(UsageSummary())
    /** «Чат» or «Агент» of the selected chat; for a new chat, the mode it will be created with. */
    private val mutableAgent = MutableStateFlow(false)
    val agentMode = mutableAgent.asStateFlow()
    val conversations = mutableConversations.asStateFlow(); val messages = mutableMessages.asStateFlow(); val selected = mutableSelected.asStateFlow()
    /** Token usage of the selected chat and of today; refreshed after every recorded reply. */
    val usage = mutableUsage.asStateFlow()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,updated INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0,agent INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,role TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL,attachments TEXT NOT NULL DEFAULT '[]',reasoning TEXT NOT NULL DEFAULT '',meta TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX messages_chat ON messages(chat,id)")
        createUsage(db)
    }
    // Usage outlives deleted chats on purpose: today's total stays honest.
    private fun createUsage(db: SQLiteDatabase) = db.execSQL("CREATE TABLE usage(day TEXT NOT NULL,chat INTEGER NOT NULL,input INTEGER NOT NULL,output INTEGER NOT NULL,PRIMARY KEY(day,chat))")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT NOT NULL DEFAULT '[]'")
        if(oldVersion<3) {db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE messages ADD COLUMN meta TEXT NOT NULL DEFAULT ''")}
        if(oldVersion<4) {db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");createUsage(db)}
        if(oldVersion<5) db.execSQL("ALTER TABLE conversations ADD COLUMN agent INTEGER NOT NULL DEFAULT 0")
    }
    suspend fun init() = withContext(Dispatchers.IO) { refresh(); refreshUsage() }
    /** A saved chat brings back its own mode; a new chat keeps the mode that was on screen. */
    suspend fun select(id: Long?) = withContext(Dispatchers.IO) {
        mutableSelected.value = id
        if (id != null) mutableAgent.value = readableDatabase.rawQuery("SELECT agent FROM conversations WHERE id=?", arrayOf(id.toString())).use { c -> c.moveToFirst() && c.getInt(0) == 1 }
        reloadMessages(); refreshUsage()
    }
    /** Switches «Чат»/«Агент» at once and stores it with the selected chat, so it survives leaving the screen and restarts. */
    suspend fun setAgentMode(agent: Boolean) {
        mutableAgent.value = agent
        val chat = mutableSelected.value ?: return
        withContext(Dispatchers.IO) { writableDatabase.update("conversations", ContentValues().apply { put("agent", if (agent) 1 else 0) }, "id=?", arrayOf(chat.toString())); refresh() }
    }
    suspend fun ensure(title: String): Long = withContext(Dispatchers.IO) {
        mutableSelected.value ?: writableDatabase.insertOrThrow("conversations", null, ContentValues().apply {
            put("title", title.trim().take(60)); put("updated", System.currentTimeMillis()); put("agent", if (mutableAgent.value) 1 else 0)
        }).also { mutableSelected.value = it; refresh(); reloadMessages(); refreshUsage() }
    }
    /** [reasoning], [stats] and [search] are shown with the answer only; they are never sent back to the model.
     *  [failed] marks an error reply that the chat offers to send again. */
    suspend fun append(chat: Long, role: String, body: String,files: List<Attachment> = emptyList(),reasoning: String = "",stats: TokenStats? = null,failed: Boolean = false,
        search: WebSearch? = null,generatedFiles: List<GeneratedFile> = emptyList()): Long = withContext(Dispatchers.IO) {
        if(files.size>8 || files.sumOf {it.size}>32_000_000) throw UserError("Не больше 8 вложений и 32 МБ в одном сообщении.")
        val now = System.currentTimeMillis()
        val meta=(stats?.json() ?: JSONObject()).apply { if(failed) put("failed",true); search?.let { put("search",it.json()) };if(generatedFiles.isNotEmpty()) put("generatedFiles",org.json.JSONArray(generatedFiles.take(100).map {it.json()})) }.takeIf { it.length()>0 }?.toString().orEmpty()
        val id: Long
        writableDatabase.beginTransaction()
        try {
            id=writableDatabase.insertOrThrow("messages", null, ContentValues().apply { put("chat", chat); put("role", role); put("body", body.take(100_000)); put("created", now);put("attachments",Attachment.encode(files));put("reasoning",reasoning.take(100_000));put("meta",meta) })
            writableDatabase.update("conversations", ContentValues().apply { put("updated", now) }, "id=?", arrayOf(chat.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        refresh(); if (mutableSelected.value == chat) reloadMessages()
        id
    }
    /** Only the newest [limit] messages are read, so attachments outside the context are never loaded. */
    suspend fun history(chat: Long, limit: Int = 30): List<Turn> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Turn>()
        // Failed replies are error notes for the user, not part of the conversation with the model.
        readableDatabase.rawQuery("SELECT role,body,attachments,meta FROM messages WHERE chat=? AND role IN ('user','assistant') ORDER BY id DESC LIMIT ?", arrayOf(chat.toString(), limit.coerceIn(1, 30).toString())).use { c ->
            var total = 0;var binary=0L
            while (c.moveToNext()) {
                if(isFailed(c.getString(3))) continue
                val body=c.getString(1);val files=Attachment.decode(c.getString(2))
                if(total+body.length>32_000 || binary+files.sumOf {it.size}>20_000_000) break
                total+=body.length;binary+=files.sumOf {it.size}
                result+=Turn(c.getString(0),body,attachments=files.map {item ->
                    runCatching {attachments.payload(item)}.getOrElse {AttachmentData(item.name,"text/plain",text="Файл недоступен: ${item.name}")}
                })
            }
        }; result.reversed()
    }
    /** The latest user message of a chat, to send it again after a failed or unwanted answer. */
    suspend fun lastUserMessage(chat: Long): ChatMessage? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT id,role,body,created,attachments FROM messages WHERE chat=? AND role='user' ORDER BY id DESC LIMIT 1", arrayOf(chat.toString())).use { c ->
            if (c.moveToFirst()) ChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), Attachment.decode(c.getString(4))) else null
        }
    }
    suspend fun lastAssistant(chat: Long): ChatMessage? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT id,body,created,meta FROM messages WHERE chat=? AND role='assistant' ORDER BY id DESC LIMIT 1",arrayOf(chat.toString())).use {c ->
            if(c.moveToFirst()) ChatMessage(c.getLong(0),"assistant",c.getString(1),c.getLong(2),failed=isFailed(c.getString(3))) else null
        }
    }
    /** Removes one message; its files go too unless another message still uses them. */
    suspend fun deleteMessage(id: Long) = withContext(Dispatchers.IO) {
        val files=mutableListOf<Attachment>()
        readableDatabase.rawQuery("SELECT attachments FROM messages WHERE id=?",arrayOf(id.toString())).use {c->if(c.moveToFirst()) files+=Attachment.decode(c.getString(0))}
        writableDatabase.delete("messages","id=?",arrayOf(id.toString()))
        removeUnreferenced(files)
        reloadMessages()
    }
    suspend fun delete(chat: Long) = withContext(Dispatchers.IO) {
        val files=mutableListOf<Attachment>()
        readableDatabase.rawQuery("SELECT attachments FROM messages WHERE chat=?",arrayOf(chat.toString())).use {c->while(c.moveToNext()) files+=Attachment.decode(c.getString(0))}
        writableDatabase.delete("conversations", "id=?", arrayOf(chat.toString()))
        removeUnreferenced(files)
        if (mutableSelected.value == chat) { mutableSelected.value = null; mutableMessages.value = emptyList() }; refresh(); refreshUsage()
    }
    /** Clears every chat, including ones outside the 200-row UI page. Usage totals remain accurate. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val files=mutableListOf<Attachment>()
        readableDatabase.rawQuery("SELECT attachments FROM messages",null).use { c -> while(c.moveToNext()) files+=Attachment.decode(c.getString(0)) }
        writableDatabase.delete("conversations",null,null)
        removeUnreferenced(files)
        mutableSelected.value=null; mutableMessages.value=emptyList(); refresh(); refreshUsage()
    }
    private fun removeUnreferenced(files: List<Attachment>) {
        if(files.isEmpty()) return
        val referenced=mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT attachments FROM messages WHERE attachments!='[]'",null).use {c->while(c.moveToNext()) referenced+=Attachment.decode(c.getString(0)).map {it.id}}
        files.distinctBy {it.id}.filterNot {it.id in referenced}.forEach {attachments.file(it).delete()}
    }
    suspend fun rename(chat: Long, title: String) = withContext(Dispatchers.IO) {
        val clean=title.trim().take(80); if(clean.isEmpty()) throw UserError("Название не может быть пустым.")
        writableDatabase.update("conversations",ContentValues().apply {put("title",clean)},"id=?",arrayOf(chat.toString())); refresh()
    }
    suspend fun setPinned(chat: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        writableDatabase.update("conversations",ContentValues().apply {put("pinned",if(pinned) 1 else 0)},"id=?",arrayOf(chat.toString())); refresh()
    }
    /** Chats whose title or any message contains [query], pinned first. */
    suspend fun search(query: String): List<Conversation> = withContext(Dispatchers.IO) {
        val q=query.trim(); if(q.isEmpty()) return@withContext conversations.value
        // SQLite LIKE only folds ASCII case, so Cyrillic is matched in Kotlin.
        val needle=q.lowercase()
        val ids=mutableSetOf<Long>()
        readableDatabase.rawQuery("SELECT chat,body FROM messages ORDER BY id DESC LIMIT 5000",null).use {c->while(c.moveToNext()) if(c.getString(1).lowercase().contains(needle)) ids+=c.getLong(0)}
        queryConversations().filter {it.title.lowercase().contains(needle) || it.id in ids}
    }
    /** The whole chat as Markdown, for export. */
    suspend fun exportMarkdown(chat: Long): String = withContext(Dispatchers.IO) {
        val title=readableDatabase.rawQuery("SELECT title FROM conversations WHERE id=?",arrayOf(chat.toString())).use {c->if(c.moveToFirst()) c.getString(0) else "Чат"}
        val out=StringBuilder("# ").append(title).append("\n\n")
        val time=SimpleDateFormat("d MMMM yyyy, HH:mm",Locale("ru"))
        readableDatabase.rawQuery("SELECT role,body,created,attachments,reasoning,meta FROM messages WHERE chat=? ORDER BY id",arrayOf(chat.toString())).use {c->
            while(c.moveToNext()) {
                val role=c.getString(0)
                if(role=="tool") {out.append("> ").append(c.getString(1)).append("\n\n");continue}
                out.append("**").append(if(role=="user") "Вы" else "Luma").append("** · ").append(time.format(Date(c.getLong(2)))).append("\n\n")
                Attachment.decode(c.getString(3)).forEach {out.append("📎 ").append(it.name).append("\n\n")}
                val reasoning=c.getString(4)
                if(reasoning.isNotBlank()) out.append("<details><summary>Рассуждения</summary>\n\n").append(reasoning).append("\n\n</details>\n\n")
                out.append(c.getString(1)).append("\n\n")
                val search=runCatching {WebSearch.parse(JSONObject(c.getString(5).orEmpty().ifBlank {"{}"}).optJSONObject("search"))}.getOrNull()
                if(search!=null && search.sources.isNotEmpty()) {
                    out.append("Источники:\n\n")
                    search.sources.forEach {out.append("- [").append(it.title.replace("]","")).append("](").append(it.url).append(")\n")}
                    out.append("\n")
                }
            }
        }
        out.toString()
    }
    /** Counts tokens of one reply for its chat and for today. */
    suspend fun addUsage(chat: Long, stats: TokenStats?) = withContext(Dispatchers.IO) {
        if(stats==null || (stats.inputTokens<=0 && stats.outputTokens<=0)) return@withContext
        val day=today()
        val updated=writableDatabase.compileStatement("UPDATE usage SET input=input+?,output=output+? WHERE day=? AND chat=?").use {
            it.bindLong(1,stats.inputTokens.toLong());it.bindLong(2,stats.outputTokens.toLong());it.bindString(3,day);it.bindLong(4,chat);it.executeUpdateDelete()
        }
        if(updated==0) writableDatabase.insertOrThrow("usage",null,ContentValues().apply {put("day",day);put("chat",chat);put("input",stats.inputTokens);put("output",stats.outputTokens)})
        refreshUsage()
    }
    private fun today()=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(Date())
    private fun refreshUsage() {
        val chat=mutableSelected.value ?: -1L
        var ci=0L;var co=0L;var di=0L;var dout=0L
        readableDatabase.rawQuery("SELECT COALESCE(SUM(input),0),COALESCE(SUM(output),0) FROM usage WHERE chat=?",arrayOf(chat.toString())).use {c->if(c.moveToFirst()) {ci=c.getLong(0);co=c.getLong(1)}}
        readableDatabase.rawQuery("SELECT COALESCE(SUM(input),0),COALESCE(SUM(output),0) FROM usage WHERE day=?",arrayOf(today())).use {c->if(c.moveToFirst()) {di=c.getLong(0);dout=c.getLong(1)}}
        mutableUsage.value=UsageSummary(ci,co,di,dout)
    }
    private fun queryConversations(): List<Conversation> {
        val result = mutableListOf<Conversation>()
        readableDatabase.rawQuery("SELECT id,title,updated,pinned,agent FROM conversations ORDER BY pinned DESC,updated DESC LIMIT 200", null).use { c -> while (c.moveToNext()) result += Conversation(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3)==1, c.getInt(4)==1) }
        return result
    }
    private fun refresh() { mutableConversations.value = queryConversations() }
    private fun isFailed(meta: String?) = meta?.contains("\"failed\":true")==true
    private fun reloadMessages() {
        val id = mutableSelected.value ?: run { mutableMessages.value = emptyList(); return }
        val result = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery("SELECT id,role,body,created,attachments,reasoning,meta FROM messages WHERE chat=? ORDER BY id DESC LIMIT 200", arrayOf(id.toString())).use { c -> while (c.moveToNext()) {
            val meta=c.getString(6)
            val search=runCatching {WebSearch.parse(JSONObject(meta.orEmpty().ifBlank {"{}"}).optJSONObject("search"))}.getOrNull()
            val generated=runCatching {GeneratedFile.decode(JSONObject(meta.orEmpty().ifBlank {"{}"}).optJSONArray("generatedFiles"))}.getOrDefault(emptyList())
            result += ChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),Attachment.decode(c.getString(4)),c.getString(5),TokenStats.parse(meta),isFailed(meta),search,generated)
        } }
        mutableMessages.value = result.reversed()
    }
}
