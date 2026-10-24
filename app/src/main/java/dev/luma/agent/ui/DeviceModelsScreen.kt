@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.core.*
import kotlinx.coroutines.*

@Composable fun DeviceModelsScreen(graph: AppGraph) {
    val models by graph.deviceModels.state.collectAsStateWithLifecycle()
    val engine by graph.local.state.collectAsStateWithLifecycle()
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val importing by graph.deviceModels.importing.collectAsStateWithLifecycle()
    val chatBusy by graph.chatBusy.collectAsStateWithLifecycle()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val voice by graph.voice.state.collectAsStateWithLifecycle()
    val busy=engine.busy || importing || chatBusy || run.active || voice.active
    val scope=rememberCoroutineScope()
    var removing by remember {mutableStateOf<DeviceModel?>(null)}
    fun action(work: suspend ()->Unit) {scope.launch {try {work()} catch(e: CancellationException) {throw e} catch(e: Exception) {graph.uiMessage.value=safeError(e)}}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) action {graph.deviceModels.import(uri)}}
    LaunchedEffect(Unit) {runCatching {graph.downloads.refresh()}}
    MotionLazyColumn(Modifier.fillMaxSize().testTag("device-models"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            PageTitle("МОДЕЛИ","На телефоне","GGUF работают внутри Luma, без сервера и API-ключа.")
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("import-gguf")) {
                if(importing) LumaLoading() else Icon(Icons.Outlined.FileOpen,null)
                Spacer(Modifier.width(8.dp));Text(if(importing) "Импортирую GGUF…" else "Импортировать GGUF")
            }
        }
        item {SoftCard {
            Row(verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.Memory,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Text(engine.status,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)}
            if(engine.busy) LumaProgress(Modifier.fillMaxWidth())
            if(engine.model.isNotBlank()) OutlinedButton(onClick={action {graph.local.unload()}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Выгрузить из памяти")}
            Text("Чат, голос, поиск и агент используют выбранную модель. Для инструментов нужна instruct-модель, умеющая выполнять команды. Скорость и возможности зависят от модели и памяти телефона.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        if(models.isEmpty()) item {Text("Скачайте GGUF в каталоге Hugging Face или импортируйте файл. Начните с небольшой instruct-модели 0,1–1,5B в Q4.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(models,key={it.id}) {model ->
            val selected=settings.provider==Provider.DEVICE && settings.connection().model==model.id
            SoftCard {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {Text(model.name,style=MaterialTheme.typography.titleSmall);Text("${sizeLabel(model.bytes)} · ${if(model.imported) "Импорт" else "Hugging Face"}",style=MaterialTheme.typography.bodySmall)}
                    if(model.imported) IconButton(onClick={removing=model},enabled=!busy) {Icon(Icons.Outlined.DeleteOutline,"Удалить модель")}
                }
                FilledTonalButton(onClick={action {graph.useDeviceModel(model.id);graph.uiMessage.value="Модель выбрана. Откройте чат и напишите сообщение."}},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("use-${model.id}")) {
                    Icon(if(selected) Icons.Outlined.Check else Icons.Outlined.PlayArrow,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(selected) "Выбрана для чата и агента" else "Использовать в Luma")
                }
            }
        }
        item {SoftCard {
            Text("Память и скорость",style=MaterialTheme.typography.titleMedium)
            Text("Контекст: ${settings.localContext} токенов",style=MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(2048,4096,8192,16384).forEach {n ->FilterChip(settings.localContext==n,{graph.update {it.copy(localContext=n)}},enabled=!busy,label={Text("${n/1024}К")})}
            }
            Text("Потоки CPU: ${if(settings.localThreads==0) "авто" else settings.localThreads}",style=MaterialTheme.typography.bodyMedium)
            Slider(settings.localThreads.toFloat(),{n->graph.update {it.copy(localThreads=n.toInt())}},enabled=!busy,valueRange=0f..8f,steps=7)
            Text("Большой контекст расходует больше оперативной памяти. Движок работает на CPU. Эта версия поддерживает одиночные текстовые GGUF; файлы mmproj и разделённые модели не запускаются.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
    }
    removing?.let {model ->AlertDialog(onDismissRequest={removing=null},title={Text("Удалить импортированную модель?")},text={Text(model.name)},confirmButton={TextButton(onClick={removing=null;action {graph.local.unload();graph.deviceModels.remove(model.id)}}) {Text("Удалить")}},dismissButton={TextButton(onClick={removing=null}) {Text("Отмена")}})}
}
