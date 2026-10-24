package dev.luma.agent.core

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import android.net.Uri
import android.os.StatFs
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class VoiceDownload(val busy: Boolean=false,val progress: Float=0f,val ready: Boolean=false,val error: String?=null,val phase: String="")
class VoiceModel(private val context: Context,private val scope: CoroutineScope) {
    val directory=File(context.noBackupFilesDir,"voice/vosk-model-small-ru-0.22")
    val state=MutableStateFlow(VoiceDownload(ready=ready()))
    private var job: Job?=null
    fun ready()=File(directory,"am/final.mdl").isFile && File(directory,"conf/model.conf").isFile
    fun cancel() {job?.cancel()}
    fun importZip(uri: Uri) {
        if(state.value.busy) return
        download(uri)
    }
    fun download() = download(null)
    private fun download(importUri: Uri?) {
        if(state.value.busy || (importUri==null && ready())) return
        state.value=VoiceDownload(busy=true)
        job=scope.launch(Dispatchers.IO) {
            val parent=directory.parentFile!!;parent.mkdirs()
            val archive=File(parent,"model.download");val stage=File(parent,"extracting")
            try {
                if(StatFs(parent.path).availableBytes<180_000_000) throw UserError("Для загрузки и распаковки нужно хотя бы 180 МБ свободного места во внутренней памяти.")
                if(importUri!=null) {
                    state.value=VoiceDownload(true,phase="Импортирую ZIP")
                    context.contentResolver.openInputStream(importUri)?.use {input ->archive.outputStream().use {out ->
                        var copied=0L;val buffer=ByteArray(32768)
                        while(true) {ensureActive();val n=input.read(buffer);if(n<0) break;copied+=n
                            if(copied>SIZE) throw UserError("Выберите vosk-model-small-ru-0.22.zip размером 46 МБ.")
                            out.write(buffer,0,n);state.value=state.value.copy(progress=copied.toFloat()/SIZE)
                        }
                    }} ?: throw UserError("Не удалось прочитать ZIP. Выберите файл ещё раз.")
                } else {
                    if(archive.length()>SIZE) archive.delete()
                    var failure: Exception?=null
                    if(archive.length()!=SIZE) for((index,source) in SOURCES.withIndex()) {
                        try {transfer(source,index,archive);failure=null;break}
                        catch(e: CancellationException) {throw e}
                        catch(e: Exception) {ensureActive();failure=e}
                    }
                    failure?.let {throw UserError("${downloadError(it)} Попробуйте снова: загрузка продолжится. Можно установить ZIP вручную.")}
                }
                state.value=VoiceDownload(true,1f,phase="Проверяю и распаковываю")
                val digest=MessageDigest.getInstance("SHA-256")
                archive.inputStream().use {input ->val buf=ByteArray(32768);while(true) {ensureActive();val n=input.read(buf);if(n<0) break;digest.update(buf,0,n)}}
                if(digest.digest().joinToString("") {"%02x".format(it)}!=SHA256) {archive.delete();throw UserError("ZIP повреждён или относится к другой модели. Повторите загрузку или выберите vosk-model-small-ru-0.22.zip.")}
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
            catch(e: Exception) {state.value=VoiceDownload(ready=ready(),error=downloadError(e))}
            finally {if(ready()) archive.delete();stage.deleteRecursively()}
        }
    }
    private suspend fun transfer(source: String,index: Int,archive: File)=coroutineScope {
        val offset=archive.length()
        state.value=VoiceDownload(true,offset.toFloat()/SIZE,phase="${if(offset>0) "Продолжаю" else "Скачиваю"} · источник ${index+1}/${SOURCES.size}")
        val call=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).callTimeout(20,TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url(source).header("Accept-Encoding","identity").apply {if(offset>0) header("Range","bytes=$offset-")}.build())
        val cancelWatch=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
        try {call.execute().use {response ->
            if(!response.isSuccessful) throw UserError("Источник модели вернул HTTP ${response.code}.")
            val append=offset>0 && response.code==206
            if(append && !response.header("Content-Range").orEmpty().startsWith("bytes $offset-")) {archive.delete();throw UserError("Неверный диапазон загрузки. Повторите.")}
            var count=if(append) offset else 0L
            val body=response.body ?: throw UserError("Источник вернул пустой файл модели.")
            body.byteStream().use {input ->java.io.FileOutputStream(archive,append).use {out ->
                val buffer=ByteArray(32768)
                while(true) {ensureActive();val n=input.read(buffer);if(n<0) break;count+=n
                    if(count>SIZE) throw UserError("Источник вернул неверный размер модели.")
                    out.write(buffer,0,n);state.value=state.value.copy(progress=count.toFloat()/SIZE)
                }
            }}
            if(count!=SIZE) throw IOException("Incomplete download")
        }} finally {cancelWatch.cancel()}
    }
    companion object {
        const val SIZE=46_236_750L
        val SOURCES by lazy {listOf("https://huggingface.co/rhasspy/vosk-models/resolve/e7ac2109d134b5f2404ba95389b2fb51916d4cab/ru/vosk-model-small-ru-0.22.zip",URL,"https://github.com/BartekReterski/VoskModels/releases/download/v1/vosk-model-small-ru-0.22.zip")}
        const val URL="https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        const val SHA256="961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
    }
}

internal fun downloadError(e: Exception): String=when(e) {
    is UserError -> e.message.orEmpty()
    is UnknownHostException -> "Сервер модели не найден (DNS)."
    is SocketTimeoutException -> "Сервер модели не ответил вовремя."
    is SSLException -> "Не удалось установить защищённое соединение. Проверьте дату телефона и сеть."
    is java.io.FileNotFoundException -> "Не удалось открыть файл во внутренней памяти."
    is IOException -> "Передача файла прервалась. Повторите загрузку — сохранённая часть будет использована."
    else -> "Установка модели не завершена (${e.javaClass.simpleName}). Можно установить ZIP вручную."
}
