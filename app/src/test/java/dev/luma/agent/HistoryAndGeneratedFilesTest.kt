package dev.luma.agent

import android.app.Application
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
@Config(sdk=[35],application=Application::class)
class HistoryAndGeneratedFilesTest {
    private lateinit var context: Application
    @Before fun setup() {context=ApplicationProvider.getApplicationContext();File(context.noBackupFilesDir,"conversations.db").delete()}

    @Test fun generatedFilesSurviveRestartAndNeverBecomeModelInput()=runBlocking {
        val store=ChatStore(context);store.init();val chat=store.ensure("Создай заметку")
        val file=GeneratedFile("Заметка.md","content://documents/note",24,123456,"text/markdown","content://documents/folder")
        store.append(chat,"user","Создай заметку")
        store.append(chat,"assistant","Готово",generatedFiles=listOf(file))
        assertEquals(listOf(file),store.messages.value.last().generatedFiles)
        assertTrue(store.history(chat).none {it.text.contains(file.uri) || it.attachments.isNotEmpty()})
        store.close()
        val restored=ChatStore(context);restored.init();restored.select(chat)
        assertEquals(file,restored.messages.value.last().generatedFiles.single());restored.close()
    }

    @Test fun clearAllDeletesChatsBeyondTheVisiblePageAndPreservesWorkspaceFiles()=runBlocking {
        val store=ChatStore(context);store.init()
        val workspace=File(context.filesDir,"keep.md").apply {writeText("keep")}
        repeat(205) {store.select(null);val id=store.ensure("Чат $it");store.append(id,"user","Сообщение")}
        assertEquals(200,store.conversations.value.size)
        store.clearAll()
        assertTrue(store.conversations.value.isEmpty());assertTrue(store.messages.value.isEmpty());assertNull(store.selected.value)
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM messages",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))}
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM conversations",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))}
        assertEquals("keep",workspace.readText());store.close()
    }

    @Test fun fullAccessNeverRequestsApprovalForAnyRegisteredTool() {
        for(name in ToolRegistry.names) assertFalse(name,AgentApprovalPolicy.requiresApproval(AgentReply.Tool(name,org.json.JSONObject().put("overwrite",true),""),false,true))
        assertTrue(AgentApprovalPolicy.requiresApproval(AgentReply.Tool("send_sms",org.json.JSONObject(),""),true))
    }
}
