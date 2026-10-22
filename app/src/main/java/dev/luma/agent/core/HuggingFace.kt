package dev.luma.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HubRepo(val id: String, val downloads: Long = 0)
data class HubFile(val repo: String, val revision: String, val path: String, val bytes: Long) {
    val name get() = path.substringAfterLast('/')
    val identity get() = "$repo@$revision/$path"
    val storageName get() = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).take(12).joinToString("") { "%02x".format(it) } + "-" + name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").takeLast(100)
    fun json() = JSONObject().put("repo",repo).put("revision",revision).put("path",path).put("bytes",bytes)
    fun url(): HttpUrl {
        validateHubFile(this)
        return "https://huggingface.co/".toHttpUrl().newBuilder().apply {
            repo.split('/').forEach { addPathSegment(it) }; addPathSegment("resolve"); addPathSegment(revision)
            path.split('/').forEach { addPathSegment(it) }; addQueryParameter("download","true")
        }.build()
    }
    companion object { fun parse(o: JSONObject) = HubFile(o.getString("repo"),o.getString("revision"),o.getString("path"),o.getLong("bytes")).also(::validateHubFile) }
}
fun validHubRepo(id: String) = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}").matches(id) && id.split('/').none { it == "." || it == ".." }
fun validateHubFile(file: HubFile) {
    if (!validHubRepo(file.repo) || !Regex("[a-f0-9]{40}").matches(file.revision) || file.path.length > 600 || file.path.contains('\\') || file.path.any { it.isISOControl() } || file.path.split('/').any { it.isBlank() || it == "." || it == ".." } || !file.path.endsWith(".gguf",true) || file.bytes !in 4..(32L*1024*1024*1024)) throw UserError("Некорректный файл GGUF или размер больше 32 ГБ.")
}
fun validGguf(file: File, expected: Long): Boolean = file.isFile && file.length() == expected && runCatching { file.inputStream().use { stream -> ByteArray(4).also { if (stream.read(it) != 4) return@use false }.contentEquals(byteArrayOf(71,71,85,70)) } }.getOrDefault(false)
fun sizeLabel(bytes: Long): String = if (bytes >= 1024L*1024*1024) String.format(java.util.Locale.ROOT,"%.2f ГБ",bytes.toDouble()/(1024*1024*1024)) else String.format(java.util.Locale.ROOT,"%.0f МБ",bytes.toDouble()/(1024*1024))

class HuggingFaceClient(private val transport: (suspend (HttpUrl) -> String)? = null) {
    private val client = OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).addInterceptor(localCleartextOnly).build()
    suspend fun search(query: String): List<HubRepo> = withContext(Dispatchers.IO) {
        val clean = query.trim().take(96).ifBlank { "Qwen3-0.6B" }
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder().addQueryParameter("search",clean).addQueryParameter("filter","gguf").addQueryParameter("sort","downloads").addQueryParameter("direction","-1").addQueryParameter("limit","20").build()
        val list = JSONArray(get(url))
        (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { o -> val id=o.optString("id"); if(validHubRepo(id) && !o.optBoolean("private") && !gated(o)) HubRepo(id,o.optLong("downloads")) else null } }.distinctBy { it.id }
    }
    suspend fun files(repo: String): List<HubFile> = withContext(Dispatchers.IO) {
        if (!validHubRepo(repo)) throw UserError("Укажите репозиторий в формате автор/модель.")
        val url = "https://huggingface.co/api/models/".toHttpUrl().newBuilder().apply { repo.split('/').forEach { addPathSegment(it) }; addQueryParameter("blobs","true") }.build()
        val root=JSONObject(get(url))
        if (root.optBoolean("private") || gated(root)) throw UserError("Для этой модели нужен вход или принятие лицензии на Hugging Face. Выберите публичную модель.")
        val sha=root.optString("sha"); val siblings=root.optJSONArray("siblings") ?: JSONArray()
        if (siblings.length() > 5000) throw UserError("В репозитории слишком много файлов. Выберите отдельную GGUF-модель.")
        (0 until siblings.length()).mapNotNull { i ->
            val o=siblings.optJSONObject(i) ?: return@mapNotNull null
            val path=o.optString("rfilename"); if(!path.endsWith(".gguf",true)) return@mapNotNull null
            val file=HubFile(repo,sha,path,o.optLong("size",o.optJSONObject("lfs")?.optLong("size") ?: 0))
            runCatching { validateHubFile(file);file }.getOrNull()
        }.sortedBy { it.bytes }
    }
    private fun gated(o: JSONObject) = o.opt("gated").let { it == true || it == "auto" || it == "manual" }
    private suspend fun get(url: HttpUrl): String {
        transport?.let { return it(url) }
        return suspendCancellableCoroutine { continuation ->
            val call=client.newCall(Request.Builder().url(url).header("Accept","application/json").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWithException(UserError("Hugging Face не отвечает. Проверьте сеть и повторите поиск.")) }
                override fun onResponse(call: Call,response: Response) {
                    val result=runCatching { response.use {
                        if(!it.isSuccessful) throw UserError(when(it.code){401,403->"Нужен доступ к репозиторию Hugging Face. Выберите публичную модель.";404->"Репозиторий не найден.";429->"Hugging Face ограничил запросы. Повторите немного позже.";else->"Ошибка Hugging Face (${it.code}). Повторите запрос."})
                        val body=it.body ?: throw UserError("Пустой ответ Hugging Face.")
                        body.byteStream().use { stream -> readLimited(stream,3*1024*1024,"Ответ Hugging Face слишком большой.").toString(Charsets.UTF_8) }
                    } }
                    if(continuation.isActive) result.fold(continuation::resume,continuation::resumeWithException)
                }
            })
        }
    }
}
