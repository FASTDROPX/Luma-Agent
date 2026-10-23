package dev.luma.agent

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatModeAndRemoteServerTest {
    private lateinit var context: Application
    private val database get() = File(context.noBackupFilesDir, "conversations.db")
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); database.delete() }

    @Test fun eachChatKeepsItsModeAcrossScreensAndRestarts() = runBlocking {
        val chats = ChatStore(context); chats.init()
        chats.setAgentMode(true)                       // chosen before the first message of a new chat
        val agentChat = chats.ensure("Включи фонарик")
        assertTrue(chats.agentMode.value)
        chats.select(null); chats.setAgentMode(false)
        val talkChat = chats.ensure("Привет")
        chats.select(agentChat); assertTrue(chats.agentMode.value)
        chats.select(talkChat); assertFalse(chats.agentMode.value)
        chats.setAgentMode(true); chats.setAgentMode(false) // switching in a chat is stored at once
        chats.close()

        val reopened = ChatStore(context); reopened.init()
        reopened.select(agentChat); assertTrue(reopened.agentMode.value)
        reopened.select(talkChat); assertFalse(reopened.agentMode.value)
        assertEquals(mapOf(agentChat to true, talkChat to false), reopened.conversations.value.associate { it.id to it.agent })
        // A new chat starts in the mode that was on screen.
        reopened.select(agentChat); reopened.select(null); assertTrue(reopened.agentMode.value)
        reopened.close()
    }

    @Test fun olderHistoryGetsTheModeColumnAndStartsInChatMode() = runBlocking {
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,updated INTEGER NOT NULL,pinned INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,role TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL,attachments TEXT NOT NULL DEFAULT '[]',reasoning TEXT NOT NULL DEFAULT '',meta TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE usage(day TEXT NOT NULL,chat INTEGER NOT NULL,input INTEGER NOT NULL,output INTEGER NOT NULL,PRIMARY KEY(day,chat))")
            db.insert("conversations", null, ContentValues().apply { put("title", "Старый чат"); put("updated", 1L) })
            db.version = 4
        }
        val chats = ChatStore(context); chats.init()
        val old = chats.conversations.value.single()
        assertEquals("Старый чат", old.title); assertFalse(old.agent)
        chats.select(old.id); chats.setAgentMode(true); chats.select(null); chats.select(old.id)
        assertTrue(chats.agentMode.value)
        chats.close()
    }

    @Test fun homeServerWorksThroughTailscaleButPublicHttpStaysBlocked() {
        assertEquals("http://100.101.102.103:11434/v1/", validateEndpoint("http://100.101.102.103:11434/v1"))
        assertEquals("http://my-pc.tail1234.ts.net:11434/v1/", validateEndpoint("http://my-pc.tail1234.ts.net:11434/v1"))
        assertEquals("http://[fd7a:115c:a1e0::1]:11434/v1/", validateEndpoint("http://[fd7a:115c:a1e0::1]:11434/v1"))
        assertEquals("https://llm.example.com/v1/", validateEndpoint("https://llm.example.com/v1"))
        // Outside 100.64.0.0/10, look-alike names and public hosts still need HTTPS.
        for (host in listOf("100.63.0.1", "100.128.0.1", "evil-ts.net", "ts.net.evil.com", "my-pc.ts.net.example.org", "8.8.8.8"))
            assertFalse(host, isPrivateHost(host))
        try { validateEndpoint("http://my-pc.example.com:11434/v1"); fail() } catch (e: UserError) { assertTrue(e.message.contains("HTTPS")) }
        assertTrue(isPrivateHost("100.64.0.1")); assertTrue(isPrivateHost("100.127.255.254"))
        // The transport check uses the same rule, so a Tailscale address is not refused before connecting.
        val client = okhttp3.OkHttpClient.Builder().addInterceptor(localCleartextOnly).build()
        try { client.newCall(okhttp3.Request.Builder().url("http://100.64.0.1:9/").build()).execute().close() } catch (e: java.io.IOException) { assertFalse(e is CleartextBlocked) }
        assertEquals("Установите Tailscale", Tone.adapt("Установи Tailscale", formal = true))
    }
}
