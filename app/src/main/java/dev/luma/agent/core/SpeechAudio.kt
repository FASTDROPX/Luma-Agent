package dev.luma.agent.core

import android.content.Context
import android.media.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.awaitVoice(): Response = suspendCancellableCoroutine {c ->
    c.invokeOnCancellation {cancel()}
    enqueue(object: Callback {
        override fun onFailure(call: Call,e: IOException) {if(c.isActive) c.resumeWithException(UserError("Речевой сервер недоступен. Проверьте подключение и адрес API."))}
        override fun onResponse(call: Call,response: Response) {if(c.isActive) c.resume(response) {_,r,_->r.close()} else response.close()}
    })
}

/** PCM is never persisted while waiting for the wake phrase. */
object VoiceWav {
    fun voiced(pcm: ByteArray): Boolean {
        var count=0
        for(i in 0 until pcm.size-1 step 2) {
            val value=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort().toInt()
            if(kotlin.math.abs(value)>500 && ++count>=1600) return true
        }
        return false
    }
    fun encode(pcm: ByteArray): ByteArray {
        val b=ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }
}

class CloudSpeech internal constructor(private val transport: (suspend (Request)->Response)?=null) {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS)
        .callTimeout(100,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).addInterceptor(localCleartextOnly).build()
    private fun request(s: VoiceSettings,path: String): Request.Builder {
        val url=(s.endpoint.trim().trimEnd('/')+"/audio/$path").toHttpUrlOrNullSafe()
        return Request.Builder().url(url).apply {if(s.key.isNotBlank()) header("Authorization","Bearer ${s.key.trim()}")}
    }
    private fun String.toHttpUrlOrNullSafe(): HttpUrl = try {toHttpUrl()}
        catch(e: Exception) {throw UserError("Укажите корректный адрес речевого API: https://…/v1")}
    private fun checked(response: Response) {
        if(!response.isSuccessful) throw UserError(when(response.code) {401,403->"Речевой API отклонил ключ или доступ.";404->"Речевой API или модель не найдены. Проверьте адрес и имена моделей.";429->"Речевой API исчерпал лимит. Попробуйте позже.";else->"Речевой API: HTTP ${response.code}."})
    }
    private suspend fun <T> response(request: Request,read: (Response)->T): T = coroutineScope {
        val call=client.newCall(request)
        // Keep cancellation attached until the response body is consumed, not just until headers arrive.
        val cancelWatch=launch(Dispatchers.IO) {try {awaitCancellation()} finally {call.cancel()}}
        try {(transport?.invoke(request) ?: call.awaitVoice()).use(read)} finally {cancelWatch.cancel()}
    }
    suspend fun transcribe(s: VoiceSettings,pcm: ByteArray): String = withContext(Dispatchers.IO) {
        val form=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model",s.sttModel)
            .addFormDataPart("language",s.language).addFormDataPart("response_format","json")
            .addFormDataPart("file","voice.wav",VoiceWav.encode(pcm).toRequestBody("audio/wav".toMediaType())).build()
        response(request(s,"transcriptions").post(form).build()) {r ->
            checked(r);val bytes=r.body?.byteStream()?.use {it.boundedVoiceBytes(100_000)} ?: byteArrayOf()
            if(bytes.size>100_000) throw UserError("Слишком большой ответ речевого API.")
            try {JSONObject(bytes.toString(Charsets.UTF_8)).optString("text").trim().take(12000)} catch(e: Exception) {throw UserError("Речевой API вернул неверную расшифровку.")}
        }
    }
    suspend fun synthesize(s: VoiceSettings,text: String): ByteArray = withContext(Dispatchers.IO) {
        val body=JSONObject().put("model",s.ttsModel).put("input",text).put("voice",s.cloudVoice).put("response_format","mp3").put("speed",s.speed.toDouble())
        response(request(s,"speech").post(body.toString().toRequestBody("application/json".toMediaType())).build()) {r ->
            checked(r);val bytes=r.body?.byteStream()?.use {it.boundedVoiceBytes(16_000_000)} ?: byteArrayOf()
            if(bytes.isEmpty() || bytes.size>16_000_000) throw UserError("Речевой API вернул некорректное аудио.")
            bytes
        }
    }
}

private fun java.io.InputStream.boundedVoiceBytes(max: Int): ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) {val n=read(buffer,0,minOf(buffer.size,max+1-out.size()));if(n<0) break;out.write(buffer,0,n);if(out.size()>max) break}
    return out.toByteArray()
}

