package dev.luma.agent.core

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class SpeechLanguage(val code: String,val name: String,val sample: String,val listening: String,val aliases: String)
object SpeechLanguages {
    val all=listOf(
        SpeechLanguage("ru","Русский","Привет! Я Люма. Давай поговорим.","Я слушаю","люма, эй люма, лума, эй лума, luma"),
        SpeechLanguage("en","English","Hi! I'm Luma. Let's talk.","I'm listening","luma, hey luma, looma, hey looma"),
        SpeechLanguage("zh","中文","你好，我是露玛。我们聊聊吧。","我在听","露玛, 卢马, 你好露玛, luma"),
        SpeechLanguage("de","Deutsch","Hallo! Ich bin Luma. Lass uns reden.","Ich höre zu","luma, hey luma, hallo luma"),
        SpeechLanguage("fr","Français","Bonjour ! Je suis Luma. Parlons ensemble.","Je vous écoute","luma, louma, salut luma"))
    fun get(code: String)=all.firstOrNull {it.code==code} ?: all.first()
}
data class SpeechPack(val id: String,val language: String,val gender: String,val name: String,val size: Long,val sha256: String) {
    val root get()="vits-piper-$id"
    val url get()="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$root.tar.bz2"
}
data class SpeechInstall(val id: String="",val busy: Boolean=false,val progress: Float=0f,val error: String?=null,val revision: Int=0)

