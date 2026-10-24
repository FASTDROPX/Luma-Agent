package dev.luma.agent.ui

import dev.luma.agent.core.tr

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.luma.agent.core.GeneratedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun GeneratedFiles(files: List<GeneratedFile>) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var saving by remember {mutableStateOf(false)}
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var note by remember {mutableStateOf<String?>(null)}
    val download=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val file=selected?.let {runCatching {GeneratedFile.parse(JSONObject(it))}.getOrNull()};selected=null
        if(target!=null && file!=null) scope.launch {
            saving=true
            note=try {
                withContext(Dispatchers.IO) {
                    val source=Uri.parse(file.uri)
                    val sameDocument=source.authority==target.authority && runCatching {DocumentsContract.getDocumentId(source)==DocumentsContract.getDocumentId(target)}.getOrDefault(false)
                    check(source!=target && !sameDocument) {"Choose a different destination"}
                    context.contentResolver.openInputStream(source)?.use {input ->
                        context.contentResolver.openOutputStream(target,"wt")?.use {output ->
                            val buffer=ByteArray(32768);var total=0L
                            while(true) {val count=input.read(buffer);if(count<0) break;total+=count;if(total>256_000_000) error("too large");output.write(buffer,0,count)}
                        } ?: error("output unavailable")
                    } ?: error("source unavailable")
                }
                tr("Копия файла сохранена")
            } catch (_: Exception) {tr("Не удалось сохранить файл. Проверьте доступ к исходному файлу и место в памяти.")}
            finally {saving=false}
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
        files.distinctBy {it.uri}.forEach {file ->
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.fillMaxWidth().testTag("generated-file")) {
                Row(Modifier.padding(start=14.dp,end=4.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.InsertDriveFile,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        val date=SimpleDateFormat("d MMM, HH:mm",Locale("ru")).format(Date(file.created))
                        val size=android.text.format.Formatter.formatShortFileSize(context,file.size)
                        Text("$date · $size",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick={selected=file.json().toString();download.launch(file.name)},enabled=!saving,modifier=Modifier.testTag("generated-download")) {
                        if(saving) LumaLoading(Modifier.size(22.dp)) else Icon(Icons.Outlined.ArrowDownward,tr("Скачать {0}" ,file.name),Modifier.size(21.dp))
                    }
                    IconButton(onClick={scope.launch {
                        note=withContext(Dispatchers.IO) {runCatching {openGeneratedFile(context,file);null}.getOrElse {tr("Не удалось открыть файл. Возможно, он перемещён или доступ к папке отозван.")}}
                    }},modifier=Modifier.testTag("generated-open")) {Icon(Icons.Outlined.SouthEast,tr("Открыть {0}" ,file.name),Modifier.size(21.dp))}
                }
            }
        }
        note?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

/** Prefer the actual folder in a file manager. Android's document picker is the portable fallback for SAF files. */
private fun openGeneratedFile(context: android.content.Context,file: GeneratedFile) {
    val source=Uri.parse(file.uri)
    val parent=Uri.parse(file.parentUri)
    if(parent.scheme=="content" && DocumentsContract.isDocumentUri(context,parent)) {
        val folder=Intent(Intent.ACTION_VIEW).setDataAndType(parent,DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if(runCatching {context.startActivity(folder)}.isSuccess) return
        context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI,source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    if(source.scheme=="file") {
        val original=File(source.path ?: error("missing path"))
        val external=android.os.Environment.getExternalStorageDirectory().canonicalFile
        if(original.canonicalFile.toPath().startsWith(external.toPath())) {
            val relative=original.parentFile!!.canonicalFile.relativeTo(external).invariantSeparatorsPath
            val folder=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:$relative")
            if(runCatching {context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(folder,DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.isSuccess) return
        }
        val copy=File(context.cacheDir,"share/${java.util.UUID.randomUUID()}/${file.name.replace('/','_').replace('\\','_')}")
        copy.parentFile!!.mkdirs();original.copyTo(copy,overwrite=false)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.model-files",copy)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } else context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(source,file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}