data class DeviceVoice(val id: String,val label: String,val language: String="ru")
/** The JNI API looks up invoke(float[]) with a boxed Integer return; an indy lambda has only the generic bridge. */
class SpeechCancelCallback(private val active: ()->Boolean): (FloatArray)->Int {
    override fun invoke(samples: FloatArray): Int = if(active()) 1 else 0
}
class SpeechOutput(private val context: Context) {
    val level=MutableStateFlow(0f)
    lateinit var packs: SpeechPacks
    val voices=MutableStateFlow<List<DeviceVoice>>(emptyList())
    private var tts: TextToSpeech?=null
    private var initialized: CompletableDeferred<Unit>?=null
    private val completions=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<Unit>>()
    private val envelopes=java.util.concurrent.ConcurrentHashMap<String,SpeechEnvelope>()
    private val levelScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var levelJob: Job?=null
    private val cloud=CloudSpeech()
    private var player: MediaPlayer?=null
    private var focus: AudioFocusRequest?=null
    private val manager=context.getSystemService(AudioManager::class.java)
    suspend fun loadVoices() {try {engine()} catch(_: Exception) {}}
    private suspend fun engine(): TextToSpeech {
        if(initialized==null) {
            val ready=CompletableDeferred<Unit>();initialized=ready
            tts=TextToSpeech(context) {status ->
                if(status==TextToSpeech.SUCCESS) ready.complete(Unit) else ready.completeExceptionally(UserError("На телефоне нет движка озвучки. Установите его в настройках Android."))
            }
        }
        withTimeout(15000) {initialized!!.await()}
        return tts!!.also {engine ->
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                override fun onBeginSynthesis(id: String?,rate: Int,format: Int,channels: Int) {id?.let {envelopes[it]=SpeechEnvelope(rate,channels,format)}}
                override fun onAudioAvailable(id: String?,audio: ByteArray?) {if(id!=null && audio!=null) envelopes[id]?.append(audio)}
                override fun onStart(id: String?) {
                    val started=android.os.SystemClock.elapsedRealtime()
                    levelJob?.cancel();levelJob=levelScope.launch {while(isActive && id!=null && completions.containsKey(id)) {
                        level.value=envelopes[id]?.at(android.os.SystemClock.elapsedRealtime()-started) ?: 0f;delay(40)
                    }}
                }
                override fun onDone(id: String?) {id?.let {envelopes.remove(it);completions.remove(it)?.complete(Unit)};level.value=0f}
                @Deprecated("Android legacy callback") override fun onError(id: String?) {id?.let {completions.remove(it)?.completeExceptionally(UserError("Не удалось озвучить ответ. Проверьте голосовые данные Android."))}}
            })
            voices.value=engine.voices.orEmpty().filter {!it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty()}
                .sortedWith(compareBy({if(it.locale.language=="ru") 0 else 1},{it.name})).map {DeviceVoice(it.name,"${it.locale.getDisplayName()} · ${it.name}",it.locale.language)}
        }
    }
    suspend fun speak(text: String,s: VoiceSettings) {
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val lost=CompletableDeferred<Unit>()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener {if(it==AudioManager.AUDIOFOCUS_LOSS || it==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) lost.complete(Unit)}.build()
        focus=request
        if(manager.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {focus=null;throw UserError("Звук занят другим приложением. Повторите озвучку позже.")}
        try {coroutineScope {
            val watch=launch {lost.await();this@coroutineScope.cancel(CancellationException("Audio focus lost"))}
            try {
                if(s.synthesis=="piper") speakPack(text,s,attributes)
                else if(s.synthesis=="cloud") for(chunk in VoiceText.chunks(text,language=s.outputLanguage)) play(cloud.synthesize(s,chunk),attributes)
                else {
                    val engine=engine()
                    val voice=if(s.deviceVoice.isNotBlank()) engine.voices?.firstOrNull {it.name==s.deviceVoice && !it.isNetworkConnectionRequired}
                        ?: throw UserError("Выбранный голос недоступен. Выберите другой в настройках голоса.")
                    else engine.voices?.firstOrNull {it.locale.language==s.outputLanguage && !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty()}
                        ?: throw UserError("Установите офлайн-голос выбранного языка в настройках синтеза речи Android или скачайте голос Luma.")
                    engine.voice=voice;engine.setSpeechRate(s.speed)
                    for(chunk in VoiceText.chunks(text,language=s.outputLanguage)) {
                        val id=UUID.randomUUID().toString();val done=CompletableDeferred<Unit>();completions[id]=done
                        try {if(engine.speak(chunk,TextToSpeech.QUEUE_FLUSH,null,id)==TextToSpeech.ERROR) throw UserError("Не удалось начать озвучку.")
                            withTimeout(300000) {done.await()}
                        } finally {completions.remove(id)}
                    }
                }
            } finally {watch.cancel()}
        } } finally {stop();manager.abandonAudioFocusRequest(request);focus=null}
    }
    private suspend fun play(bytes: ByteArray,attributes: AudioAttributes) {
        val file=File.createTempFile("speech-",".mp3",context.cacheDir)
        try {
            withContext(Dispatchers.IO) {file.writeBytes(bytes)}
            val envelope=withContext(Dispatchers.IO) {try {SpeechEnvelope.decode(file)} catch(e: CancellationException) {throw e} catch(_: Exception) {null}}
            val media=MediaPlayer();player=media
            try {
                media.setAudioAttributes(attributes);media.setDataSource(file.path)
                coroutineScope {
                var prepared=false
                val monitor=launch {while(isActive) {if(prepared && media.isPlaying) level.value=envelope?.at(media.currentPosition.toLong()) ?: 0f;delay(40)}}
                try {suspendCancellableCoroutine<Unit> {c ->
                    media.setOnPreparedListener {prepared=true;if(c.isActive) it.start()}
                    media.setOnCompletionListener {if(c.isActive) c.resume(Unit)}
                    media.setOnErrorListener {_,_,_->if(c.isActive) c.resumeWithException(UserError("Не удалось воспроизвести аудио речевого API."));true}
                    media.prepareAsync()
                }} finally {monitor.cancel();level.value=0f}
                }
            } finally {media.release();if(player===media) player=null}
        } finally {withContext(NonCancellable+Dispatchers.IO) {file.delete()}}
    }
    private suspend fun speakPack(text: String,s: VoiceSettings,attributes: AudioAttributes)=withContext(Dispatchers.IO) {
        val pack=SpeechPacks.selected(s)
        if(!packs.ready(pack)) throw UserError("Сначала скачайте выбранный голос в настройках → Голос.")
        val folder=packs.directory(pack)
        val model=folder.listFiles().orEmpty().first {it.extension=="onnx"}
        val config=com.k2fsa.sherpa.onnx.OfflineTtsConfig(model=com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
            vits=com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(model=model.path,tokens=File(folder,"tokens.txt").path,
                dataDir=File(folder,"espeak-ng-data").takeIf {it.isDirectory}?.path.orEmpty(),lexicon=File(folder,"lexicon.txt").takeIf {it.isFile}?.path.orEmpty()),numThreads=2))
        val engine=com.k2fsa.sherpa.onnx.OfflineTts(config=config)
        try {
            for(chunk in VoiceText.chunks(text,350,s.outputLanguage)) {
                ensureActive()
                val coroutine=currentCoroutineContext()
                val audio=engine.generateWithCallback(chunk,speed=s.speed,callback=SpeechCancelCallback {coroutine.isActive})
                ensureActive()
                if(audio.samples.isEmpty()) throw UserError("Голос не смог озвучить текст выбранного языка.")
                val track=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(audio.sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT),8192))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                try {
                    track.play();var offset=0
                    while(offset<audio.samples.size) {ensureActive();val end=minOf(offset+audio.sampleRate/20,audio.samples.size)
                        var energy=0.0;for(i in offset until end) energy+=audio.samples[i]*audio.samples[i]
                        level.value=(kotlin.math.sqrt(energy/(end-offset))*5).toFloat().coerceIn(0f,1f)
                        val count=track.write(audio.samples,offset,end-offset,AudioTrack.WRITE_BLOCKING)
                        if(count<=0) throw UserError("Воспроизведение голоса прервалось.");offset+=count
                    }
                    val duration=audio.samples.size.toLong()*1000/audio.sampleRate
                    withTimeout(duration+5000) {while(track.playbackHeadPosition<audio.samples.size) {ensureActive();delay(20)}}
                } finally {track.stop();track.release();level.value=0f}
            }
        } finally {engine.release()}
    }
    fun stop() {levelJob?.cancel();envelopes.clear();level.value=0f;tts?.stop();completions.values.forEach {it.cancel()};completions.clear();runCatching {player?.pause()}}
    fun close() {stop();levelScope.cancel();tts?.shutdown();tts=null;initialized=null;focus?.let {manager.abandonAudioFocusRequest(it)};focus=null}
}
