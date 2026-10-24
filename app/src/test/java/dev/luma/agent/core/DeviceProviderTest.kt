package dev.luma.agent.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DeviceProviderTest {
    @Test fun deviceModelDoesNotSendNetworkRequestsAndPreservesTextAttachments()=runBlocking {
        var calls=0
        val client=ProviderClient(sender={error("Device request must not reach HTTP")},device={connection,turns,system ->
            calls++;assertEquals("file:test",connection.model);assertEquals("policy",system)
            assertEquals("привет",turns.single().text);assertEquals("notes",turns.single().attachments.single().text)
            Generation("ответ",stats=TokenStats(5,2,1,20))
        })
        val result=client.generateDetailed(Provider.DEVICE,Connection(model="file:test"),listOf(Turn("user","привет",attachments=listOf(AttachmentData("note.txt","text/plain",text="notes")))),"policy")
        assertEquals("ответ",result.text);assertEquals(1,calls);assertEquals(2,client.stats.value!!.outputTokens)
    }
    @Test fun deviceCatalogIsOffline()=runBlocking {
        val models=listOf(ModelInfo("file:test","My model"))
        assertEquals(models,ProviderClient(sender={error("No HTTP")}).models(Provider.DEVICE,Connection(models=models)))
    }
    @Test fun settingsPersistLocalContextAndRejectOutOfRangeValues() {
        val settings=AppSettings.parse(JSONObject().put("provider","DEVICE").put("localContext",999999).put("localThreads",500))
        assertEquals(Provider.DEVICE,settings.provider);assertEquals(16384,settings.localContext);assertEquals(8,settings.localThreads)
        assertEquals(settings.localContext,AppSettings.parse(settings.json()).localContext)
        assertEquals(8192,AppSettings.parse(JSONObject()).localContext)
    }
    @Test fun downloadFailuresExplainActualCause() {
        assertTrue(downloadError(UnknownHostException()).contains("DNS"))
        assertTrue(downloadError(SocketTimeoutException()).contains("вовремя"))
        assertEquals("HTTP 403",downloadError(UserError("HTTP 403")))
        assertTrue(downloadError(IOException()).contains("сохранённая часть"))
        assertFalse(downloadError(UnknownHostException()).contains("мест"))
    }
    @Test fun allVoiceSourcesUseSamePinnedArchive() {
        assertEquals(3,VoiceModel.SOURCES.size)
        assertTrue(VoiceModel.SOURCES.all {it.startsWith("https://") && it.endsWith("vosk-model-small-ru-0.22.zip")})
        assertEquals(64,VoiceModel.SHA256.length)
    }
}
