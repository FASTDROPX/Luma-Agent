package dev.luma.agent.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FolderTools(private val context: Context, private val rootUri: String) {
    private fun root(): DocumentFile {
        if (rootUri.isBlank()) throw UserError("Выберите папку в разделе «Доступ». Агент видит только эту папку.")
        return DocumentFile.fromTreeUri(context, Uri.parse(rootUri))?.takeIf { it.exists() && it.canRead() } ?: throw UserError("Доступ к папке потерян. Выберите её заново в разделе «Доступ».")
    }
    private fun resolve(path: String): DocumentFile = relativeParts(path).fold(root()) { parent, name -> parent.findFile(name) ?: throw UserError("Файл или папка не найдены: $name") }
    private fun parent(path: String): Pair<DocumentFile, String> {
        val parts = relativeParts(path); if (parts.isEmpty()) throw UserError("Укажите имя файла.")
        val folder = resolve(parts.dropLast(1).joinToString("/")); if (!folder.isDirectory || !folder.canWrite()) throw UserError("В эту папку нельзя записывать.")
        return folder to parts.last()
    }
    suspend fun run(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "list_files" -> {
                    val directory = resolve(args.requiredString("path")); if (!directory.isDirectory) throw UserError("Это не папка.")
                    val all = directory.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name })
                    JSONObject().put("files", JSONArray(all.take(200).map { JSONObject().put("name", it.name).put("directory", it.isDirectory).put("bytes", it.length()).put("mime", it.type) })).put("truncated", all.size > 200)
                }
                "read_text" -> {
                    val file = resolve(args.requiredString("path")); if (!file.isFile) throw UserError("Это не файл.")
                    val type = file.type.orEmpty()
                    if (!(type.startsWith("text/") || type in setOf("application/json", "application/xml", "application/javascript") || file.name.orEmpty().substringAfterLast('.').lowercase() in setOf("txt", "md", "csv", "json", "log", "xml", "yaml", "yml"))) throw UserError("Этот файл не является поддерживаемым текстом.")
                    if (file.length() > 65_536) throw UserError("Текстовый файл больше 64 КБ.")
                    val bytes = context.contentResolver.openInputStream(file.uri)?.use { readLimited(it, 65_536) } ?: throw UserError("Не удалось прочитать файл.")
                    if (bytes.size > 65_536 || bytes.any { it == 0.toByte() }) throw UserError("Файл слишком большой или содержит двоичные данные.")
                    JSONObject().put("text", String(bytes, Charsets.UTF_8))
                }
                "write_text" -> {
                    val path = args.requiredString("path"); val text = args.requiredString("text", 65_536)
                    val bytes = text.toByteArray(Charsets.UTF_8); if (bytes.size > 65_536) throw UserError("Текст больше 64 КБ.")
                    val (parent, title) = parent(path); val existing = parent.findFile(title)
                    if (existing != null && (!args.optBoolean("overwrite") || !existing.isFile)) throw UserError("Файл уже существует. Для замены нужно overwrite=true и подтверждение.")
                    val backup = existing?.let { original ->
                        if (original.length() > 65_536) throw UserError("Для перезаписи поддерживаются файлы до 64 КБ.")
                        context.contentResolver.openInputStream(original.uri)?.use { readLimited(it, 65_536) } ?: throw UserError("Не удалось сохранить резервную копию перед заменой.")
                    }
                    val file = existing ?: parent.createFile("text/plain", title) ?: throw UserError("Не удалось создать файл.")
                    try {
                        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } ?: throw UserError("Не удалось записать файл.")
                    } catch (e: Exception) {
                        if (backup == null) file.delete()
                        else try { context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(backup) } ?: throw IllegalStateException() }
                        catch (restore: Exception) { throw UserError("Запись и восстановление не удались. Проверьте содержимое файла вручную.") }
                        throw e
                    }
                    JSONObject().put("saved", path).put("actualName", file.name)
                }
                "rename_file" -> {
                    val path = args.requiredString("path"); if (relativeParts(path).isEmpty()) throw UserError("Нельзя переименовать корневую папку.")
                    val newName = args.requiredString("name"); if (relativeParts(newName).size != 1 || newName.contains('/')) throw UserError("Нужно одно имя без пути.")
                    val (parent, _) = parent(path); if (parent.findFile(newName) != null) throw UserError("Такое имя уже занято.")
                    val file = resolve(path); if (!file.isFile || !file.renameTo(newName)) throw UserError("Не удалось переименовать файл.")
                    JSONObject().put("renamed", true).put("name", file.name)
                }
                "copy_file", "move_file" -> {
                    val from = args.requiredString("from"); val to = args.requiredString("to")
                    val source = resolve(from); if (!source.isFile || source.length() > 20_000_000) throw UserError("Можно переносить отдельные файлы до 20 МБ.")
                    val (targetParent, targetName) = parent(to)
                    if (targetParent.findFile(targetName) != null) throw UserError("Файл назначения уже существует. Он не будет перезаписан.")
                    val target = targetParent.createFile(source.type ?: "application/octet-stream", targetName) ?: throw UserError("Не удалось создать копию.")
                    try {
                        val job = currentCoroutineContext(); var total = 0L
                        context.contentResolver.openInputStream(source.uri).use { input ->
                            if (input == null) throw UserError("Не удалось открыть исходный файл.")
                            context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
                                if (output == null) throw UserError("Не удалось открыть файл назначения.")
                                val buffer = ByteArray(32_768)
                                while (true) { job.ensureActive(); val n = input.read(buffer); if (n < 0) break; total += n; if (total > 20_000_000) throw UserError("Файл больше 20 МБ."); output.write(buffer, 0, n) }
                            }
                        }
                        if (target.length() != total) throw UserError("Не удалось проверить размер копии.")
                    } catch (e: Throwable) { target.delete(); throw e }
                    currentCoroutineContext().ensureActive()
                    val moved = name == "move_file" && source.delete()
                    JSONObject().put("copied", to).put("actualName", target.name).put("sourceDeleted", moved)
                        .put("note", if (name == "move_file" && !moved) "Копия сохранена, исходный файл не удалось удалить." else "Готово")
                }
                "delete_file" -> {
                    val path = args.requiredString("path"); if (relativeParts(path).isEmpty()) throw UserError("Корневую папку удалить нельзя.")
                    val file = resolve(path); if (!file.isFile || !file.delete()) throw UserError("Не удалось удалить файл. Папки удалять нельзя.")
                    JSONObject().put("deleted", path)
                }
                else -> throw UserError("Неизвестная операция с файлами.")
            }
        } catch (e: SecurityException) { throw UserError("Android отклонил доступ. Выберите папку заново в разделе «Доступ».") }
        catch (e: java.io.IOException) { throw UserError("Не удалось завершить операцию с файлом. Проверьте доступ и свободное место.") }
    }
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
