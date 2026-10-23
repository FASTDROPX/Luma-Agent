package dev.luma.agent.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceTest {
    @Test fun oldSettingsKeepVoiceDefaultsAndSecretsRoundTrip() {
        val old=AppSettings.parse(JSONObject("{\"onboarded\":true}"))
        assertEquals(VoiceSettings(),old.voice)
        val configured=old.copy(voice=VoiceSettings(recognition="cloud",synthesis="cloud",key="private-key",deviceVoice="ru-voice",wakeWords="люма, эй люма"))
        assertEquals(configured.voice,AppSettings.parse(configured.json()).voice)
    }
    @Test fun malformedVoiceSettingsAreBounded() {
        val settings=VoiceSettings.parse(JSONObject().put("speed",100).put("recognition","unknown").put("key","x".repeat(5000)))
        assertEquals(2f,settings.speed);assertEquals("local",settings.recognition);assertEquals(4000,settings.key.length)
    }
    @Test fun wakeIsPrefixWithWordBoundaries() {
        assertEquals("какая погода",VoiceText.afterWake("Эй, Люма! Какая погода?","люма, эй люма"))
        assertNull(VoiceText.afterWake("расскажи про люма","люма"))
        assertNull(VoiceText.afterWake("люмаль привет","люма"))
        assertEquals("",VoiceText.afterWake("Люма","люма"))
    }
    @Test fun syllableWakeKeepsCommandFromIndependentRecognizer() {
        assertEquals(listOf("лю ма","эй лю ма"),VoiceText.wakeGrammar("люма,эй люма"))
        val detected=JSONObject("""{"text":"эй лю ма","result":[{"word":"эй","end":0.3},{"word":"лю","end":0.45},{"word":"ма","end":0.66}]}""")
        assertEquals(.66f,VoiceText.wakeEnd(detected,"люма,эй люма")!!,.001f)
        val command=JSONObject("""{"result":[{"word":"эльма","start":0.12},{"word":"какая","start":0.99},{"word":"погода","start":1.5}]}""")
        assertEquals("какая погода",VoiceText.afterWakeTime(command,.66f))
        assertNull(VoiceText.wakeEnd(JSONObject("""{"text":"[unk]","result":[]}"""),"люма"))
    }
    @Test fun stopRequiresAWholeCommand() {
        assertTrue(VoiceText.stop("Заверши разговор!"))
        assertFalse(VoiceText.stop("объясни слово хватит"))
        assertFalse(VoiceText.stop("не закончи разговор"))
    }
    @Test fun speechOmitsCodeAndSplitsWithoutDroppingText() {
        val text="Смотри [страницу](https://example.com).\n```kotlin\nsecret()\n```\nГотово."
        assertFalse(VoiceText.spoken(text).contains("secret"))
        assertFalse(VoiceText.spoken(text).contains("https"))
        val long=("привет ".repeat(1500)).trim()
        val chunks=VoiceText.chunks(long)
        assertTrue(chunks.all {it.length<=3000});assertEquals(long,chunks.joinToString(" "))
    }
    @Test fun wavHasCorrectMonoPcmHeaderAndSize() {
        val pcm=byteArrayOf(0,1,2,3);val wav=VoiceWav.encode(pcm)
        assertEquals(48,wav.size);assertEquals("RIFF",wav.copyOfRange(0,4).toString(Charsets.US_ASCII))
        val header=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000,header.getInt(24));assertEquals(32000,header.getInt(28));assertEquals(4,header.getInt(40))
        assertArrayEquals(pcm,wav.copyOfRange(44,48))
    }
    private fun response(request: Request,body: String,code: Int=200)=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
    @Test fun cloudTranscriptionUsesSeparateEndpointAndWavMultipart() = runBlocking {
        val speech=CloudSpeech {request ->
            assertEquals("https://speech.example/v1/audio/transcriptions",request.url.toString())
            assertEquals("Bearer voice-key",request.header("Authorization"))
            val bytes=Buffer();request.body!!.writeTo(bytes);val body=bytes.readUtf8()
            assertTrue(body.contains("voice.wav"));assertTrue(body.contains("audio/wav"));assertTrue(body.contains("my-stt"));assertTrue(body.contains("RIFF"))
            response(request,"""{"text":"Привет"}""")
        }
        assertEquals("Привет",speech.transcribe(VoiceSettings(endpoint="https://speech.example/v1/",key="voice-key",sttModel="my-stt"),byteArrayOf(0,1)))
    }
    @Test fun synthesisUsesSelectedVoiceAndReturnsAudio() = runBlocking {
        val speech=CloudSpeech {request ->
            assertEquals("/v1/audio/speech",request.url.encodedPath)
            val bytes=Buffer();request.body!!.writeTo(bytes);val body=JSONObject(bytes.readUtf8())
            assertEquals("onyx",body.getString("voice"));assertEquals("my-tts",body.getString("model"));assertEquals("mp3",body.getString("response_format"))
            response(request,"audio")
        }
        assertEquals("audio",speech.synthesize(VoiceSettings(ttsModel="my-tts",cloudVoice="onyx"),"привет").toString(Charsets.UTF_8))
    }
    @Test fun cloudErrorsNeverExposeServerBodyOrKey() = runBlocking {
        val speech=CloudSpeech {response(it,"private upstream body",401)}
        val error=runCatching {speech.transcribe(VoiceSettings(key="private-key"),byteArrayOf())}.exceptionOrNull()
        assertTrue(error is UserError);assertFalse(error!!.message.orEmpty().contains("private"))
    }
    @Test fun rejectsUnboundedCloudReply() = runBlocking {
        val speech=CloudSpeech {response(it,"a".repeat(100001))}
        assertTrue(runCatching {speech.transcribe(VoiceSettings(),byteArrayOf())}.exceptionOrNull() is UserError)
    }
}
