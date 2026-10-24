package dev.luma.agent

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import dev.luma.agent.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Manual native-device checks. Install the hash-verified test packs first; never downloads during a test. */
@RunWith(AndroidJUnit4::class)
class SpeechDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun translatedNavigationFollowsTheSelectedLanguage()=runBlocking {
        val graph=(context.applicationContext as LumaApp).graph
        withContext(Dispatchers.Main) {
            graph.vault.update {it.copy(onboarded=true)}
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo?,text: String): Boolean {
            if(node==null) return false
            if(node.text?.toString()==text) return true
            return (0 until node.childCount).any {contains(node.getChild(it),text)}
        }
        try {for((code,label) in listOf("en" to "Settings","zh" to "设置","de" to "Einstellungen","fr" to "Paramètres")) {
            withContext(Dispatchers.Main) {graph.vault.update {it.copy(language=code)}}
            withTimeout(30000) {while(true) {instrumentation.uiAutomation.clearCache();if(contains(instrumentation.uiAutomation.rootInActiveWindow,label)) break;delay(100)}}
            delay(300)
            instrumentation.uiAutomation.takeScreenshot().also {bitmap ->
                File(context.getExternalFilesDir(null),"ui-$code.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        }} finally {withContext(Dispatchers.Main) {graph.vault.update {it.copy(language="ru")}}}
    }
    @Test fun decoderProducesAnEnvelopeForPlayback()=runBlocking {
        val file=File("/product/media/audio/ui/ChargingStarted.ogg")
        assertTrue(file.isFile)
        val envelope=SpeechEnvelope.decode(file)
        assertNotNull(envelope)
        assertTrue((0..150).any {envelope!!.at(it*40L)>.01f})
    }
    @Test fun fiveRecognitionPacksCanDecodeSpeech()=runBlocking {
        for(language in SpeechLanguages.all) {
            val spec=VoskPacks.get(language.code)
            val folder=File(context.noBackupFilesDir,"voice/${spec.name}")
            assertTrue("Missing ASR pack ${language.code}",folder.isDirectory)
            org.vosk.Model(folder.path).use {model ->org.vosk.Recognizer(model,16000f).use {recognizer ->
                assertFalse(recognizer.acceptWaveForm(ByteArray(1600),1600))
                assertTrue(org.json.JSONObject(recognizer.finalResult).has("text"))
            }}
        }
    }
    @Test fun downloadedVoicePlaybackAndSpeakingSurface()=runBlocking {
        val graph=(context.applicationContext as LumaApp).graph
        withContext(Dispatchers.Main) {
            graph.vault.update {it.copy(onboarded=true,theme="dark",language="ru",voice=VoiceSettings(synthesis="piper"))}
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        delay(2000)
        withContext(Dispatchers.Main) {
            graph.voice.state.value=VoiceState("conversation","Озвучиваю","Привет! Я Люма. Давай поговорим.")
            graph.voice.screen.value=true
        }
        try {
            val playback=launch(Dispatchers.Main) {graph.voice.output.speak(SpeechLanguages.get("ru").sample,VoiceSettings(synthesis="piper"))}
            withTimeout(30000) {while(graph.voice.output.level.value<.05f) {if(playback.isCompleted) error("Playback did not emit audio level");delay(20)}}
            instrumentation.uiAutomation.takeScreenshot().also {bitmap ->
                File(context.getExternalFilesDir(null),"conversation-speaking.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            playback.join()
        } finally {withContext(Dispatchers.Main) {graph.voice.stop()}}
    }
    @Test fun tenOfflineVoicesGenerateFiniteAudio()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val packs=SpeechPacks(context,scope)
        try {for(pack in SpeechPacks.all) {
            assertTrue("Missing verified pack: ${pack.id}",packs.ready(pack))
            val folder=packs.directory(pack)
            val config=OfflineTtsConfig(model=OfflineTtsModelConfig(vits=OfflineTtsVitsModelConfig(
                model=folder.listFiles()!!.first {it.extension=="onnx"}.path,tokens=File(folder,"tokens.txt").path,
                dataDir=File(folder,"espeak-ng-data").takeIf {it.isDirectory}?.path.orEmpty(),
                lexicon=File(folder,"lexicon.txt").takeIf {it.isFile}?.path.orEmpty()),numThreads=2))
            val engine=OfflineTts(config=config)
            try {
                val audio=engine.generate(SpeechLanguages.get(pack.language).sample)
                assertTrue(pack.id,audio.sampleRate in 16000..48000)
                assertTrue(pack.id,audio.samples.size>audio.sampleRate/4)
                assertTrue(pack.id,audio.samples.all {it.isFinite()})
                assertTrue(pack.id,audio.samples.any {kotlin.math.abs(it)>.01f})
            } finally {engine.release()}
        }} finally {scope.cancel()}
    }
    @Test fun conversationMicrophoneMuteAndDismiss()=runBlocking {
        val graph=(context.applicationContext as LumaApp).graph
        withContext(Dispatchers.Main) {
            graph.vault.update {it.copy(onboarded=true,language="ru",voice=VoiceSettings(synthesis="piper"),
                provider=Provider.LOCAL,connections=it.connections+(Provider.LOCAL to Connection(model="Test model",endpoint="http://127.0.0.1:11434/v1/")))}
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        delay(2500)
        instrumentation.uiAutomation.executeShellCommand("pm grant dev.luma.agent android.permission.RECORD_AUDIO").close()
        withContext(Dispatchers.Main) {graph.voice.start("conversation")}
        try {
            withTimeout(30000) {while(!graph.voice.state.value.listening) delay(100)}
            assertTrue(graph.voice.screen.value)
            // Give the instrumentation screenshot a settled particle field.
            delay(1200)
            instrumentation.uiAutomation.takeScreenshot().also {bitmap ->
                File(context.getExternalFilesDir(null),"conversation-listening.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            withContext(Dispatchers.Main) {graph.voice.toggleMute()}
            withTimeout(10000) {while(graph.voice.state.value.phase!="Микрофон выключен") delay(100)}
            assertEquals(0f,graph.voice.state.value.level)
            withContext(Dispatchers.Main) {graph.voice.toggleMute()}
            withTimeout(10000) {while(!graph.voice.state.value.listening) delay(100)}
        } finally {withContext(Dispatchers.Main) {graph.voice.stop()}}
        assertFalse(graph.voice.screen.value)
        assertFalse(graph.voice.state.value.active)
    }
}