/** Voice weights are data, downloaded explicitly. Only pinned, hash-checked packs can be installed. */
class SpeechPacks(private val context: Context,private val scope: CoroutineScope) {
    val state=MutableStateFlow(SpeechInstall())
    private val root=File(context.noBackupFilesDir,"speech-packs").apply {mkdirs()}
    private var job: Job?=null
    fun directory(pack: SpeechPack)=File(root,pack.root)
    fun ready(pack: SpeechPack)=File(directory(pack),".verified").readTextOrEmpty()==pack.sha256
    fun cancel() {job?.cancel()}
    fun download(pack: SpeechPack) {
        if(state.value.busy || job?.isCompleted==false || ready(pack)) return
        state.value=SpeechInstall(pack.id,true,revision=state.value.revision)
        job=scope.launch(Dispatchers.IO) {
            val archive=File(root,pack.id+".part");val stage=File(root,pack.id+".stage")
            try {
                if(StatFs(root.path).availableBytes<300_000_000) throw UserError("Для установки голоса нужно хотя бы 300 МБ свободного места.")
                if(archive.length()>pack.size) archive.delete()
                if(archive.length()!=pack.size) transfer(pack,archive)
                state.value=state.value.copy(progress=1f)
                val digest=MessageDigest.getInstance("SHA-256")
                archive.inputStream().use {input->val b=ByteArray(32768);while(true) {ensureActive();val n=input.read(b);if(n<0) break;digest.update(b,0,n)}}
                if(digest.digest().joinToString("") {"%02x".format(it)}!=pack.sha256) {archive.delete();throw UserError("Файл голоса повреждён. Повторите загрузку.")}
                stage.deleteRecursively();stage.mkdirs()
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use {tar ->
                    var bytes=0L;var entries=0
                    while(true) {
                        ensureActive();val entry=tar.nextEntry ?: break
                        if(++entries>15000 || (!entry.isFile && !entry.isDirectory)) throw UserError("Недопустимый архив голоса.")
                        val dest=File(stage,entry.name).canonicalFile
                        if(!dest.path.startsWith(stage.canonicalPath+File.separator)) throw UserError("Недопустимый путь в архиве голоса.")
                        if(entry.isDirectory) dest.mkdirs() else {
                            dest.parentFile?.mkdirs();dest.outputStream().use {out->val buffer=ByteArray(32768)
                                while(true) {ensureActive();val n=tar.read(buffer);if(n<0) break;bytes+=n
                                    if(bytes>250_000_000) throw UserError("Архив голоса слишком большой.");out.write(buffer,0,n)}
                            }
                        }
                    }
                }
                val folder=File(stage,pack.root)
                if(folder.listFiles().orEmpty().none {it.extension=="onnx"} || !File(folder,"tokens.txt").isFile) throw UserError("В архиве нет модели озвучки.")
                File(folder,".verified").writeText(pack.sha256)
                directory(pack).deleteRecursively()
                if(!folder.renameTo(directory(pack))) throw UserError("Не удалось сохранить голос.")
                archive.delete();state.value=SpeechInstall(revision=state.value.revision+1)
            } catch(e: CancellationException) {state.value=SpeechInstall(revision=state.value.revision);throw e}
            catch(e: Exception) {state.value=SpeechInstall(pack.id,error=downloadError(e),revision=state.value.revision)}
            finally {stage.deleteRecursively()}
        }
    }
    private suspend fun transfer(pack: SpeechPack,file: File)=coroutineScope {
        val offset=file.length()
        val call=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).callTimeout(20,TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url(pack.url).header("Accept-Encoding","identity").apply {if(offset>0) header("Range","bytes=$offset-")}.build())
        val watcher=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
        try {call.execute().use {r ->
            if(!r.isSuccessful) throw UserError("Сервер голосов вернул HTTP ${r.code}.")
            val append=offset>0 && r.code==206
            if(append && !r.header("Content-Range").orEmpty().startsWith("bytes $offset-")) {file.delete();throw UserError("Неверный диапазон загрузки голоса.")}
            var count=if(append) offset else 0L
            (r.body ?: throw UserError("Сервер вернул пустой файл голоса.")).byteStream().use {input ->FileOutputStream(file,append).use {out ->
                val b=ByteArray(32768);while(true) {ensureActive();val n=input.read(b);if(n<0) break;count+=n
                    if(count>pack.size) throw UserError("Неверный размер файла голоса.")
                    out.write(b,0,n);state.value=state.value.copy(progress=count.toFloat()/pack.size)}
            }}
            if(count!=pack.size) throw java.io.IOException("Incomplete speech pack")
        }} finally {watcher.cancel()}
    }
    companion object {
        val all=listOf(
            SpeechPack("ru_RU-irina-medium-int8","ru","female","Ирина",21149417L,"b0000a509f7551a80742eed5c43b8eb03f469cb9f0a42f6feee96ce0da0ebab8"),
            SpeechPack("ru_RU-denis-medium-int8","ru","male","Денис",21058905L,"d710e29eb7854c42461d16d65d3cef753cc559639ca75806edc75ba71f23af66"),
            SpeechPack("en_US-amy-medium-int8","en","female","Amy",21028122L,"bd23c0aa629eb3719448582f45ede49e8fa6a679061fed5eab16a6a6fd8e7e82"),
            SpeechPack("en_US-ryan-low-int8","en","male","Ryan",21212659L,"659a39069edef4f0a64fa16119fa1e36c9b9644839a61aed73872f16c2e7ecb2"),
            SpeechPack("zh_CN-xiao_ya-medium-int8","zh","female","Xiao Ya",14016124L,"eab027e194e70289233cf12308373611fa4e2e96ef2d97354ef433ab663d831f"),
            SpeechPack("zh_CN-chaowen-medium-int8","zh","male","Chaowen",14011298L,"f5f7c8628427fbb259ea4b7ec1a9a822a0c04e3f267071f0abfa0610371d9e0c"),
            SpeechPack("de_DE-eva_k-x_low-int8","de","female","Eva",13321491L,"0501123c7e184571a40690e79943f4111a57b988632f7f60a9d2344fd061e2a2"),
            SpeechPack("de_DE-thorsten-medium-int8","de","male","Thorsten",20949833L,"07e240b7b9c1fc9211d5a69512f8cbe11b3286c2ed79c15c076ac6ed427fdf13"),
            SpeechPack("fr_FR-siwis-medium-int8","fr","female","Siwis",20914888L,"3909cff9b3cfd4820c66aa13bf554315c82e34899c161f0b446ece372bc4b5ec"),
            SpeechPack("fr_FR-tom-medium-int8","fr","male","Tom",21019617L,"b158f884eb4231b8dddd372c3dc506949630c992a8e48ca692a90323e8bb4e09"))
        fun selected(settings: VoiceSettings)=all.first {it.language==settings.outputLanguage && it.gender==settings.gender}
    }
}
private fun File.readTextOrEmpty()=runCatching {readText()}.getOrDefault("")
