@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun HuggingFaceScreen(graph: AppGraph, client: HuggingFaceClient = graph.hub) {
    val context=LocalContext.current; val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope(); val lifecycle=LocalLifecycleOwner.current
    val downloads by graph.downloads.state.collectAsStateWithLifecycle()
    var library by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("Qwen3-0.6B") }
    var requested by rememberSaveable { mutableStateOf(query) }
    var repository by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var repositories by remember { mutableStateOf(emptyList<HubRepo>()) }
    var files by remember { mutableStateOf(emptyList<HubFile>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<HubFile?>(null) }
    var removing by remember { mutableStateOf<HubDownload?>(null) }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    fun search() { keyboard?.hide(); repository=null; requested=query.trim(); retry++ }
    fun openPage(repo: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://huggingface.co/$repo"))) }.onFailure { graph.uiMessage.value="Не удалось открыть браузер." } }
    fun action(work: suspend () -> Unit) { scope.launch { try { work() } catch(e:CancellationException) { throw e } catch(e:Exception) { graph.uiMessage.value=safeError(e) } } }
    LaunchedEffect(requested,repository,retry,library) {
        if(library) return@LaunchedEffect
        busy=true;error=null
        try {
            val repo=repository
            if(repo!=null) files=client.files(repo) else repositories=client.search(requested)
        } catch(e:CancellationException) { throw e } catch(e:Exception) { error=safeError(e) } finally { busy=false }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var reported=false
            while(true) {
                try { graph.downloads.refresh() } catch(e:CancellationException) { throw e } catch(e:Exception) { if(!reported) { graph.uiMessage.value="Не удалось обновить загрузки Android."; reported=true } }
                delay(1500)
            }
        }
    }
    Column(Modifier.fillMaxSize().testTag("hugging-face")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Модели на телефон", Modifier.weight(1f), style=MaterialTheme.typography.titleMedium)
            IconButton(onClick={info=true}) { Icon(Icons.Outlined.Info,"Как использовать GGUF") }
        }
        Text("Файлы GGUF · запуск через локальный AI-сервер", Modifier.padding(horizontal=20.dp), style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(horizontal=20.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!library,{library=false},label={Text("Каталог")})
            FilterChip(library,{library=true},label={Text("Загрузки (${downloads.size})")},leadingIcon={Icon(Icons.Outlined.Download,null,Modifier.size(16.dp))},modifier=Modifier.testTag("hub-library"))
        }
        if(library) MotionLazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(downloads.isEmpty()) item { Text(t("Пока пусто. Найди модель в каталоге и выбери файл GGUF."),color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(downloads,key={it.id}) { item ->
                DownloadCard(item,share={action {
                    val file=graph.downloads.fileForShare(item.id)
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.model-files",file)
                    val intent=Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData=ClipData.newRawUri(item.file.name,uri) }
                    context.startActivity(Intent.createChooser(intent,"Открыть GGUF в приложении"))
                }},retry={action {graph.downloads.retry(item.id)}},remove={if(item.phase==HubPhase.READY) removing=item else action {graph.downloads.remove(item.id)}})
            }
        } else {
            if(repository==null) OutlinedTextField(query,{query=it.take(96)},Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp).testTag("hub-search"),singleLine=true,label={Text("Название модели")},placeholder={Text("Qwen, Gemma, SmolLM…")},shape=MaterialTheme.shapes.medium,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),trailingIcon={IconButton(onClick={search()}){Icon(Icons.Outlined.Search,"Найти GGUF")}})
            else Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={repository=null}) {Icon(Icons.Outlined.ArrowBack,"К результатам")}
                Text(repository!!,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                IconButton(onClick={openPage(repository!!)}) {Icon(Icons.Outlined.OpenInNew,"Карточка и лицензия")}
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp))
            MotionLazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(error!=null) item { SoftCard { Text(error!!,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium);OutlinedButton(onClick={retry++}){Text("Повторить")}} }
                else if(!busy) {
                    if(repository==null) {
                        if(repositories.isEmpty()) item { Text(t("Модели не найдены. Попробуй другое название."),style=MaterialTheme.typography.bodyMedium) }
                        items(repositories,key={it.id}) { repo -> Surface(onClick={repository=repo.id},shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().appear()) {
                            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.AutoAwesome,null,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)) {Text(repo.id,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis);Text("${repo.downloads} скачиваний · GGUF",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Outlined.ChevronRight,null) }
                        } }
                    } else {
                        if(files.isEmpty()) item {Text(t("В репозитории нет доступных GGUF до 32 ГБ. Выбери другую модель."),style=MaterialTheme.typography.bodyMedium)}
                        items(files,key={it.identity}) { file ->
                            val exists=downloads.any{it.file.identity==file.identity}
                            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().appear()) {
                                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Text(file.path,style=MaterialTheme.typography.titleSmall)
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        Text(sizeLabel(file.bytes),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        FilledTonalButton(onClick={if(exists)library=true else pending=file}) {Icon(if(exists)Icons.Outlined.Check else Icons.Outlined.Download,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(exists)"В загрузках" else "Скачать")}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pending?.let { file -> AlertDialog(onDismissRequest={if(!adding)pending=null},title={Text("Скачать модель?")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(file.name,style=MaterialTheme.typography.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
            Text("${sizeLabel(file.bytes)} · ${file.repo}",style=MaterialTheme.typography.bodyMedium)
            if(Regex("-\\d{5}-of-\\d{5}").containsMatchIn(file.name)) Text("Это часть набора. Для запуска понадобятся все части этой версии модели.",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(wifiOnly,{wifiOnly=it});Text("Только Wi-Fi",style=MaterialTheme.typography.bodyMedium)}
            TextButton(onClick={openPage(file.repo)}){Text("Карточка модели и лицензия")}
        }
    },confirmButton={TextButton(enabled=!adding,onClick={adding=true;action { try { graph.downloads.enqueue(file,wifiOnly);pending=null;library=true } finally {adding=false} }}){Text(if(adding)"Начинаю…" else "Скачать")}},dismissButton={TextButton(enabled=!adding,onClick={pending=null}){Text("Отмена")}}) }
    removing?.let { item -> AlertDialog(onDismissRequest={removing=null},title={Text("Удалить модель?")},text={Text("${item.file.name}\nОсвободится ${sizeLabel(item.file.bytes)}. При необходимости файл можно скачать снова.")},confirmButton={TextButton(onClick={removing=null;action{graph.downloads.remove(item.id)}}){Text("Удалить")}},dismissButton={TextButton(onClick={removing=null}){Text("Отмена")}}) }
    if(info) AlertDialog(onDismissRequest={info=false},title={Text("Модели Hugging Face")},text={Text("Здесь можно скачать публичные GGUF. Для работы модели требуется больше свободной оперативной памяти, чем размер файла.\n\nГотовый файл можно передать локальному AI-приложению. Для чата и агента Luma подключи его OpenAI-совместимый сервер во вкладке «API-модели» → «Локальный сервер». Встроенного запуска GGUF в этой версии нет.\n\nAndroid продолжает загрузку в фоне. Wi-Fi и состояние сети видны в «Загрузках». Удаление Luma удалит эти файлы; важные модели предварительно передай в другое приложение.",Modifier.motionScroll())},confirmButton={TextButton(onClick={info=false}){Text("Понятно")}})
}

@Composable private fun DownloadCard(item: HubDownload,share: () -> Unit,retry: () -> Unit,remove: () -> Unit) {
    SoftCard {
        Row(verticalAlignment=Alignment.Top) {
            Column(Modifier.weight(1f)) {Text(item.file.name,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis);Text(item.file.repo,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
            IconButton(onClick=remove) {Icon(if(item.phase==HubPhase.READY)Icons.Outlined.DeleteOutline else Icons.Outlined.Close,if(item.phase==HubPhase.READY)"Удалить модель" else "Отменить загрузку")}
        }
        Text(item.message,style=MaterialTheme.typography.bodySmall,color=if(item.phase==HubPhase.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if(item.phase==HubPhase.DOWNLOADING || item.phase==HubPhase.PAUSED) {val progress=(item.received.toDouble()/item.file.bytes).coerceIn(0.0,1.0).toFloat();LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());Text("${sizeLabel(item.received)} / ${sizeLabel(item.file.bytes)} · ${(progress*100).toInt()}%",style=MaterialTheme.typography.labelSmall)}
        if(item.phase==HubPhase.QUEUED) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(item.phase==HubPhase.READY) FilledTonalButton(onClick=share,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.OpenInNew,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Передать файл")}
        if(item.phase==HubPhase.ERROR) OutlinedButton(onClick=retry,modifier=Modifier.fillMaxWidth()){Text("Повторить загрузку")}
    }
}
