package dev.luma.agent.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

/**
 * What the model knows about the user's folder. The real content:// URI stays inside Luma; the model
 * sees the stable root "workspace:/", a display name and the access state, never a guessed /storage path.
 */
data class WorkspaceInfo(val status: String, val name: String = "", val location: String = "", val canRead: Boolean = false, val canWrite: Boolean = false) {
    val usable get() = status == READY || status == READ_ONLY
    fun json(): JSONObject = JSONObject().put("root", Workspace.ROOT).put("status", status).put("name", name).put("location", location)
        .put("read", canRead).put("write", canWrite)
    fun prompt(): String = when (status) {
        NOT_SELECTED -> "Рабочая папка НЕ выбрана. При первом файловом инструменте Luma покажет в чате карточку «Выбранная папка» и продолжит после выбора. Не ищи папку на экране и в файловом менеджере."
        LOST -> "Доступ к рабочей папке «$name» потерян (Android отозвал разрешение или папка удалена). При файловом инструменте Luma покажет карточку выбора папки и продолжит после выдачи доступа. Не ищи её на экране."
        else -> "Рабочая папка workspace:/ = «$name»" + (if (location.isNotEmpty()) " ($location)" else "") +
            ". Права: чтение ${if (canRead) "да" else "нет"}, запись ${if (canWrite) "да" else "нет"}. Это папка, выбранная пользователем через системный выбор; " +
            "её физический путь неизвестен и не нужен. Относительные пути (test.txt, notes/plan.md) и пути вида workspace:/test.txt разрешаются внутри неё. " +
            "Создавая файл, сразу вызывай write_text: существование и права проверяются внутри."
    }
    companion object {
        const val READY = "ready"; const val READ_ONLY = "read_only"; const val LOST = "lost"; const val NOT_SELECTED = "not_selected"
    }
}

object Workspace {
    const val ROOT = "workspace:/"
    /** "workspace:/a/b.txt", "workspace:a.txt" and "a/b.txt" all mean the same relative path. */
    /** Other absolute paths keep their leading slash, so [relativeParts] still rejects them. */
    fun relative(path: String): String = path.trim().let { p -> if (p.startsWith("workspace:")) p.removePrefix("workspace:").trimStart('/') else p }
    fun display(parts: List<String>): String = ROOT + parts.joinToString("/")

    fun describe(context: Context, raw: String): WorkspaceInfo {
        if (raw.isBlank()) return WorkspaceInfo(WorkspaceInfo.NOT_SELECTED)
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return WorkspaceInfo(WorkspaceInfo.LOST)
        val fallbackName = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').substringAfter(':') }.getOrNull().orEmpty()
        val location = location(context, uri)
        val granted = runCatching { context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } }.getOrNull()
        val folder = runCatching { DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.isDirectory } }.getOrNull()
        val name = runCatching { folder?.name }.getOrNull()?.takeIf(String::isNotBlank) ?: fallbackName.ifBlank { "Папка" }
        if (folder == null || !runCatching { folder.canRead() }.getOrDefault(false)) return WorkspaceInfo(WorkspaceInfo.LOST, name, location)
        val canWrite = runCatching { folder.canWrite() }.getOrDefault(false) && (granted?.isWritePermission ?: true)
        return WorkspaceInfo(if (canWrite) WorkspaceInfo.READY else WorkspaceInfo.READ_ONLY, name, location, true, canWrite)
    }
    /** A human description of where the folder lives: «Внутренняя память › Documents › Luma», «Загрузки», a provider name. */
    fun location(context: Context, uri: Uri): String {
        val authority = uri.authority.orEmpty()
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        return when (authority) {
            "com.android.externalstorage.documents" -> {
                val root = documentId.substringBefore(':'); val path = documentId.substringAfter(':', "")
                (listOf(if (root == "primary") "Внутренняя память" else "Карта памяти или USB ($root)") + path.split('/').filter(String::isNotBlank)).joinToString(" › ")
            }
            "com.android.providers.downloads.documents" -> "Загрузки"
            else -> runCatching { context.packageManager.resolveContentProvider(authority, 0)?.loadLabel(context.packageManager)?.toString() }.getOrNull().orEmpty()
        }
    }
}
