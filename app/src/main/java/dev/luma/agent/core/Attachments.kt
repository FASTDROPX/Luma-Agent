package dev.luma.agent.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class Attachment(val id: String,val name: String,val mime: String,val size: Long) {
    fun json()=JSONObject().put("id",id).put("name",name).put("mime",mime).put("size",size)
    companion object {
        fun encode(items: List<Attachment>)=JSONArray(items.map {it.json()}).toString()
        fun decode(raw: String): List<Attachment> = runCatching {
            val a=JSONArray(raw)
            (0 until minOf(a.length(),8)).map {i -> val j=a.getJSONObject(i);Attachment(j.getString("id"),j.getString("name"),j.getString("mime"),j.getLong("size"))}
                .filter {it.id.matches(Regex("[a-f0-9-]{36}"))}
        }.getOrDefault(emptyList())
    }
}
data class AttachmentData(val name: String,val mime: String,val data: String="",val text: String?=null)

/** Only user-picked content is copied; no broad storage permission or external path is retained. */
class AttachmentStore(private val context: Context) {
    private val root=File(context.noBackupFilesDir,"chat-attachments").apply {mkdirs()}
    fun file(item: Attachment): File {
        if(!item.id.matches(Regex("[a-f0-9-]{36}"))) throw UserError("Некорректное вложение.")
        return File(root,item.id)
    }
    suspend fun importFile(uri: Uri): Attachment=withContext(Dispatchers.IO) {
        if(uri.scheme!="content") throw UserError("Выберите файл через системный проводник.")
        var name="Вложение";var declared=0L
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use {c ->
            if(c.moveToFirst()) {
                val n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0) name=c.getString(n).orEmpty().take(160)
                val s=c.getColumnIndex(OpenableColumns.SIZE);if(s>=0 && !c.isNull(s)) declared=c.getLong(s)
            }
        }
        if(declared>8_000_000) throw UserError("Файл больше 8 МБ. Выберите файл поменьше.")
        val bytes=context.contentResolver.openInputStream(uri)?.use {readLimited(it,8_000_000)} ?: throw UserError("Не удалось открыть файл.")
        store(name,context.contentResolver.getType(uri).orEmpty(),bytes)
    }
    internal fun store(rawName: String,rawMime: String,bytes: ByteArray): Attachment {
        if(bytes.isEmpty() || bytes.size>8_000_000) throw UserError("Нужен непустой файл размером до 8 МБ.")
        val name=rawName.filter {it.code>=32 && it!='/' && it!='\\'}.take(160).ifBlank {"Вложение"}
        var mime=rawMime.substringBefore(';').lowercase()
        var data=bytes
        val ext=name.substringAfterLast('.').lowercase()
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth>0 && bounds.outHeight>0) {
            if(bounds.outWidth.toLong()*bounds.outHeight>40_000_000) throw UserError("Слишком большое разрешение картинки.")
            val options=BitmapFactory.Options().apply {inSampleSize=1;while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>1600) inSampleSize*=2}
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: throw UserError("Не удалось прочитать картинку.")
            val orientation=runCatching {ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix=Matrix().apply {when(orientation) {
                ExifInterface.ORIENTATION_ROTATE_90->postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180->postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270->postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL->postScale(-1f,1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL->postScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE->{postRotate(90f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_TRANSVERSE->{postRotate(270f);postScale(-1f,1f)}
            }}
            val rotated=if(matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
            val opaque=Bitmap.createBitmap(rotated.width,rotated.height,Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(opaque).apply {drawColor(android.graphics.Color.WHITE);drawBitmap(rotated,0f,0f,null)}
            data=ByteArrayOutputStream().use {out->opaque.compress(Bitmap.CompressFormat.JPEG,88,out);out.toByteArray()}
            opaque.recycle()
            if(rotated!==bitmap) rotated.recycle();bitmap.recycle();mime="image/jpeg"
        } else if(bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) mime="application/pdf"
        else if(mime.startsWith("text/") || mime in setOf("application/json","application/xml","application/javascript") || ext in setOf("txt","md","csv","json","xml","yaml","yml","log","kt","java","py","js","ts","html","css","sql","rs","c","cpp","h")) {
            decodeText(bytes);mime="text/plain"
        } else if(mime !in setOf("audio/mpeg","audio/mp3","audio/wav","audio/x-wav","audio/ogg","audio/flac","audio/aac","audio/mp4"))
            throw UserError("Этот формат пока не поддерживается. Можно прикрепить картинки, PDF, текст, код или аудио для Gemini.")
        mime=when(mime) {"audio/x-wav"->"audio/wav";"audio/mp3"->"audio/mpeg";else->mime}
        val item=Attachment(UUID.randomUUID().toString(),name,mime,data.size.toLong())
        val target=file(item);val temp=File(root,item.id+".tmp")
        try {temp.outputStream().use {it.write(data)};if(!temp.renameTo(target)) throw UserError("Не удалось сохранить вложение.")}
        finally {temp.delete()}
        return item
    }
    internal fun payload(item: Attachment): AttachmentData {
        val bytes=file(item).inputStream().use {readLimited(it,8_000_000)}
        return if(item.mime=="text/plain") AttachmentData(item.name,item.mime,text=decodeText(bytes))
        else AttachmentData(item.name,item.mime,Base64.encodeToString(bytes,Base64.NO_WRAP))
    }
    private fun decodeText(bytes: ByteArray): String {
        if(bytes.size>256_000) throw UserError("Текстовый файл должен быть не больше 256 КБ.")
        val text=runCatching {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}
            .getOrElse {throw UserError("Нужен текстовый файл в кодировке UTF-8.")}
        if(text.length>64_000 || '\u0000' in text) throw UserError("Нужен текстовый файл до 64 000 символов.")
        return text
    }
}
