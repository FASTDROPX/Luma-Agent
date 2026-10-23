package dev.luma.agent.core

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class VoiceDownload(val busy: Boolean=false,val progress: Float=0f,val ready: Boolean=false,val error: String?=null)
class VoiceModel(context: Context,private val scope: CoroutineScope) {
    val directory=File(context.noBackupFilesDir,"voice/vosk-model-small-ru-0.22")
    val state=MutableStateFlow(VoiceDownload(ready=ready()))
    private var job: Job?=null
    fun ready()=File(directory,"am/final.mdl").isFile && File(directory,"conf/model.conf").isFile
    fun cancel() {job?.cancel()}
    fun download() {
        if(state.value.busy || ready()) return
        state.value=VoiceDownload(busy=true)
        job=scope.launch(Dispatchers.IO) {
            val parent=directory.parentFile!!;parent.mkdirs()
            val archive=File(parent,"model.download");val stage=File(parent,"extracting")
            try {
                val call=OkHttpClient.Builder().readTimeout(15,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).build().newCall(Request.Builder().url(URL).build())
                val cancelWatch=launch {try {awaitCancellation()} finally {call.cancel()}}
                try {
                    call.execute().use {response ->
                        if(!response.isSuccessful) throw UserError("Не удалось скачать голосовую модель: HTTP ${response.code}")
                        val body=response.body ?: throw UserError("Пустой файл модели.")
                        val digest=MessageDigest.getInstance("SHA-256");var count=0L
                        body.byteStream().use {input -> archive.outputStream().use {out ->
                            val buffer=ByteArray(32768)
                            while(true) {ensureActive();val n=input.read(buffer);if(n<0) break;count+=n
                                if(count>50_000_000) throw UserError("Файл голосовой модели слишком большой.")
                                out.write(buffer,0,n);digest.update(buffer,0,n);state.value=VoiceDownload(true,(count/46_236_750f).coerceIn(0f,1f))}
                        } }
                        if(digest.digest().joinToString("") {"%02x".format(it)}!=SHA256) throw UserError("Контрольная сумма голосовой модели не совпала.")
                    }
                } finally {cancelWatch.cancel()}
                stage.deleteRecursively();stage.mkdirs()
                ZipInputStream(archive.inputStream().buffered()).use {zip ->
                    var total=0L;var entries=0
                    while(true) {ensureActive();val entry=zip.nextEntry ?: break
                        if(++entries>1000) throw UserError("Некорректный архив модели.")
                        val target=File(stage,entry.name).canonicalFile
                        if(!target.path.startsWith(stage.canonicalPath+File.separator)) throw UserError("Небезопасный путь в архиве.")
                        if(entry.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs();target.outputStream().use {out ->
                                val buf=ByteArray(32768);while(true) {ensureActive();val n=zip.read(buf);if(n<0) break;total+=n
                                    if(total>150_000_000) throw UserError("Архив модели слишком большой.");out.write(buf,0,n)}
                            }
                        }
                    }
                }
                val unpacked=File(stage,directory.name)
                if(!File(unpacked,"am/final.mdl").isFile || !File(unpacked,"conf/model.conf").isFile) throw UserError("В архиве нет голосовой модели.")
                directory.deleteRecursively()
                if(!unpacked.renameTo(directory)) throw UserError("Не удалось сохранить голосовую модель.")
                state.value=VoiceDownload(ready=true)
            } catch(e: CancellationException) {state.value=VoiceDownload(ready=ready());throw e}
            catch(e: Exception) {state.value=VoiceDownload(ready=ready(),error="Загрузка не удалась. Проверьте сеть и свободное место.")}
            finally {archive.delete();stage.deleteRecursively()}
        }
    }
    companion object {
        const val URL="https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        const val SHA256="961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
    }
}
