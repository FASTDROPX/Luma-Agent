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
    @Test fun gigaRecognizesRussianSpeechAndVadRejectsSilence()=runBlocking {
        val folder=VoiceModel(context,CoroutineScope(Dispatchers.IO)).directory
        assertTrue(folder.isDirectory)
        val pack=SpeechPacks.all.first {it.language=="ru" && it.gender=="female"}
        val data=SpeechPacks(context,CoroutineScope(Dispatchers.IO)).directory(pack)
        val tts=OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(vits=OfflineTtsVitsModelConfig(
            model=data.listFiles()!!.first {it.extension=="onnx"}.path,tokens=File(data,"tokens.txt").path,
            dataDir=File(data,"espeak-ng-data").path),numThreads=2)))
        val asr=OfflineSpeech(folder,"ru")
        val report=StringBuilder()
        try {
            repeat(100) {asr.vad.acceptWaveform(FloatArray(512))}
            assertTrue(asr.vad.empty())
            val cases=listOf("Привет, как твои дела?" to listOf("привет","твои","дела"),
                "Открой браузер и найди прогноз погоды на завтра." to listOf("браузер","погоды","завтра"),
                "Поставь таймер на пятнадцать минут." to listOf("таймер","минут"))
            for((phrase,words) in cases) {
                val audio=tts.generate(phrase)
                val pcm=FloatArray((audio.samples.size*16000L/audio.sampleRate).toInt()) {i ->
                    val at=i*audio.sampleRate/16000f;val n=at.toInt().coerceAtMost(audio.samples.lastIndex)
                    audio.samples[n]+(audio.samples[minOf(n+1,audio.samples.lastIndex)]-audio.samples[n])*(at-n)
                }
                val started=System.currentTimeMillis()
                val text=withContext(Dispatchers.IO) {asr.transcribe(pcm)}
                report.appendLine("$phrase => $text (${System.currentTimeMillis()-started} ms)")
                File(context.getExternalFilesDir(null),"giga-recognition.txt").writeText(report.toString())
                words.forEach {assertTrue("Missing $it in $text",VoiceText.normalize(text).contains(it))}
                asr.vad.reset()
                pcm.asList().chunked(512).forEach {asr.vad.acceptWaveform(it.toFloatArray())}
                repeat(40) {asr.vad.acceptWaveform(FloatArray(512))}
                assertFalse("Speech was not detected",asr.vad.empty())
            }
            val real=File(context.noBackupFilesDir,"voice/giga-test/example.wav").readBytes()
            assertEquals("RIFF",String(real,0,4))
            val raw=java.nio.ByteBuffer.wrap(real,44,real.size-44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples=FloatArray(raw.remaining()) {raw.get()/32768f}
            val decoded=withContext(Dispatchers.IO) {asr.transcribe(samples)}
            assertTrue(decoded,decoded.contains("похвал"));assertTrue(decoded,decoded.contains("лукоморья"))
        } finally {asr.release();tts.release()}
    }
    @Test fun multilingualRecognitionMeetsTheSpeechAccuracyThreshold()=runBlocking {
        val phrases=mapOf("en" to ("Open the browser and find the weather for tomorrow." to listOf("browser","weather")),
            "de" to ("Öffne den Browser und suche das Wetter für morgen." to listOf("browser","wetter")),
            "fr" to ("Ouvre le navigateur et cherche la météo pour demain." to listOf("navigateur","météo")),
            "zh" to ("打开浏览器，查询明天的天气。" to listOf("浏览器","明天","天气")))
        val report=StringBuilder();val failures=mutableListOf<String>()
        for((language,example) in phrases) {
            val pack=SpeechPacks.all.first {it.language==language && it.gender=="female"}
            val data=SpeechPacks(context,CoroutineScope(Dispatchers.IO)).directory(pack)
            val tts=OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(vits=OfflineTtsVitsModelConfig(
                model=data.listFiles()!!.first {it.extension=="onnx"}.path,tokens=File(data,"tokens.txt").path,
                dataDir=File(data,"espeak-ng-data").takeIf {it.isDirectory}?.path.orEmpty(),
                lexicon=File(data,"lexicon.txt").takeIf {it.isFile}?.path.orEmpty()),numThreads=2)))
            val asr=OfflineSpeech(VoiceModel(context,CoroutineScope(Dispatchers.IO),language).directory,language)
            try {
                val audio=tts.generate(example.first)
                val samples=FloatArray((audio.samples.size*16000L/audio.sampleRate).toInt()) {i ->
                    val at=i*audio.sampleRate/16000f;val n=at.toInt().coerceAtMost(audio.samples.lastIndex)
                    audio.samples[n]+(audio.samples[minOf(n+1,audio.samples.lastIndex)]-audio.samples[n])*(at-n)
                }
                val text=withContext(Dispatchers.IO) {asr.transcribe(samples)}
                fun units(value: String)=VoiceText.normalize(value).let {if(language=="zh") it.filterNot(Char::isWhitespace).map(Char::toString) else it.split(' ')}
                val ref=units(example.first);val hyp=units(text)
                var previous=IntArray(hyp.size+1) {it}
                ref.forEachIndexed {row,word ->val current=IntArray(hyp.size+1);current[0]=row+1
                    hyp.forEachIndexed {column,got ->current[column+1]=minOf(current[column]+1,previous[column+1]+1,previous[column]+if(word==got) 0 else 1)}
                    previous=current
                }
                val error=previous.last().toFloat()/ref.size
                report.appendLine("$language: ${example.first} => $text (word/character error=$error)")
                File(context.getExternalFilesDir(null),"whisper-languages.txt").writeText(report.toString())
                // Accuracy check, not a claim of verbatim transcription for every accent or TTS voice.
                if(error>.25f || text.isBlank()) failures+="$language: $text (error=$error)"
            } finally {asr.release();tts.release()}
        }
        assertTrue(failures.joinToString("; "),failures.isEmpty())
    }
    @Test fun speechDownloadResumesACompletePartAndFetchesTheMissingVad()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val model=VoiceModel(context,scope,"ru")
        val vad=File(model.directory,"silero_vad.onnx")
        val backup=vad.readBytes()
        val marker=File(model.directory,".verified")
        try {
            marker.delete()
            // A cancellation after the last byte must not issue Range at EOF (HTTP 416).
            val tokens=File(model.directory,"tokens.txt")
            assertTrue(tokens.renameTo(File(model.directory,"tokens.txt.part")))
            vad.delete()
            withContext(Dispatchers.Main) {model.download()}
            withTimeout(120000) {while(model.state.value.busy) delay(100)}
            assertTrue(model.state.value.error.orEmpty(),model.state.value.ready)
            assertTrue(model.ready())
        } finally {
            model.cancel();delay(100)
            vad.writeBytes(backup)
            val part=File(model.directory,"tokens.txt.part");if(part.isFile) part.renameTo(File(model.directory,"tokens.txt"))
            marker.writeText(model.spec.manifest)
            scope.cancel()
        }
    }
    @Test fun voiceAgentAnswersAndWorksInsideConversation()=runBlocking {
        val graph=(context.applicationContext as LumaApp).graph
        val server=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val serving=launch(Dispatchers.IO) {
            var request=0
            while(isActive) {
                val socket=try {server.accept()} catch(e: java.net.SocketException) {break}
                socket.use {
                    val input=it.getInputStream().buffered()
                    fun line(): String {val out=java.io.ByteArrayOutputStream();while(true) {val c=input.read();if(c<0 || c==10) break;if(c!=13) out.write(c)};return out.toString("UTF-8")}
                    var length=0
                    while(true) {val line=line();if(line.isEmpty()) break;if(line.startsWith("Content-Length:",true)) length=line.substringAfter(':').trim().toInt()}
                    var remaining=length;val buffer=ByteArray(8192)
                    while(remaining>0) {val count=input.read(buffer,0,minOf(remaining,buffer.size));if(count<0) break;remaining-=count}
                    val json=when(request++) {
                        0 -> """{"choices":[{"finish_reason":"stop","message":{"content":"Привет! Я слушаю тебя."}}]}"""
                        1 -> """{"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[{"id":"status","type":"function","function":{"name":"device_status","arguments":"{}"}}]}}]}"""
                        else -> {delay(2600);"""{"choices":[{"finish_reason":"stop","message":{"content":"Я проверила состояние телефона."}}]}"""}
                    }
                    val bytes=json.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                }
            }
        }
        withContext(Dispatchers.Main) {
            graph.vault.update {it.copy(onboarded=true,theme="dark",language="ru",toolCalling="native",confirmActions=false,voice=VoiceSettings(synthesis="piper"),
                provider=Provider.LOCAL,connections=it.connections+(Provider.LOCAL to Connection(model="Voice regression",endpoint="http://127.0.0.1:${server.localPort}/v1/")))}
            graph.chats.setAgentMode(true)
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            graph.voice.state.value=VoiceState("conversation","Слушаю")
            graph.voice.screen.value=true
        }
        delay(1500)
        try {
            val greeting=async(Dispatchers.Main) {graph.voice.answerUtterance("привет",null)}
            withTimeout(30000) {while(graph.voice.state.value.phase!="Озвучиваю") {if(greeting.isCompleted) error("Greeting did not reach speech: ${graph.voice.state.value}");delay(20)}}
            assertTrue("Greeting dismissed the call",graph.voice.screen.value)
            assertFalse("Greeting activated device UI",graph.overlayVisible.value)
            val chat=greeting.await();assertNotNull(chat)
            assertTrue(graph.voice.state.value.partial.contains("Привет"))
            val task=async(Dispatchers.Main) {graph.voice.answerUtterance("Проверь состояние телефона",chat)}
            withTimeout(30000) {while(!graph.agent.state.value.acting) {if(task.isCompleted) error("No agent action");delay(20)}}
            delay(1250)
            assertTrue(graph.voice.screen.value)
            assertTrue("Voice orb was not used as the handoff origin",graph.orbAnchor?.immersive==true)
            instrumentation.uiAutomation.takeScreenshot().also {bitmap ->File(context.getExternalFilesDir(null),"conversation-agent.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            task.await()
            assertTrue(graph.voice.screen.value)
            assertEquals("Озвучиваю",graph.voice.state.value.phase)
            assertTrue(graph.voice.state.value.partial.contains("состояние"))
        } finally {
            server.close();serving.cancelAndJoin()
            withContext(Dispatchers.Main) {graph.voice.stop();graph.agent.stop()}
        }
    }
    @Test fun persistentWakeListeningSurvivesHomeAndLockAndHangup()=runBlocking {
        val graph=(context.applicationContext as LumaApp).graph
        withContext(Dispatchers.Main) {
            graph.vault.update {it.copy(onboarded=true,voice=VoiceSettings(synthesis="piper"),provider=Provider.LOCAL,
                connections=it.connections+(Provider.LOCAL to Connection(model="Test model",endpoint="http://127.0.0.1:11434/v1/")))}
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        instrumentation.uiAutomation.executeShellCommand("pm grant dev.luma.agent android.permission.RECORD_AUDIO").close()
        delay(1000)
        try {
            withContext(Dispatchers.Main) {graph.voice.setAlwaysListening(true)}
            withTimeout(30000) {while(!graph.voice.state.value.listening) delay(100)}
            assertTrue(graph.vault.state.value.voice.alwaysListening)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            delay(1500)
            assertTrue(graph.voice.state.value.active)
            assertTrue(graph.voice.state.value.listening)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            withContext(Dispatchers.Main) {context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));graph.voice.start("conversation")}
            withTimeout(10000) {while(graph.voice.state.value.phase!="Слушаю") delay(100)}
            assertTrue(graph.voice.screen.value)
            withContext(Dispatchers.Main) {graph.voice.endConversation()}
            withTimeout(10000) {while(graph.voice.state.value.phase!="Жду вызова «Люма»") delay(100)}
            assertFalse(graph.voice.screen.value)
            assertTrue(graph.vault.state.value.voice.alwaysListening)
            withContext(Dispatchers.Main) {graph.voice.start("dictation")}
            withTimeout(10000) {while(graph.voice.state.value.mode!="dictation") delay(100)}
            withContext(Dispatchers.Main) {graph.voice.finishDictation()}
            withTimeout(10000) {while(graph.voice.state.value.phase!="Жду вызова «Люма»") delay(100)}
            assertTrue(graph.vault.state.value.voice.alwaysListening)
            withContext(Dispatchers.Main) {graph.voice.read(17,"Привет! Я продолжаю слушать.")}
            withTimeout(10000) {while(graph.voice.state.value.mode!="read") delay(100)}
            withTimeout(30000) {while(graph.voice.state.value.phase!="Жду вызова «Люма»") delay(100)}
            withContext(Dispatchers.Main) {graph.voice.disableAndStop()}
            withTimeout(10000) {while(graph.voice.state.value.active || graph.vault.state.value.voice.alwaysListening) delay(100)}
        } finally {
            withContext(Dispatchers.Main) {graph.voice.disableAndStop()}
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
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
            delay(700)
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
