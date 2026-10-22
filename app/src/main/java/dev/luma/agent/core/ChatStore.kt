package dev.luma.agent.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class ChatStore(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "conversations.db").path, null, 3) {
    val attachments=AttachmentStore(context)
    private val mutableConversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val mutableSelected = MutableStateFlow<Long?>(null)
    val conversations = mutableConversations.asStateFlow(); val messages = mutableMessages.asStateFlow(); val selected = mutableSelected.asStateFlow()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,role TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL,attachments TEXT NOT NULL DEFAULT '[]',reasoning TEXT NOT NULL DEFAULT '',meta TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX messages_chat ON messages(chat,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT NOT NULL DEFAULT '[]'")
        if(oldVersion<3) {db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE messages ADD COLUMN meta TEXT NOT NULL DEFAULT ''")}
    }
    suspend fun init() = withContext(Dispatchers.IO) { refresh() }
    suspend fun select(id: Long?) = withContext(Dispatchers.IO) { mutableSelected.value = id; reloadMessages() }
    suspend fun ensure(title: String): Long = withContext(Dispatchers.IO) {
        mutableSelected.value ?: writableDatabase.insertOrThrow("conversations", null, ContentValues().apply {
            put("title", title.trim().take(60)); put("updated", System.currentTimeMillis())
        }).also { mutableSelected.value = it; refresh(); reloadMessages() }
    }
    /** [reasoning] and [stats] are shown with the answer only; they are never sent back to the model. */
    suspend fun append(chat: Long, role: String, body: String,files: List<Attachment> = emptyList(),reasoning: String = "",stats: TokenStats? = null) = withContext(Dispatchers.IO) {
        if(files.size>4 || files.sumOf {it.size}>16_000_000) throw UserError("Не больше 4 вложений и 16 МБ в одном сообщении.")
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertOrThrow("messages", null, ContentValues().apply { put("chat", chat); put("role", role); put("body", body.take(100_000)); put("created", now);put("attachments",Attachment.encode(files));put("reasoning",reasoning.take(100_000));put("meta",stats?.json()?.toString().orEmpty()) })
            writableDatabase.update("conversations", ContentValues().apply { put("updated", now) }, "id=?", arrayOf(chat.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        refresh(); if (mutableSelected.value == chat) reloadMessages()
    }
    /** Only the newest [limit] messages are read, so attachments outside the context are never loaded. */
    suspend fun history(chat: Long, limit: Int = 30): List<Turn> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Turn>()
        readableDatabase.rawQuery("SELECT role,body,attachments FROM messages WHERE chat=? AND role IN ('user','assistant') ORDER BY id DESC LIMIT ?", arrayOf(chat.toString(), limit.coerceIn(1, 30).toString())).use { c ->
            var total = 0;var binary=0L
            while (c.moveToNext()) {
                val body=c.getString(1);val files=Attachment.decode(c.getString(2))
                if(total+body.length>32_000 || binary+files.sumOf {it.size}>20_000_000) break
                total+=body.length;binary+=files.sumOf {it.size}
                result+=Turn(c.getString(0),body,attachments=files.map {item ->
                    runCatching {attachments.payload(item)}.getOrElse {AttachmentData(item.name,"text/plain",text="Файл недоступен: ${item.name}")}
                })
            }
        }; result.reversed()
    }
    suspend fun delete(chat: Long) = withContext(Dispatchers.IO) {
        val files=mutableListOf<Attachment>()
        readableDatabase.rawQuery("SELECT attachments FROM messages WHERE chat=?",arrayOf(chat.toString())).use {c->while(c.moveToNext()) files+=Attachment.decode(c.getString(0))}
        writableDatabase.delete("conversations", "id=?", arrayOf(chat.toString()))
        val referenced=mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT attachments FROM messages WHERE attachments!='[]'",null).use {c->while(c.moveToNext()) referenced+=Attachment.decode(c.getString(0)).map {it.id}}
        files.distinctBy {it.id}.filterNot {it.id in referenced}.forEach {attachments.file(it).delete()}
        if (mutableSelected.value == chat) { mutableSelected.value = null; mutableMessages.value = emptyList() }; refresh()
    }
    private fun refresh() {
        val result = mutableListOf<Conversation>()
        readableDatabase.rawQuery("SELECT id,title,updated FROM conversations ORDER BY updated DESC LIMIT 100", null).use { c -> while (c.moveToNext()) result += Conversation(c.getLong(0), c.getString(1), c.getLong(2)) }
        mutableConversations.value = result
    }
    private fun reloadMessages() {
        val id = mutableSelected.value ?: run { mutableMessages.value = emptyList(); return }
        val result = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery("SELECT id,role,body,created,attachments,reasoning,meta FROM messages WHERE chat=? ORDER BY id DESC LIMIT 200", arrayOf(id.toString())).use { c -> while (c.moveToNext()) result += ChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),Attachment.decode(c.getString(4)),c.getString(5),TokenStats.parse(c.getString(6))) }
        mutableMessages.value = result.reversed()
    }
}
