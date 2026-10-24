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
class VoiceModel(private val context: Context,private val scope: CoroutineScope,language: String="ru") {
    private var selected=VoskPacks.get(language)
    val spec get()=selected
    fun select(language: String) {if(state.value.busy) return;selected=VoskPacks.get(language);state.value=VoiceDownload(ready=ready())}
    val directory get()=File(context.noBackupFilesDir,"voice/${selected.name}")
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
        if(state.value.busy || job?.isCompleted==false || (importUri==null && ready())) return
        val spec=selected;val directory=directory
        state.value=VoiceDownload(busy=true)
        job=scope.launch(Dispatchers.IO) {
            val parent=directory.parentFile!!;parent.mkdirs()
            val archive=File(parent,"${spec.name}.download");val stage=File(parent,"${spec.name}.extracting")
            try {
                if(StatFs(parent.path).availableBytes<180_000_000) throw UserError("Для загрузки и распаковки нужно хотя бы 180 МБ свободного места во внутренней памяти.")
                if(importUri!=null) {
                    state.value=VoiceDownload(true,phase="Импортирую ZIP")
                    context.contentResolver.openInputStream(importUri)?.use {input ->archive.outputStream().use {out ->
                        var copied=0L;val buffer=ByteArray(32768)
                        while(true) {ensureActive();val n=input.read(buffer);if(n<0) break;copied+=n
                            if(copied>spec.size) throw UserError("Выберите ${spec.name}.zip.")
                            out.write(buffer,0,n);state.value=state.value.copy(progress=copied.toFloat()/spec.size)
                        }
                    }} ?: throw UserError("Не удалось прочитать ZIP. Выберите файл ещё раз.")
                } else {
                    if(archive.length()>spec.size) archive.delete()
                    var failure: Exception?=null
                    if(archive.length()!=spec.size) for((index,source) in spec.sources.withIndex()) {
                        try {transfer(source,index,archive,spec);failure=null;break}
                        catch(e: CancellationException) {throw e}
                        catch(e: Exception) {ensureActive();failure=e}
                    }
                    failure?.let {throw UserError("${downloadError(it)} Попробуйте снова: загрузка продолжится. Можно установить ZIP вручную.")}
                }
                state.value=VoiceDownload(true,1f,phase="Проверяю и распаковываю")
                val digest=MessageDigest.getInstance("SHA-256")
                archive.inputStream().use {input ->val buf=ByteArray(32768);while(true) {ensureActive();val n=input.read(buf);if(n<0) break;digest.update(buf,0,n)}}
                if(digest.digest().joinToString("") {"%02x".format(it)}!=spec.sha256) {archive.delete();throw UserError("ZIP повреждён или относится к другой модели. Повторите загрузку или выберите ${spec.name}.zip.")}
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
    private suspend fun transfer(source: String,index: Int,archive: File,spec: VoskPack)=coroutineScope {
        val offset=archive.length()
        state.value=VoiceDownload(true,offset.toFloat()/spec.size,phase="${if(offset>0) "Продолжаю" else "Скачиваю"} · источник ${index+1}/${spec.sources.size}")
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
                    if(count>spec.size) throw UserError("Источник вернул неверный размер модели.")
                    out.write(buffer,0,n);state.value=state.value.copy(progress=count.toFloat()/spec.size)
                }
            }}
            if(count!=spec.size) throw IOException("Incomplete download")
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


data class VoskPack(val language: String,val name: String,val size: Long,val sha256: String,val mirror: String="") {
    val sources get()=if(language=="ru") VoiceModel.SOURCES else listOfNotNull(mirror.takeIf {it.isNotEmpty()},"https://alphacephei.com/vosk/models/$name.zip")
}
object VoskPacks {
    private const val MIRROR="https://huggingface.co/rhasspy/vosk-models/resolve/e7ac2109d134b5f2404ba95389b2fb51916d4cab"
    val all=listOf(
        VoskPack("ru","vosk-model-small-ru-0.22",VoiceModel.SIZE,VoiceModel.SHA256),
        VoskPack("en","vosk-model-small-en-us-0.15",41205931L,"30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498","$MIRROR/en/vosk-model-small-en-us-0.15.zip"),
        VoskPack("zh","vosk-model-small-cn-0.22",43898754L,"3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"),
        VoskPack("de","vosk-model-small-de-0.15",46499967L,"b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43","$MIRROR/de/vosk-model-small-de-0.15.zip"),
        VoskPack("fr","vosk-model-small-fr-0.22",42233323L,"cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad","$MIRROR/fr/vosk-model-small-fr-0.22.zip"))
    fun get(language: String)=all.firstOrNull {it.language==language} ?: all.first()
}
