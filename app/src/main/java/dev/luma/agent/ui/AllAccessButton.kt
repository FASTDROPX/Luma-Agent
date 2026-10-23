package dev.luma.agent.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.luma.agent.AppGraph
import dev.luma.agent.core.OptionalAccess

/** Runtime dialogs first, dependent background location next, special settings, folder, accessibility last. */
@Composable fun AllAccessButton(graph: AppGraph, refresh: () -> Unit = {}) {
    val context = LocalContext.current
    var queue by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var running by rememberSaveable { mutableStateOf(false) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    var intro by remember { mutableStateOf(false) }
    fun next() { waiting=false; queue=queue.drop(1); refresh(); if(queue.isEmpty()) running=false }
    val special = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { next() }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { next() }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) try {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            graph.update { it.copy(folder=uri.toString()) }
        } catch (_: Exception) { graph.uiMessage.value="Не удалось сохранить доступ к папке." }
        next()
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        waiting=false; refresh(); if(queue.isEmpty()) running=false
    }
    FilledTonalButton(onClick={intro=true},enabled=!running,modifier=Modifier.fillMaxWidth().testTag("request-all-access")) {
        Icon(Icons.Outlined.DoneAll,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(if(running) "Настройка доступов…" else "Запросить все разом")
    }
    if(intro) AlertDialog(onDismissRequest={intro=false},title={Text("Запросить все доступы?")},text={
        Text("Сначала Android покажет обычные запросы, затем Luma по очереди откроет специальные настройки и выбор папки. Управление экраном будет последним. Любой пункт можно пропустить.")
    },confirmButton={TextButton(onClick={
        intro=false; running=true
        queue=buildList {
            OptionalAccess.items().filter { (it.runtime().isEmpty() || it.id=="background_location") && !it.granted(context) }.sortedBy {if(it.id=="background_location") 0 else 1}.forEach { add(it.id) }
            if(graph.vault.state.value.folder.isBlank()) add("workspace")
            if(!OptionalAccess.granted(context,"accessibility")) add("accessibility")
        }
        val missing=OptionalAccess.items().filter {it.id!="background_location"}.flatMap {it.runtime()}.distinct()
            .filter {context.checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty()) {waiting=true;runtime.launch(missing.toTypedArray())}
        else if(queue.isEmpty()) {running=false;graph.uiMessage.value="Все доступные разрешения уже выданы."}
    }) {Text("Начать")}},dismissButton={TextButton(onClick={intro=false}) {Text("Отмена")}})
    val id=queue.firstOrNull()
    val item=id?.let(OptionalAccess::item)
    // Recheck dependencies and grants at every step; never ask background location with foreground permission.
    LaunchedEffect(id,running,waiting) {
        if(running && !waiting && id!=null && id!="workspace" &&
            (OptionalAccess.granted(context,id) || id=="background_location" && !OptionalAccess.granted(context,"location"))) next()
    }
    if(running && !waiting && id!=null) AlertDialog(onDismissRequest={running=false;queue=emptyList()},
        title={Text(if(id=="workspace") "Папка для файлов" else item?.title ?: "Доступ")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(id=="workspace") "Выберите папку, в которой агент сможет создавать файлы." else item?.purpose.orEmpty())
            if(id=="accessibility") Text("В специальных возможностях выберите «Luma · управление телефоном» и включите службу. Это последний шаг.")
            Text("Осталось пунктов: ${queue.size}",style=MaterialTheme.typography.labelMedium)
        }},confirmButton={TextButton(onClick={
            try {
                waiting=true
                when {
                    id=="workspace" -> folder.launch(null)
                    id=="background_location" && Build.VERSION.SDK_INT==29 -> background.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    else -> {
                        val screens=if(id=="background_location") OptionalAccess.appDetails(context) else item?.screens?.invoke(context).orEmpty()
                        val target=screens.firstOrNull {it.resolveActivity(context.packageManager)!=null}
                        if(target==null) {graph.uiMessage.value="Этот экран настроек недоступен на устройстве.";next()} else special.launch(target)
                    }
                }
            } catch (_: Exception) {graph.uiMessage.value="Android не открыл этот запрос. Пункт пропущен.";next()}
        }) {Text(if(id=="workspace") "Выбрать папку" else "Открыть")}},
        dismissButton={TextButton(onClick={next()}) {Text("Пропустить")}})
}
