package dev.luma.agent.core

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class HubPhase { QUEUED, DOWNLOADING, PAUSED, READY, ERROR }
data class HubDownload(val id: Long, val file: HubFile, val wifiOnly: Boolean, val phase: HubPhase = HubPhase.QUEUED, val received: Long = 0, val message: String = "В очереди")

/** Android owns transfers, retries, and notifications even after this process exits. */
class HubDownloads(private val context: Context) {
    private val manager get() = context.getSystemService(DownloadManager::class.java)
    private val metadata = AtomicFile(File(context.noBackupFilesDir,"hf-downloads.json"))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()
    private fun directory(): File {
        val base=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw UserError("Хранилище телефона недоступно.")
        return File(base,"huggingface").also { if(!it.exists() && !it.mkdirs()) throw UserError("Не удалось создать папку моделей.") }
    }
    private fun destination(file: HubFile) = File(directory(),file.storageName)
    suspend fun enqueue(file: HubFile,wifiOnly: Boolean): Long = withContext(Dispatchers.IO) { mutex.withLock {
        validateHubFile(file)
        mutableState.value.firstOrNull { it.file.identity == file.identity }?.let { throw UserError("Этот файл уже есть в загрузках.") }
        if(mutableState.value.size >= 24) throw UserError("В списке уже 24 файла. Удалите ненужные загрузки.")
        if(mutableState.value.count { it.phase in listOf(HubPhase.QUEUED,HubPhase.DOWNLOADING,HubPhase.PAUSED) } >= 2) throw UserError("Уже загружаются две модели. Дождитесь завершения или отмените одну.")
        val folder=directory()
        val reserved=mutableState.value.filter { it.phase in listOf(HubPhase.QUEUED,HubPhase.DOWNLOADING,HubPhase.PAUSED) }.sumOf { (it.file.bytes-it.received).coerceAtLeast(0) }
        if(StatFs(folder.absolutePath).availableBytes < file.bytes + reserved + 64L*1024*1024) throw UserError("Недостаточно места с учётом текущих загрузок. Нужно ${sizeLabel(file.bytes+reserved)} и небольшой запас.")
        val target=destination(file)
        if(target.exists() && !target.delete()) throw UserError("Не удалось подготовить файл для загрузки.")
        val request=DownloadManager.Request(Uri.parse(file.url().toString())).setTitle(file.name).setDescription("${file.repo} · Luma").setMimeType("application/octet-stream").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverRoaming(false).setDestinationUri(Uri.fromFile(target))
        if(wifiOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        request.setAllowedOverMetered(!wifiOnly)
        val id=try { manager.enqueue(request) } catch(e:Exception) { throw UserError("Android не смог начать загрузку. Проверьте, включён ли системный «Диспетчер загрузок».") }
        val next=mutableState.value + HubDownload(id,file,wifiOnly)
        try { persist(next) } catch(e:Exception) { manager.remove(id); throw UserError("Не удалось сохранить загрузку. Попробуйте ещё раз.") }
        mutableState.value=next; id
    } }
    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        mutableState.value=mutableState.value.map { entry ->
            manager.query(DownloadManager.Query().setFilterById(entry.id))?.use { cursor ->
                if(!cursor.moveToFirst()) return@use if(validGguf(destination(entry.file),entry.file.bytes)) entry.copy(phase=HubPhase.READY,received=entry.file.bytes,message="Скачано") else entry.copy(phase=HubPhase.ERROR,message="Загрузка удалена системой. Повторите.")
                val status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val received=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                val reason=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                when(status) {
                    DownloadManager.STATUS_SUCCESSFUL -> if(validGguf(destination(entry.file),entry.file.bytes)) entry.copy(phase=HubPhase.READY,received=entry.file.bytes,message="Скачано · GGUF") else entry.copy(phase=HubPhase.ERROR,message="Файл повреждён или не является GGUF. Повторите загрузку.")
                    DownloadManager.STATUS_RUNNING -> entry.copy(phase=HubPhase.DOWNLOADING,received=received,message="Загружается")
                    DownloadManager.STATUS_PAUSED -> entry.copy(phase=HubPhase.PAUSED,received=received,message=if(reason==DownloadManager.PAUSED_QUEUED_FOR_WIFI || entry.wifiOnly) "Ожидание Wi-Fi" else "Ожидание сети / повтор Android")
                    DownloadManager.STATUS_FAILED -> entry.copy(phase=HubPhase.ERROR,received=received,message=when(reason){DownloadManager.ERROR_INSUFFICIENT_SPACE->"Не хватает места на телефоне";401,403->"Hugging Face требует доступ к модели";404->"Файл больше не доступен на Hugging Face";else->"Загрузка не удалась (код $reason)"})
                    else -> entry.copy(phase=HubPhase.QUEUED,received=received,message=if(entry.wifiOnly) "В очереди · только Wi-Fi" else "В очереди")
                }
            } ?: entry.copy(phase=HubPhase.ERROR,message="Диспетчер загрузок недоступен")
        }
    } }
    suspend fun remove(id: Long) = withContext(Dispatchers.IO) { mutex.withLock {
        val old=mutableState.value.firstOrNull { it.id==id } ?: return@withLock
        manager.remove(id)
        val target=destination(old.file)
        if(target.exists() && !target.delete()) throw UserError("Не удалось удалить файл. Повторите после закрытия модели в других приложениях.")
        val next=mutableState.value.filterNot { it.id==id }; persist(next); mutableState.value=next
    } }
    suspend fun retry(id: Long) { val old=mutableState.value.firstOrNull { it.id==id } ?: return; remove(id); enqueue(old.file,old.wifiOnly) }
    suspend fun fileForShare(id: Long): File = withContext(Dispatchers.IO) {
        val item=mutableState.value.firstOrNull { it.id==id && it.phase==HubPhase.READY } ?: throw UserError("Сначала дождитесь окончания загрузки.")
        destination(item.file).also { if(!validGguf(it,item.file.bytes)) throw UserError("Файл недоступен или повреждён. Повторите загрузку.") }
    }
    private fun load(): List<HubDownload> = runCatching {
        val array=metadata.openRead().use { JSONArray(readLimited(it,256*1024).toString(Charsets.UTF_8)) }
        (0 until array.length().coerceAtMost(24)).mapNotNull { i -> runCatching { val o=array.getJSONObject(i); val id=o.getLong("id"); require(id>=0); HubDownload(id,HubFile.parse(o.getJSONObject("file")),o.optBoolean("wifiOnly",true)) }.getOrNull() }.distinctBy { it.file.identity }
    }.getOrDefault(emptyList())
    private fun persist(entries: List<HubDownload>) {
        val bytes=JSONArray(entries.map { JSONObject().put("id",it.id).put("file",it.file.json()).put("wifiOnly",it.wifiOnly) }).toString().toByteArray()
        val stream=metadata.startWrite()
        try { stream.write(bytes); metadata.finishWrite(stream) } catch(e:Exception) { metadata.failWrite(stream);throw e }
    }
}
