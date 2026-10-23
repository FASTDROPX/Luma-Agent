package dev.luma.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class VaultAgentTest {
    @Test fun unlimitedAgentPassesFortyStepsWithBoundedRequestContext()=runBlocking {
        val vault=SettingsVault(context);vault.saveConnection(Provider.GEMINI,"fake","")
        vault.saveModels(Provider.GEMINI,vault.state.value.connection(),listOf(ModelInfo("gemini-test")));vault.selectModel(Provider.GEMINI,"gemini-test")
        val chats=ChatStore(context);val id=chats.ensure("long");chats.append(id,"user","Длинная задача")
        var calls=0; var initialRequestSize = 0
        val api=ProviderClient {request ->
            calls++
            val size = request.body.toString().length
            if (calls == 1) initialRequestSize = size
            assertTrue("Context grew without bound: $size", size < initialRequestSize + 12_000)
            val reply=if(calls<=40) JSONObject().put("type","tool").put("tool","remember").put("args",JSONObject().put("notes","Прогресс $calls")).toString()
                else """{"type":"final","text":"Завершено после сорока шагов"}"""
            JSONObject().put("candidates",org.json.JSONArray().put(JSONObject().put("content",JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",reply))))))
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val done=CompletableDeferred<Unit>()
        val controller=AgentController(context,scope,vault,chats,api)
        try {
            controller.start(id,"Длинная задача"){done.complete(Unit)};withTimeout(10000){done.await()}
            assertEquals(41,calls);assertEquals("Завершено после сорока шагов",chats.history(id).last().text)
            assertFalse(controller.state.value.active)
        } finally {controller.stop();scope.cancel();chats.close()}
    }
    @Test fun temporaryProviderFailureWaitsForRetryWithoutRepeatingCompletedTool()=runBlocking {
        val vault=SettingsVault(context);vault.saveConnection(Provider.GEMINI,"fake","")
        vault.saveModels(Provider.GEMINI,vault.state.value.connection(),listOf(ModelInfo("gemini-test")));vault.selectModel(Provider.GEMINI,"gemini-test")
        val chats=ChatStore(context);val id=chats.ensure("recover");chats.append(id,"user","test")
        var calls=0
        val api=ProviderClient {
            calls++
            if(calls in 2..4) throw ProviderClient.httpError(503)
            val reply=if(calls==1) """{"type":"tool","tool":"wait","args":{"ms":0}}""" else """{"type":"final","text":"Продолжил"}"""
            JSONObject().put("candidates",org.json.JSONArray().put(JSONObject().put("content",JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",reply))))))
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val done=CompletableDeferred<Unit>()
        val controller=AgentController(context,scope,vault,chats,api)
        try {
            controller.start(id,"test"){done.complete(Unit)}
            withTimeout(7000){controller.state.first {it.retryPending}}
            assertTrue(controller.state.value.active);assertEquals(4,calls)
            assertEquals(1,chats.messages.value.count {it.role=="tool"})
            controller.retryRequest();withTimeout(5000){done.await()}
            assertEquals(5,calls);assertEquals(1,chats.messages.value.count {it.role=="tool"});assertEquals("Продолжил",chats.history(id).last().text)
        } finally {controller.stop();scope.cancel();chats.close()}
    }
    private lateinit var context: Application
    @Before fun setup(){TestKeys.install();context=ApplicationProvider.getApplicationContext();File(context.noBackupFilesDir,"luma-vault.bin").delete()}
    @After fun teardown(){TestKeys.restore()}
    @Test fun keysAreEncryptedAndSelectedConfigurationSurvivesRestart() = runBlocking {
        val vault=SettingsVault(context);vault.saveConnection(Provider.GEMINI,"secret-test-key","")
        val connection=vault.state.value.connection();vault.saveModels(Provider.GEMINI,connection,listOf(ModelInfo("gemini-test","Test",true)));vault.selectModel(Provider.GEMINI,"gemini-test")
        val disk=File(context.noBackupFilesDir,"luma-vault.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains("secret-test-key"));val reopened=SettingsVault(context)
        assertNull(reopened.loadError);assertEquals("secret-test-key",reopened.state.value.connection().key);assertEquals("gemini-test",reopened.state.value.connection().model)
    }
    @Test fun changedKeyInvalidatesCatalogAndStaleReplyCannotRestoreIt() = runBlocking {
        val vault=SettingsVault(context);vault.saveConnection(Provider.GEMINI,"first-key","");val old=vault.state.value.connection()
        vault.saveModels(Provider.GEMINI,old,listOf(ModelInfo("gemini-old")));vault.selectModel(Provider.GEMINI,"gemini-old")
        vault.saveConnection(Provider.GEMINI,"second-key","");vault.saveModels(Provider.GEMINI,old,listOf(ModelInfo("gemini-stale")))
        assertEquals("",vault.state.value.connection().model);assertTrue(vault.state.value.connection().models.isEmpty())
    }
    @Test fun corruptedVaultIsReportedAndNeverSilentlyOverwritten() = runBlocking {
        File(context.noBackupFilesDir,"luma-vault.bin").writeBytes(byteArrayOf(2,3,4))
        val vault=SettingsVault(context);assertNotNull(vault.loadError)
        try{vault.saveConnection(Provider.GEMINI,"new-key","");fail()}catch(_:UserError){}
        assertArrayEquals(byteArrayOf(2,3,4),File(context.noBackupFilesDir,"luma-vault.bin").readBytes())
    }
    @Test fun deleteCommandWaitsForHumanAndRejectionDoesNotExecuteIt() = runBlocking {
        val vault=SettingsVault(context);vault.saveConnection(Provider.GEMINI,"fake-key","")
        vault.saveModels(Provider.GEMINI,vault.state.value.connection(),listOf(ModelInfo("gemini-test")));vault.selectModel(Provider.GEMINI,"gemini-test")
        vault.update{it.copy(confirmActions=true)}
        val chats=ChatStore(context);val chat=chats.ensure("Удалить файл");chats.append(chat,"user","Удалить файл")
        var calls=0
        val api=ProviderClient { calls++;JSONObject("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"type\":\"tool\",\"tool\":\"delete_file\",\"args\":{\"path\":\"note.txt\"}}"}]}}]}""") }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val done=CompletableDeferred<Unit>()
        val controller=AgentController(context,scope,vault,chats,api)
        try {
            controller.start(chat,"Удалить файл"){done.complete(Unit)}
            withTimeout(5000){controller.state.first{it.approval!=null}}
            assertTrue(controller.state.value.active);assertTrue(controller.state.value.approval!!.details.contains("окончательное"));assertEquals(1,calls)
            controller.decide(false);withTimeout(5000){done.await()}
            assertFalse(controller.state.value.active);assertTrue(chats.history(chat).last().text.contains("отменено"));assertEquals(1,calls)
        }finally{scope.cancel();chats.close()}
    }
    @Test fun cancellationRemovesPendingApprovalWithoutAcceptingAction() = runBlocking {
        val vault=SettingsVault(context);vault.saveConnection(Provider.GEMINI,"fake-key","")
        vault.saveModels(Provider.GEMINI,vault.state.value.connection(),listOf(ModelInfo("gemini-test")));vault.selectModel(Provider.GEMINI,"gemini-test")
        val chats=ChatStore(context);val chat=chats.ensure("Удалить");chats.append(chat,"user","Удалить")
        val api=ProviderClient { JSONObject("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"type\":\"tool\",\"tool\":\"delete_file\",\"args\":{\"path\":\"x.txt\"}}"}]}}]}""") }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val done=CompletableDeferred<Unit>();val controller=AgentController(context,scope,vault,chats,api)
        try{controller.start(chat,"Удалить"){done.complete(Unit)};withTimeout(5000){controller.state.first{it.approval!=null}};controller.stop();withTimeout(5000){done.await()};controller.decide(true);assertFalse(controller.state.value.active);assertNull(controller.state.value.approval)}finally{scope.cancel();chats.close()}
    }
    @Test fun agentUsesSavedModelPromptInItsRealRequest() = runBlocking {
        val vault=SettingsVault(context);vault.saveConnection(Provider.GEMINI,"fake-key","")
        vault.saveModels(Provider.GEMINI,vault.state.value.connection(),listOf(ModelInfo("gemini-test")));vault.selectModel(Provider.GEMINI,"gemini-test")
        vault.update {it.withGeneration(Provider.GEMINI,"gemini-test") {p->p.copy(instruction="CUSTOM_AGENT_STYLE")}}
        val chats=ChatStore(context);val chat=chats.ensure("Привет");chats.append(chat,"user","Привет")
        var calls=0
        val api=ProviderClient {r ->
            calls++
            val system=r.body!!.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
            assertTrue(system.contains("CUSTOM_AGENT_STYLE"));assertTrue(system.contains(AgentProtocol.system))
            JSONObject("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"type\":\"final\",\"text\":\"Привет!\"}"}]}}]}""")
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val done=CompletableDeferred<Unit>()
        val controller=AgentController(context,scope,vault,chats,api)
        try {controller.start(chat,"Привет"){done.complete(Unit)};withTimeout(5000){done.await()};assertEquals(1,calls);assertEquals("Привет!",chats.history(chat).last().text)}
        finally {scope.cancel();chats.close()}
    }
}
