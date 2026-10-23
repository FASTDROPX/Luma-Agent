package dev.luma.agent.core

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Files inside the one folder the user picked (Storage Access Framework). Paths are relative to
 * workspace:/ and can never leave it; nothing is overwritten, moved or deleted without the agent's
 * confirmation step, and replacements keep a backup until the new content is written.
 */
class FolderTools(private val context: Context, private val rootUri: String) {
    private fun unavailable(message: String) = ToolFailure(ToolStatus.NEEDS_USER_ACTION, message,
        "Выберите папку: Luma → «Доступ» → «Выбранная папка» → «Выбрать папку» (системный выбор Android).", WORKSPACE_UNAVAILABLE)
    private fun root(): DocumentFile {
        if (rootUri.isBlank()) throw unavailable("Рабочая папка не выбрана. Агент работает с файлами только в папке, которую пользователь выбрал сам.")
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) }.getOrNull()?.takeIf { it.exists() && it.canRead() }
            ?: throw unavailable("Доступ к рабочей папке потерян: Android отозвал разрешение или папка удалена.")
    }
    private fun parts(path: String) = relativeParts(Workspace.relative(path))
    private fun find(parts: List<String>): DocumentFile? = parts.fold(root() as DocumentFile?) { parent, name -> parent?.takeIf { it.isDirectory }?.findFile(name) }
    private fun resolve(path: String): DocumentFile {
        val p = parts(path)
        return find(p) ?: throw ToolFailure(ToolStatus.ERROR, "Не найдено: ${Workspace.display(p)}. Проверьте имя через list_files или search_files.", code = "not_found")
    }
    /** The folder for a new entry; missing folders are created only when [create] is set. */
    private fun parent(path: String, create: Boolean, created: MutableList<String> = mutableListOf()): Pair<DocumentFile, String> {
        val p = parts(path); if (p.isEmpty()) throw ToolFailure(ToolStatus.ERROR, "Укажите имя файла.")
        var folder = root()
        p.dropLast(1).forEachIndexed { i, name ->
            val next = folder.findFile(name)
            folder = when {
                next == null && create -> (folder.createDirectory(name) ?: throw ToolFailure(ToolStatus.ERROR, "Не удалось создать папку $name.")).also { created += Workspace.display(p.take(i + 1)) }
                next == null -> throw ToolFailure(ToolStatus.ERROR, "Папка не найдена: ${Workspace.display(p.take(i + 1))}.", code = "not_found")
                !next.isDirectory -> throw ToolFailure(ToolStatus.ERROR, "${Workspace.display(p.take(i + 1))} — файл, а не папка.")
                else -> next
            }
        }
        if (!folder.canWrite()) throw ToolFailure(ToolStatus.DENIED, "В эту папку нельзя записывать: у Luma доступ только для чтения.", "Выберите папку заново с правом записи.")
        return folder to p.last()
    }
    /** Providers append an extension that does not match the MIME type ("notes.md" + text/plain → "notes.md.txt"). */
    private fun mimeFor(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    private fun describe(file: DocumentFile, path: String) = JSONObject().put("path", path).put("name", file.name).put("directory", file.isDirectory)
        .put("bytes", if (file.isDirectory) JSONObject.NULL else file.length()).put("mime", file.type ?: JSONObject.NULL)
        .put("modified", file.lastModified().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().take(16) } ?: JSONObject.NULL)
    private fun readBytes(file: DocumentFile, limit: Int) = context.contentResolver.openInputStream(file.uri)?.use { readLimited(it, limit) } ?: throw ToolFailure(ToolStatus.ERROR, "Не удалось прочитать файл.")
    private fun write(file: DocumentFile, bytes: ByteArray, mode: String) { context.contentResolver.openOutputStream(file.uri, mode)?.use { it.write(bytes) } ?: throw ToolFailure(ToolStatus.ERROR, "Не удалось открыть файл для записи.") }
    private fun textBytes(args: JSONObject): ByteArray = args.getString("text").toByteArray(Charsets.UTF_8).also { if (it.size > 65_536) throw ToolFailure(ToolStatus.ERROR, "Текст больше 64 КБ.") }
    private fun created(file: DocumentFile, requested: String, path: String, extra: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("status", ToolStatus.DONE).put("verified", file.exists()).put("path", path).put("actualName", file.name).apply {
            if (file.name != requested) put("note", "Хранилище сохранило файл под именем «${file.name}».")
        }.apply(extra)

    suspend fun run(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "list_files" -> {
                    val p = parts(args.optString("path")); val directory = if (p.isEmpty()) root() else resolve(args.optString("path"))
                    if (!directory.isDirectory) throw ToolFailure(ToolStatus.ERROR, "${Workspace.display(p)} — это файл, а не папка.")
                    val all = directory.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name })
                    JSONObject().put("status", ToolStatus.DONE).put("path", Workspace.display(p))
                        .put("files", JSONArray(all.take(200).map { JSONObject().put("name", it.name).put("directory", it.isDirectory).put("bytes", it.length()).put("mime", it.type) }))
                        .put("truncated", all.size > 200)
                }
                "file_info" -> {
                    val p = parts(args.getString("path")); val file = if (p.isEmpty()) root() else find(p)
                    if (file == null) JSONObject().put("status", ToolStatus.DONE).put("exists", false).put("path", Workspace.display(p))
                    else describe(file, Workspace.display(p)).put("status", ToolStatus.DONE).put("exists", true).put("canWrite", file.canWrite())
                }
                "search_files" -> {
                    val query = args.getString("query").trim(); if (query.isEmpty()) throw ToolFailure(ToolStatus.ERROR, "Укажите часть имени.")
                    val start = parts(args.optString("path")); val base = if (start.isEmpty()) root() else resolve(args.optString("path"))
                    val matches = JSONArray(); var scanned = 0; val job = currentCoroutineContext()
                    fun walk(folder: DocumentFile, path: List<String>, depth: Int) {
                        if (depth > 8 || scanned > 3000 || matches.length() >= 50) return
                        for (child in folder.listFiles()) {
                            job.ensureActive(); scanned++
                            val childPath = path + child.name.orEmpty()
                            if (child.name.orEmpty().contains(query, ignoreCase = true) && matches.length() < 50) matches.put(describe(child, Workspace.display(childPath)))
                            if (child.isDirectory) walk(child, childPath, depth + 1)
                        }
                    }
                    walk(base, start, 0)
                    JSONObject().put("status", ToolStatus.DONE).put("matches", matches).put("truncated", scanned > 3000 || matches.length() >= 50)
                }
                "read_text" -> {
                    val file = resolve(args.getString("path")); if (!file.isFile) throw ToolFailure(ToolStatus.ERROR, "Это папка, а не файл.")
                    val type = file.type.orEmpty()
                    if (!(type.startsWith("text/") || type in setOf("application/json", "application/xml", "application/javascript") || file.name.orEmpty().substringAfterLast('.').lowercase() in setOf("txt", "md", "csv", "json", "log", "xml", "yaml", "yml")))
                        throw ToolFailure(ToolStatus.UNSUPPORTED, "Этот файл не является поддерживаемым текстом.")
                    if (file.length() > 65_536) throw ToolFailure(ToolStatus.ERROR, "Текстовый файл больше 64 КБ.")
                    val bytes = readBytes(file, 65_536)
                    if (bytes.any { it == 0.toByte() }) throw ToolFailure(ToolStatus.UNSUPPORTED, "Файл содержит двоичные данные.")
                    JSONObject().put("status", ToolStatus.DONE).put("path", Workspace.display(parts(args.getString("path")))).put("text", String(bytes, Charsets.UTF_8))
                }
                "write_text" -> {
                    val path = Workspace.display(parts(args.getString("path"))); val bytes = textBytes(args)
                    val folders = mutableListOf<String>(); val (parent, title) = parent(args.getString("path"), create = true, created = folders)
                    val existing = parent.findFile(title)
                    if (existing != null && existing.isDirectory) throw ToolFailure(ToolStatus.ERROR, "$path — это папка.")
                    if (existing != null && !args.optBoolean("overwrite"))
                        throw ToolFailure(ToolStatus.ERROR, "Файл $path уже существует и не изменён. Возьмите другое имя, append_text для дополнения или overwrite=true (замена с подтверждением).",
                            code = "exists", extra = JSONObject().put("existing", describe(existing, path)))
                    val backup = existing?.let { original ->
                        if (original.length() > 65_536) throw ToolFailure(ToolStatus.ERROR, "Для замены поддерживаются файлы до 64 КБ.")
                        readBytes(original, 65_536)
                    }
                    val file = existing ?: parent.createFile(mimeFor(title), title) ?: throw ToolFailure(ToolStatus.ERROR, "Не удалось создать файл.")
                    try { write(file, bytes, "wt") } catch (e: Exception) {
                        if (backup == null) file.delete()
                        else try { write(file, backup, "wt") } catch (restore: Exception) { throw ToolFailure(ToolStatus.UNKNOWN, "Запись и восстановление не удались. Проверьте содержимое $path вручную.") }
                        throw e
                    }
                    created(file, title, path) { put("bytes", file.length()).put("replaced", backup != null); if (folders.isNotEmpty()) put("createdFolders", JSONArray(folders)) }
                        .apply { put("verified", optBoolean("verified") && file.length() == bytes.size.toLong()) }
                }
                "append_text" -> {
                    val path = Workspace.display(parts(args.getString("path"))); val bytes = textBytes(args)
                    val (parent, title) = parent(args.getString("path"), create = true)
                    val existing = parent.findFile(title)
                    if (existing == null) {
                        val file = parent.createFile(mimeFor(title), title) ?: throw ToolFailure(ToolStatus.ERROR, "Не удалось создать файл.")
                        try { write(file, bytes, "wt") } catch (e: Exception) { file.delete(); throw e }
                        return@withContext created(file, title, path) { put("bytes", file.length()).put("createdFile", true) }
                    }
                    if (!existing.isFile) throw ToolFailure(ToolStatus.ERROR, "$path — это папка.")
                    if (existing.length() > 4_000_000) throw ToolFailure(ToolStatus.ERROR, "Дописывать можно в файлы до 4 МБ.")
                    val backup = readBytes(existing, 4_000_000)
                    // Some providers do not support append mode; then the whole file is rewritten from the backup plus the new text.
                    try { write(existing, bytes, "wa") } catch (e: Exception) { write(existing, backup + bytes, "wt") }
                    if (existing.length() != (backup.size + bytes.size).toLong()) {
                        runCatching { write(existing, backup + bytes, "wt") }
                        if (existing.length() != (backup.size + bytes.size).toLong()) {
                            runCatching { write(existing, backup, "wt") }
                            throw ToolFailure(ToolStatus.UNKNOWN, "Хранилище записало неожиданный размер; восстановлено прежнее содержимое $path. Проверьте файл.")
                        }
                    }
                    JSONObject().put("status", ToolStatus.DONE).put("verified", true).put("path", path).put("bytes", existing.length()).put("appended", bytes.size)
                }
                "create_folder" -> {
                    val p = parts(args.getString("path")); if (p.isEmpty()) throw ToolFailure(ToolStatus.ERROR, "Укажите имя папки.")
                    val folders = mutableListOf<String>(); val (parent, title) = parent(args.getString("path"), create = true, created = folders)
                    val existing = parent.findFile(title)
                    if (existing != null && !existing.isDirectory) throw ToolFailure(ToolStatus.ERROR, "${Workspace.display(p)} уже существует как файл.")
                    val folder = existing ?: parent.createDirectory(title) ?: throw ToolFailure(ToolStatus.ERROR, "Не удалось создать папку.")
                    JSONObject().put("status", ToolStatus.DONE).put("verified", folder.isDirectory).put("path", Workspace.display(p)).put("existed", existing != null)
                        .apply { if (folders.isNotEmpty()) put("createdFolders", JSONArray(folders)) }
                }
                "rename_file" -> {
                    val p = parts(args.getString("path")); if (p.isEmpty()) throw ToolFailure(ToolStatus.ERROR, "Нельзя переименовать рабочую папку.")
                    val newName = args.getString("name"); if (newName.contains('/') || relativeParts(newName).size != 1) throw ToolFailure(ToolStatus.ERROR, "Нужно одно имя без пути.")
                    val (parent, _) = parent(args.getString("path"), create = false)
                    if (parent.findFile(newName) != null) throw ToolFailure(ToolStatus.ERROR, "Имя «$newName» уже занято.", code = "exists")
                    val file = resolve(args.getString("path")); if (!file.renameTo(newName)) throw ToolFailure(ToolStatus.ERROR, "Не удалось переименовать.")
                    JSONObject().put("status", ToolStatus.DONE).put("verified", parent.findFile(file.name.orEmpty()) != null).put("name", file.name).put("path", Workspace.display(p.dropLast(1) + file.name.orEmpty()))
                }
                "copy_file", "move_file" -> {
                    val source = resolve(args.getString("from")); if (!source.isFile || source.length() > 20_000_000) throw ToolFailure(ToolStatus.ERROR, "Можно переносить отдельные файлы до 20 МБ.")
                    val to = Workspace.display(parts(args.getString("to"))); val (targetParent, targetName) = parent(args.getString("to"), create = true)
                    if (targetParent.findFile(targetName) != null) throw ToolFailure(ToolStatus.ERROR, "Файл назначения $to уже существует. Он не будет перезаписан.", code = "exists")
                    val target = targetParent.createFile(source.type ?: mimeFor(targetName), targetName) ?: throw ToolFailure(ToolStatus.ERROR, "Не удалось создать копию.")
                    try {
                        val job = currentCoroutineContext(); var total = 0L
                        context.contentResolver.openInputStream(source.uri).use { input ->
                            if (input == null) throw ToolFailure(ToolStatus.ERROR, "Не удалось открыть исходный файл.")
                            context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                                if (output == null) throw ToolFailure(ToolStatus.ERROR, "Не удалось открыть файл назначения.")
                                val buffer = ByteArray(32_768)
                                while (true) { job.ensureActive(); val n = input.read(buffer); if (n < 0) break; total += n; if (total > 20_000_000) throw ToolFailure(ToolStatus.ERROR, "Файл больше 20 МБ."); output.write(buffer, 0, n) }
                            }
                        }
                        if (target.length() != total) throw ToolFailure(ToolStatus.ERROR, "Не удалось проверить размер копии.")
                    } catch (e: Throwable) { target.delete(); throw e }
                    currentCoroutineContext().ensureActive()
                    val moved = name == "move_file" && source.delete()
                    JSONObject().put("status", ToolStatus.DONE).put("verified", true).put("copied", to).put("actualName", target.name).put("sourceDeleted", moved)
                        .put("note", if (name == "move_file" && !moved) "Копия сохранена, исходный файл не удалось удалить." else "Готово")
                }
                "delete_file" -> {
                    val p = parts(args.getString("path")); if (p.isEmpty()) throw ToolFailure(ToolStatus.DENIED, "Рабочую папку удалить нельзя.")
                    val file = resolve(args.getString("path"))
                    if (file.isDirectory && file.listFiles().isNotEmpty()) throw ToolFailure(ToolStatus.DENIED, "Папка не пуста. Удалять можно только файлы и пустые папки.")
                    if (!file.delete()) throw ToolFailure(ToolStatus.ERROR, "Не удалось удалить.")
                    JSONObject().put("status", ToolStatus.DONE).put("verified", find(p) == null).put("deleted", Workspace.display(p))
                }
                else -> throw ToolFailure(ToolStatus.ERROR, "Неизвестная операция с файлами.")
            }
        } catch (e: SecurityException) { throw unavailable("Android отклонил доступ к рабочей папке.") }
        catch (e: java.io.IOException) { throw ToolFailure(ToolStatus.ERROR, "Не удалось завершить операцию с файлом. Проверьте доступ и свободное место.") }
    }
    companion object { const val WORKSPACE_UNAVAILABLE = "workspace_unavailable" }
}
internal fun readLimited(input: java.io.InputStream, maximum: Int, tooLarge: String = "Файл больше ${limitLabel(maximum)}."): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - out.size()))
        if (count < 0) break
        if (count == 0) throw UserError("Не удалось прочитать данные файла.")
        out.write(buffer, 0, count)
        if (out.size() > maximum) throw UserError(tooLarge)
    }
    return out.toByteArray()
}
internal fun limitLabel(bytes: Int): String = if (bytes >= 1_000_000) "${bytes / 1_000_000} МБ" else "${bytes / 1024} КБ"
