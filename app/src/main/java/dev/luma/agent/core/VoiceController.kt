package dev.luma.agent.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.luma.agent.AppGraph
import dev.luma.agent.VoiceService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

data class VoiceState(val mode: String="off",val phase: String="",val partial: String="",val level: Float=0f,val speakingId: Long?=null) {
    val active get()=mode!="off"
    val listening get()=phase=="Слушаю" || phase=="Жду вызова «Люма»"
}
data class DictationResult(val chat: Long?,val text: String)

/** One owner for microphone, recognizer, speech playback and conversation cancellation. */
class VoiceController(private val graph: AppGraph) {
    val model=VoiceModel(graph.context,graph.scope)
    val output=SpeechOutput(graph.context)
    val state=MutableStateFlow(VoiceState())
    val dictation=MutableStateFlow<DictationResult?>(null)
    private val cloud=CloudSpeech()
    private var job: Job?=null
    private var finishInput=false
    private var lastReplyId: Long?=null
    private var lastReply=""
    /** Called from a visible Activity (Android 14's microphone foreground-service rule). */
    fun start(mode: String) {
        if(state.value.active || job?.isCompleted==false) {graph.uiMessage.value="Дождитесь остановки текущего голосового режима.";return}
        if(!model.ready()) {graph.uiMessage.value="Сначала скачайте русскую голосовую модель в настройках → Голос.";return}
        if(graph.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {graph.uiMessage.value="Разрешите микрофон для голосового режима.";return}
        if(mode!="dictation" && graph.vault.state.value.connection().model.isBlank()) {graph.uiMessage.value="Сначала подключите модель ИИ.";return}
        launchService(mode)
    }
    private fun launchService(mode: String) {
        state.value=VoiceState(mode,"Подготовка",speakingId=if(mode=="read") lastReplyId else null)
        try {graph.context.startForegroundService(Intent(graph.context,VoiceService::class.java).putExtra("mode",mode))}
        catch(e: Exception) {state.value=VoiceState();graph.uiMessage.value="Не удалось включить голос. Откройте Luma и повторите запуск."}
    }
    fun read(id: Long,text: String) {
        if(state.value.mode=="read" && state.value.speakingId==id) {stop();return}
        if(state.value.active || job?.isCompleted==false) {graph.uiMessage.value="Сначала завершите разговор или диктовку.";return}
        lastReplyId=id;lastReply=text;launchService("read")
    }
    fun finishDictation() {finishInput=true}
    fun stop() {job?.cancel();output.stop();state.value=VoiceState();graph.context.stopService(Intent(graph.context,VoiceService::class.java))}
    fun serviceDestroyed() {job?.cancel();output.stop();state.value=VoiceState()}
    fun begin(mode: String,onFinished: ()->Unit) {
        if(job?.isActive==true) return
        finishInput=false
        val chat=graph.chats.selected.value;val agent=graph.chats.agentMode.value
        state.value=VoiceState(mode,"Подготовка",speakingId=if(mode=="read") lastReplyId else null)
        job=graph.scope.launch {
            try {
                if(mode=="read") {
                    state.value=state.value.copy(phase="Озвучиваю",speakingId=lastReplyId)
                    output.speak(lastReply,graph.vault.state.value.voice)
                } else {
                    var loaded: Model?=null
                    try {
                        withContext(Dispatchers.IO) {loaded=Model(model.directory.path)}
                        var waiting=mode=="wake"
                        var ownedChat=chat
                        while(isActive) {
                            val settings=graph.vault.state.value.voice
                            state.value=VoiceState(mode,if(waiting) "Жду вызова «Люма»" else "Слушаю")
                            val capture=capture(loaded!!,waiting,settings.recognition=="cloud")
                            var text=capture.text
                            if(waiting) {
                                if(!capture.activated) continue
                                waiting=false
                                if(text.isBlank() && !(settings.recognition=="cloud" && VoiceWav.voiced(capture.pcm))) {
                                    state.value=state.value.copy(phase="Озвучиваю",partial="Я слушаю")
                                    output.speak("Я слушаю",settings)
                                    continue
                                }
                            }
                            if(text.isBlank() && !(settings.recognition=="cloud" && VoiceWav.voiced(capture.pcm))) {
                                if(mode=="dictation") throw UserError("Речь не распознана. Попробуйте ещё раз.")
                                continue
                            }
                            if(settings.recognition=="cloud") {
                                state.value=state.value.copy(phase="Расшифровываю",partial=text,level=0f)
                                text=cloud.transcribe(settings,capture.pcm)
                                if(mode=="wake") text=VoiceText.afterWake(text,settings.wakeWords) ?: text
                            }
                            if(mode=="dictation") {dictation.value=DictationResult(chat,text);break}
                            if(VoiceText.stop(text)) break
                            if(text.isBlank()) continue
                            state.value=state.value.copy(phase="Думаю",partial=text,level=0f)
                            val result=graph.voiceTurn(text,agent,ownedChat)
                            ownedChat=result.first
                            state.value=state.value.copy(phase="Озвучиваю",partial=result.second)
                            output.speak(result.second,graph.vault.state.value.voice)
                            // Capture owns AudioRecord only while listening; playback cannot trigger itself.
                            delay(350)
                        }
                    } finally {withContext(NonCancellable+Dispatchers.IO) {loaded?.close()}}
                }
            } catch(e: CancellationException) {throw e}
            catch(e: LinkageError) {graph.uiMessage.value="Голосовой движок несовместим с этим устройством."}
            catch(e: Exception) {graph.uiMessage.value=if(e is UserError) e.message else "Голосовой режим остановлен. Проверьте микрофон, модель и настройки речи."}
            finally {output.stop();state.value=VoiceState();onFinished()}
        }
    }
    private data class Capture(val text: String,val pcm: ByteArray,val activated: Boolean=false)
    @SuppressLint("MissingPermission") // start() checks it; Android also enforces permission at foreground-service entry.
    private suspend fun capture(model: Model,wake: Boolean,cloud: Boolean): Capture = withContext(Dispatchers.IO) {
        val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(minimum<=0) throw UserError("Микрофон не поддерживает запись 16 кГц.")
        val recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum*2,8192))
        try {
            if(recorder.state!=AudioRecord.STATE_INITIALIZED) throw UserError("Микрофон недоступен.")
            Recognizer(model,16000f).use {recognizer ->
                recognizer.setWords(true)
                val aliases=graph.vault.state.value.voice.wakeWords
                val keyword=if(wake) Recognizer(model,16000f,JSONArray(VoiceText.wakeGrammar(aliases)+"[unk]").toString()).apply {setWords(true);setPartialWords(true)} else null
                try {
                recorder.startRecording()
                if(recorder.recordingState!=AudioRecord.RECORDSTATE_RECORDING) throw UserError("Android не разрешил запись микрофона.")
                val pcm=ByteArrayOutputStream();val buffer=ByteArray(3200);var elapsed=0;var heard=false;var wakeEnd: Float?=null
                var loudBytes=0;var silenceBytes=0
                fun result(json: JSONObject): Capture {
                    val end=wakeEnd
                    val bytes=pcm.toByteArray()
                    return if(wake && end!=null) Capture(VoiceText.afterWakeTime(json,end),bytes.copyOfRange((end*32000).toInt().let {it-it%2}.coerceIn(0,bytes.size),bytes.size),true)
                    else Capture(json.optString("text"),if(wake) byteArrayOf() else bytes)
                }
                while(true) {
                    ensureActive()
                    val n=recorder.read(buffer,0,buffer.size)
                    if(n<=0) throw UserError("Запись микрофона прервалась.")
                    elapsed+=n
                    // In wake mode no audio leaves this method. Bounded to a single utterance (max 60 s).
                    pcm.write(buffer,0,n)
                    if(keyword!=null && wakeEnd==null) {
                        val done=keyword.acceptWaveForm(buffer,n)
                        wakeEnd=VoiceText.wakeEnd(JSONObject(if(done) keyword.result else keyword.partialResult),aliases)
                    }
                    val ended=recognizer.acceptWaveForm(buffer,n)
                    val json=JSONObject(if(ended) recognizer.result else recognizer.partialResult)
                    val text=json.optString(if(ended) "text" else "partial")
                    heard=heard || text.isNotBlank()
                    var energy=0.0
                    for(i in 0 until n-1 step 2) {val v=(buffer[i].toInt() and 255) or (buffer[i+1].toInt() shl 8);energy+=v.toShort().toDouble()*v.toShort()}
                    val rms=sqrt(energy/(n/2))
                    if(rms>500) {loudBytes+=n;silenceBytes=0} else silenceBytes+=n
                    if(cloud && loudBytes>=6400) heard=true
                    state.value=state.value.copy(partial=if(wake) "" else text,level=(rms/5000f).toFloat().coerceIn(0f,1f))
                    if(ended && (text.isNotBlank() || wakeEnd!=null)) return@withContext result(json)
                    if(cloud && !wake && loudBytes>=6400 && silenceBytes>=32000) return@withContext result(JSONObject(recognizer.finalResult))
                    if(finishInput || elapsed>=16000*2*60 || (!wake && !heard && elapsed>=16000*2*15))
                        return@withContext result(JSONObject(recognizer.finalResult))
                }
                @Suppress("UNREACHABLE_CODE") Capture("",byteArrayOf())
                } finally {keyword?.close()}
            }
        } finally {runCatching {recorder.stop()};recorder.release()}
    }
}
