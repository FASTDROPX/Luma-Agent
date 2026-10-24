package dev.luma.agent.core

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class VoiceDownload(val busy: Boolean=false,val progress: Float=0f,val ready: Boolean=false,val error: String?=null,val phase: String="")
data class AsrAsset(val name: String,val size: Long,val sha256: String,val url: String)
data class AsrPack(val name: String,val title: String,val assets: List<AsrAsset>) {
    val size get()=assets.sumOf {it.size}
    val manifest get()=assets.joinToString("\n") {it.sha256}
}

/** GigaAM for Russian, SenseVoice for Chinese, Whisper for the other languages. Old Vosk files are never loaded. */
class VoiceModel(private val context: Context,private val scope: CoroutineScope,language: String="ru") {
    private var selected=pack(language)
    val spec get()=selected
    val directory get()=File(context.noBackupFilesDir,"voice/${selected.name}")
    fun select(language: String) { if(!state.value.busy) {selected=pack(language);state.value=VoiceDownload(ready=ready())} }
    val state=MutableStateFlow(VoiceDownload(ready=ready()))
    private var job: Job?=null
    fun ready()=selected.assets.all {File(directory,it.name).length()==it.size} &&
        runCatching {File(directory,".verified").readText()==selected.manifest}.getOrDefault(false)
    fun cancel() {job?.cancel()}
    fun download() {
        if(state.value.busy || job?.isCompleted==false || ready()) return
        val spec=selected;val directory=directory
        state.value=VoiceDownload(busy=true)
        job=scope.launch(Dispatchers.IO) {
            directory.mkdirs()
            try {
                val missing=spec.assets.sumOf {asset ->if(File(directory,asset.name).length()==asset.size) 0L else asset.size-File(directory,"${asset.name}.part").length().coerceIn(0,asset.size)}
                if(StatFs(directory.path).availableBytes<missing+100_000_000L)
                    throw UserError("Для установки распознавания нужно ещё ${((missing+100_000_000L)/1_000_000)} МБ свободного места.")
                for((index,asset) in spec.assets.withIndex()) {
                    val file=File(directory,asset.name)
                    if(file.length()!=asset.size || hash(file)!=asset.sha256) {
                        file.delete()
                        val part=File(directory,"${asset.name}.part")
                        if(part.length()>asset.size) part.delete()
                        if(part.length()!=asset.size) transfer(asset,part,index,spec)
                        state.value=state.value.copy(phase="Проверяю модель")
                        if(hash(part)!=asset.sha256) {part.delete();throw UserError("Файл модели повреждён. Повторите загрузку.")}
                        if(!part.renameTo(file)) throw IOException("Cannot install ASR asset")
                    }
                }
                File(directory,".verified").writeText(spec.manifest)
                directory.parentFile?.listFiles()?.filter {it.isDirectory && it.name.startsWith("vosk-model-small-")}
                    ?.forEach {it.deleteRecursively()}
                state.value=VoiceDownload(ready=true)
            } catch(e: CancellationException) {state.value=VoiceDownload(ready=ready());throw e}
            catch(e: Exception) {state.value=VoiceDownload(ready=ready(),error=downloadError(e))}
        }
    }
    private suspend fun hash(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use {input ->val b=ByteArray(65536)
            while(true) {currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0) break;digest.update(b,0,n)} }
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    private suspend fun transfer(asset: AsrAsset,file: File,index: Int,spec: AsrPack)=coroutineScope {
        val offset=file.length()
        val before=spec.assets.take(index).sumOf {it.size}
        state.value=VoiceDownload(true,(before+offset).toFloat()/spec.size,phase="${spec.title} · ${index+1}/${spec.assets.size}")
        val call=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(45,TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url(asset.url).header("Accept-Encoding","identity").apply {if(offset>0) header("Range","bytes=$offset-")}.build())
        val watcher=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
        try {call.execute().use {r ->
            if(!r.isSuccessful) throw UserError("Источник модели вернул HTTP ${r.code}.")
            val append=offset>0 && r.code==206
            if(append && !r.header("Content-Range").orEmpty().startsWith("bytes $offset-")) {file.delete();throw IOException("Invalid range")}
            var count=if(append) offset else 0L
            (r.body ?: throw IOException("Empty response")).byteStream().use {input ->FileOutputStream(file,append).use {out ->
                val b=ByteArray(65536);while(true) {ensureActive();val n=input.read(b);if(n<0) break;count+=n
                    if(count>asset.size) throw IOException("Invalid ASR size")
                    out.write(b,0,n);state.value=state.value.copy(progress=(before+count).toFloat()/spec.size)}
            }}
            if(count!=asset.size) throw IOException("Incomplete ASR file")
        }} finally {watcher.cancel()}
    }
    companion object {
        private const val HF="https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
        val WHISPER_ASSETS=listOf(
            AsrAsset("small-encoder.int8.onnx",112442483,"4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9","$HF/small-encoder.int8.onnx"),
            AsrAsset("small-decoder.int8.onnx",262226114,"acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee","$HF/small-decoder.int8.onnx"),
            AsrAsset("small-tokens.txt",816730,"b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126","$HF/small-tokens.txt"),
            AsrAsset("silero_vad.onnx",643854,"9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6","https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"))
        private const val GIGA="https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19/resolve/f5555086f28ef11d600e30d76b61d75fd9685196"
        val GIGA_ASSETS=listOf(
            AsrAsset("model.int8.onnx",236457977,"d0ce4aef25f58d495781ee8f05320d9e51b821f47804e07aa6549b53a72f67e8","$GIGA/model.int8.onnx"),
            AsrAsset("tokens.txt",196,"17cc514451bcceac9c280068c71502f8448f99e9fb1456b8d0761651fd0392f2","$GIGA/tokens.txt"),WHISPER_ASSETS.last())
        private const val SENSE="https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/355f4d4884d8afd08aef04b9007a8556d7b463b2"
        val SENSE_ASSETS=listOf(
            AsrAsset("sense-voice.int8.onnx",237115547,"12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d","$SENSE/model.int8.onnx"),
            AsrAsset("tokens.txt",315894,"f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc","$SENSE/tokens.txt"),WHISPER_ASSETS.last())
        fun pack(language: String)=when(language) {
            "ru" -> AsrPack("giga-am-v2-int8-v1","GigaAM v2",GIGA_ASSETS)
            "zh" -> AsrPack("sense-voice-int8-v1","SenseVoice",SENSE_ASSETS)
            else -> AsrPack("whisper-small-int8-v1","Whisper Small",WHISPER_ASSETS)
        }
    }
}

internal fun downloadError(e: Exception): String=when(e) {
    is UserError -> e.message.orEmpty()
    is UnknownHostException -> "Сервер модели не найден (DNS)."
    is SocketTimeoutException -> "Сервер модели не ответил вовремя."
    is SSLException -> "Не удалось установить защищённое соединение. Проверьте дату телефона и сеть."
    is java.io.FileNotFoundException -> "Не удалось открыть файл во внутренней памяти."
    is IOException -> "Передача файла прервалась. Повторите загрузку — сохранённая часть будет использована."
    else -> "Установка модели не завершена (${e.javaClass.simpleName}). Повторите загрузку."
}
