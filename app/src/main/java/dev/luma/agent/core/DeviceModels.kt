package dev.luma.agent.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DeviceModel(val id: String,val name: String,val bytes: Long,val imported: Boolean=false)
class DeviceModels(private val context: Context,private val downloads: HubDownloads,scope: CoroutineScope) {
    private val folder=File(context.getExternalFilesDir(null) ?: context.filesDir,"models").also {it.mkdirs()}
    private val metadata=AtomicFile(File(context.noBackupFilesDir,"device-models.json"))
    private val mutex=Mutex()
    private var imported=read()
    val state=MutableStateFlow<List<DeviceModel>>(emptyList())
    val importing=MutableStateFlow(false)
    init {scope.launch {downloads.state.collect {publish()}}}
    private fun read()=runCatching {
        val rows=metadata.openRead().use {JSONArray(readLimited(it,128*1024).toString(Charsets.UTF_8))}
        (0 until minOf(24,rows.length())).mapNotNull {i -> rows.optJSONObject(i)?.let {j ->
            val id=j.optString("id");if(!id.matches(Regex("file:[a-f0-9-]{36}"))) null else DeviceModel(id,j.optString("name").take(240),j.optLong("bytes"),true)
        }}
    }.getOrDefault(emptyList())
    private fun publish() {
        state.value=imported+downloads.state.value.filter {it.phase==HubPhase.READY}.map {DeviceModel("hf:${it.id}",it.file.name,it.file.bytes)}
    }
    private fun localFile(model: DeviceModel)=File(folder,model.id.removePrefix("file:")+".gguf")
    suspend fun file(id: String): File {
        if(id.startsWith("hf:")) return downloads.fileForShare(id.removePrefix("hf:").toLongOrNull() ?: throw UserError("Модель не найдена."))
        return mutex.withLock {
            val item=imported.firstOrNull {it.id==id} ?: throw UserError("Модель удалена. Выберите другую модель на телефоне.")
            localFile(item).also {if(!validGguf(it,item.bytes)) throw UserError("Файл модели повреждён или удалён.")}
        }
    }
    suspend fun import(uri: Uri)=withContext(Dispatchers.IO) {mutex.withLock {
        if(imported.size>=24) throw UserError("В библиотеке уже 24 импортированных модели.")
        importing.value=true
        val id="file:"+UUID.randomUUID();var target: File?=null
        try {
            var name="Модель.gguf";var expected=-1L
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use {c ->if(c.moveToFirst()) {
                val n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0) name=c.getString(n).orEmpty().take(240)
                val s=c.getColumnIndex(OpenableColumns.SIZE);if(s>=0 && !c.isNull(s)) expected=c.getLong(s)
            }}
            if(!name.endsWith(".gguf",true)) throw UserError("Выберите файл модели с расширением .gguf.")
            val available=StatFs(folder.path).availableBytes-128L*1024*1024
            if(expected>available || available<16*1024*1024) throw UserError("Недостаточно места для копии модели.")
            val item=DeviceModel(id,name,0,true);val file=localFile(item);target=file
            var copied=0L
            context.contentResolver.openInputStream(uri)?.use {input ->file.outputStream().use {output ->
                val buf=ByteArray(65536)
                while(true) {ensureActive();val n=input.read(buf);if(n<0) break;copied+=n
                    if(copied>available || copied>32L*1024*1024*1024) throw UserError("Недостаточно места для модели или файл превышает 32 ГБ.")
                    output.write(buf,0,n)
                }
            }} ?: throw UserError("Не удалось прочитать файл модели.")
            if(!validGguf(file,copied)) throw UserError("Файл не является корректным GGUF.")
            val next=imported+item.copy(bytes=copied);persist(next);imported=next;publish();target=null
        } finally {target?.delete();importing.value=false}
    }}
    suspend fun remove(id: String)=withContext(Dispatchers.IO) {mutex.withLock {
        val item=imported.firstOrNull {it.id==id} ?: throw UserError("Удалите модель Hugging Face из списка загрузок.")
        if(localFile(item).exists() && !localFile(item).delete()) throw UserError("Не удалось удалить модель.")
        val next=imported.filterNot {it.id==id};persist(next);imported=next;publish()
    }}
    private fun persist(models: List<DeviceModel>) {
        val bytes=JSONArray(models.map {JSONObject().put("id",it.id).put("name",it.name).put("bytes",it.bytes)}).toString().toByteArray()
        val out=metadata.startWrite();try {out.write(bytes);metadata.finishWrite(out)} catch(e: Exception) {metadata.failWrite(out);throw e}
    }
}
