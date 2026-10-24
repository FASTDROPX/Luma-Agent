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
import kotlin.math.sqrt

data class VoiceState(val mode: String="off",val phase: String="",val partial: String="",val level: Float=0f,val speakingId: Long?=null) {
    val active get()=mode!="off"
    val listening get()=phase=="Слушаю" || phase=="Жду вызова «Люма»"
}
data class DictationResult(val chat: Long?,val text: String)

/** One owner for microphone, recognizer, speech playback and conversation cancellation. */
class VoiceController(private val graph: AppGraph) {
    val model=VoiceModel(graph.context,graph.scope,graph.vault.state.value.voice.language)
    val packs=SpeechPacks(graph.context,graph.scope)
    val output=SpeechOutput(graph.context).also {it.packs=packs}
    val state=MutableStateFlow(VoiceState())
    val screen=MutableStateFlow(false)
    val muted=MutableStateFlow(false)
    fun restoreListening() {
        if(graph.vault.state.value.voice.alwaysListening && !state.value.active && job?.isCompleted!=false && model.ready()) start("wake")
    }
    fun disableAndStop() {graph.scope.launch {graph.vault.update {it.copy(voice=it.voice.copy(alwaysListening=false))};stop()}}
    fun toggleMute() {muted.value=!muted.value}
    val dictation=MutableStateFlow<DictationResult?>(null)
    private val cloud=CloudSpeech()
    private var job: Job?=null
    private var returnToWake=false
    private var openMode: String?=null
    private var turn: Deferred<Pair<Long,String>>?=null
    fun endConversation() {
        if(graph.vault.state.value.voice.alwaysListening) {
            screen.value=false;muted.value=false;returnToWake=true;turn?.cancel();output.stop()
        } else stop()
    }
    fun setAlwaysListening(enabled: Boolean) {
        if(enabled) {
            if(!model.ready()) {graph.uiMessage.value="Сначала скачайте модель распознавания в настройках → Голос.";return}
            if(graph.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {graph.uiMessage.value="Разрешите микрофон для голосового режима.";return}
            val settings=graph.vault.state.value
            if(settings.connection().model.isBlank()) {graph.uiMessage.value="Сначала подключите модель ИИ.";return}
            if(settings.voice.synthesis=="piper" && !packs.ready(SpeechPacks.selected(settings.voice))) {graph.uiMessage.value="Сначала скачайте выбранный голос в настройках → Голос.";return}
            graph.scope.launch {graph.vault.update {it.copy(voice=it.voice.copy(alwaysListening=true))};if(!state.value.active) start("wake")}
        } else {
            graph.scope.launch {graph.vault.update {it.copy(voice=it.voice.copy(alwaysListening=false))};if(state.value.mode in setOf("wake","conversation") && !screen.value) stop()}
        }
    }
    private var finishInput=false
    private var lastReplyId: Long?=null
    private var lastReply=""
    /** Called from a visible Activity (Android 14's microphone foreground-service rule). */
    fun start(mode: String) {
        if(mode=="conversation" && state.value.active && !screen.value && state.value.mode in setOf("wake","conversation")) {
            if(state.value.phase=="Жду вызова «Люма»") {openMode="conversation";returnToWake=false}
            muted.value=false;screen.value=true;return
        }
        if(mode=="dictation" && state.value.active && !screen.value && state.value.phase=="Жду вызова «Люма»") {
            finishInput=false;openMode="dictation";muted.value=false;return
        }
        if(state.value.active || job?.isCompleted==false) {graph.uiMessage.value="Дождитесь остановки текущего голосового режима.";return}
        if(!model.ready()) {graph.uiMessage.value="Сначала скачайте модель выбранного языка в настройках → Голос.";return}
        if(graph.vault.state.value.voice.synthesis=="piper" && !packs.ready(SpeechPacks.selected(graph.vault.state.value.voice)) && mode!="dictation") {graph.uiMessage.value="Сначала скачайте выбранный голос в настройках → Голос.";return}
        if(graph.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {graph.uiMessage.value="Разрешите микрофон для голосового режима.";return}
        if(mode!="dictation" && graph.vault.state.value.connection().model.isBlank()) {graph.uiMessage.value="Сначала подключите модель ИИ.";return}
        muted.value=false
        if(mode=="conversation") screen.value=true
        launchService(mode)
    }
    private fun launchService(mode: String) {
        state.value=VoiceState(mode,"Подготовка",speakingId=if(mode=="read") lastReplyId else null)
        try {graph.context.startForegroundService(Intent(graph.context,VoiceService::class.java).putExtra("mode",mode))}
        catch(e: Exception) {screen.value=false;state.value=VoiceState();graph.uiMessage.value="Не удалось включить голос. Откройте Luma и повторите запуск."}
    }
    fun read(id: Long,text: String) {
        if(state.value.mode=="read" && state.value.speakingId==id) {endConversation();return}
        if(state.value.active && state.value.phase=="Жду вызова «Люма»") {lastReplyId=id;lastReply=text;openMode="read";return}
        if(state.value.active || job?.isCompleted==false) {graph.uiMessage.value="Сначала завершите разговор или диктовку.";return}
        lastReplyId=id;lastReply=text;launchService("read")
    }
    fun finishDictation() {finishInput=true}
    fun stop() {screen.value=false;muted.value=false;job?.cancel();output.stop();state.value=VoiceState();graph.context.stopService(Intent(graph.context,VoiceService::class.java))}
    fun serviceDestroyed() {screen.value=false;job?.cancel();output.stop();state.value=VoiceState()}
    fun begin(mode: String,onFinished: ()->Unit) {
        if(job?.isActive==true) return
        finishInput=false;returnToWake=false;openMode=null
        val chat=graph.chats.selected.value
        state.value=VoiceState(mode,"Подготовка",speakingId=if(mode=="read") lastReplyId else null)
        job=graph.scope.launch {
            try {
                if(mode=="read") {
                    state.value=state.value.copy(phase="Озвучиваю",speakingId=lastReplyId)
                    output.speak(lastReply,graph.vault.state.value.voice)
                } else {
                    var loaded: OfflineSpeech?=null
                    try {
                        withContext(Dispatchers.IO) {loaded=OfflineSpeech(model.directory,graph.vault.state.value.voice.language)}
                        var sessionMode=mode
                        var waiting=mode=="wake"
                        var ownedChat=chat
                        while(isActive) {
                            if(returnToWake) {waiting=true;sessionMode="wake";ownedChat=null;returnToWake=false}
                            openMode?.let {sessionMode=it;waiting=false;ownedChat=graph.chats.selected.value;openMode=null}
                            while(muted.value) {state.value=state.value.copy(phase="Микрофон выключен",level=0f);delay(100)}
                            val settings=graph.vault.state.value.voice
                            if(sessionMode=="read") {
                                state.value=VoiceState("read","Озвучиваю",lastReply,speakingId=lastReplyId)
                                try {output.speak(lastReply,settings)}
                                catch(e: CancellationException) {currentCoroutineContext().ensureActive()}
                                catch(e: Exception) {graph.uiMessage.value=safeError(e)}
                                sessionMode="wake";waiting=true;continue
                            }
                            state.value=VoiceState(sessionMode,if(waiting) "Жду вызова «Люма»" else "Слушаю")
                            val capture=capture(loaded!!,waiting,settings.recognition=="cloud")
                            if(returnToWake || openMode!=null) continue
                            var text=capture.text
                            if(waiting) {
                                if(!capture.activated) continue
                                waiting=false
                                screen.value=true
                                if(text.isBlank() && !(settings.recognition=="cloud" && VoiceWav.voiced(capture.pcm))) {
                                    val welcome=SpeechLanguages.get(settings.language).listening
                                    state.value=state.value.copy(phase="Озвучиваю",partial=welcome)
                                    output.speak(welcome,settings)
                                    continue
                                }
                            }
                            if(text.isBlank() && !(settings.recognition=="cloud" && VoiceWav.voiced(capture.pcm))) {
                                if(sessionMode=="dictation") {
                                    graph.uiMessage.value="Речь не распознана. Попробуйте ещё раз."
                                    if(settings.alwaysListening) {sessionMode="wake";waiting=true;finishInput=false} else break
                                }
                                continue
                            }
                            if(settings.recognition=="cloud" && !capture.activated) {
                                state.value=state.value.copy(phase="Расшифровываю",partial=text,level=0f)
                                try {text=cloud.transcribe(settings,capture.pcm)}
                                catch(e: CancellationException) {throw e}
                                catch(e: Exception) {state.value=state.value.copy(phase="Ошибка",partial=safeError(e));delay(2500);continue}
                                if(mode=="wake") text=VoiceText.afterWake(text,settings.wakeWords) ?: text
                            }
                            if(sessionMode=="dictation") {
                                dictation.value=DictationResult(ownedChat,text)
                                if(settings.alwaysListening) {sessionMode="wake";waiting=true;finishInput=false;continue}
                                break
                            }
                            if(VoiceText.stop(text)) {
                                if(settings.alwaysListening) {screen.value=false;waiting=true;ownedChat=null;continue}
                                break
                            }
                            if(text.isBlank()) continue
                            ownedChat=answerUtterance(text,ownedChat)
                            // Capture owns AudioRecord only while listening; playback cannot trigger itself.
                            delay(350)
                        }
                    } finally {withContext(NonCancellable+Dispatchers.IO) {loaded?.release()}}
                }
            } catch(e: CancellationException) {throw e}
            catch(e: LinkageError) {graph.uiMessage.value="Голосовой движок несовместим с этим устройством."}
            catch(e: Exception) {graph.uiMessage.value=if(e is UserError) e.message else "Голосовой режим остановлен. Проверьте микрофон, модель и настройки речи."}
            finally {screen.value=false;output.stop();state.value=VoiceState();onFinished()}
        }
    }
    /** The same turn path for local and cloud recognition, preserving the call on recoverable errors. */
    internal suspend fun answerUtterance(text: String,chat: Long?): Long? {
        state.value=state.value.copy(phase="Думаю",partial=text,level=0f)
        var ownedChat=chat
        supervisorScope {
            val request=async {graph.voiceTurn(text,graph.chats.agentMode.value,chat)}
            turn=request
            try {
                val result=request.await();ownedChat=result.first
                if(!returnToWake) {
                    state.value=state.value.copy(phase="Озвучиваю",partial=result.second)
                    output.speak(result.second,graph.vault.state.value.voice)
                }
            } catch(e: CancellationException) {currentCoroutineContext().ensureActive()}
            catch(e: Exception) {state.value=state.value.copy(phase="Ошибка",partial=safeError(e),level=0f);delay(2500)}
            finally {turn=null}
        }
        return ownedChat
    }
    private data class Capture(val text: String,val pcm: ByteArray,val activated: Boolean=false)
    @SuppressLint("MissingPermission")
    private suspend fun capture(engine: OfflineSpeech,wake: Boolean,cloud: Boolean): Capture = withContext(Dispatchers.IO) {
        val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(minimum<=0) throw UserError("Микрофон не поддерживает запись 16 кГц.")
        val recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum*2,8192))
        engine.vad.reset()
        try {
            if(recorder.state!=AudioRecord.STATE_INITIALIZED) throw UserError("Микрофон недоступен.")
            recorder.startRecording()
            if(recorder.recordingState!=AudioRecord.RECORDSTATE_RECORDING) throw UserError("Android не разрешил запись микрофона.")
            val buffer=ShortArray(512);var elapsed=0
            while(true) {
                ensureActive()
                if(muted.value || returnToWake || openMode!=null) return@withContext Capture("",byteArrayOf())
                val n=recorder.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
                if(n<=0) throw UserError("Запись микрофона прервалась.")
                elapsed+=n
                val samples=FloatArray(n) {buffer[it]/32768f}
                val energy=sqrt(samples.sumOf {it.toDouble()*it}/n).toFloat()
                engine.vad.acceptWaveform(samples)
                state.value=state.value.copy(level=(energy*8f).coerceIn(0f,1f))
                if(finishInput || (!wake && elapsed>=16000*15 && !engine.vad.isSpeechDetected())) engine.vad.flush()
                if(!engine.vad.empty()) {
                    val speech=engine.vad.front().samples
                    engine.vad.pop()
                    // Microphone is released before decoding, preventing playback from feeding recognition.
                    recorder.stop()
                    val pcm=ByteArray(speech.size*2)
                    speech.forEachIndexed {i,v ->val value=(v*32767).toInt().coerceIn(-32768,32767);pcm[i*2]=value.toByte();pcm[i*2+1]=(value shr 8).toByte()}
                    if(cloud && !wake) return@withContext Capture("",pcm)
                    state.value=state.value.copy(phase=if(wake) "Жду вызова «Люма»" else "Расшифровываю",level=0f)
                    val text=engine.transcribe(speech)
                    if(wake) {
                        val command=VoiceText.afterWake(text,graph.vault.state.value.voice.wakeWords)
                        // Wake audio stays local, including when cloud dictation is selected.
                        return@withContext Capture(command.orEmpty(),byteArrayOf(),command!=null)
                    }
                    return@withContext Capture(text,pcm)
                }
                if(finishInput || (!wake && elapsed>=16000*15 && !engine.vad.isSpeechDetected())) return@withContext Capture("",byteArrayOf())
            }
            @Suppress("UNREACHABLE_CODE") Capture("",byteArrayOf())
        } finally {runCatching {recorder.stop()};recorder.release()}
    }
}
