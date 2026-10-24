@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.core.VoiceSettings

@Composable fun VoiceStart(graph: AppGraph,mode: String,label: String,icon: ImageVector,compact: Boolean=false) {
    val context=LocalContext.current
    val voice by graph.voice.state.collectAsStateWithLifecycle()
    var explain by remember {mutableStateOf(false)}
    fun start() {graph.voice.start(mode)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {result ->
        if(result[Manifest.permission.RECORD_AUDIO]==true || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) start()
        else graph.uiMessage.value="Без микрофона голосовой режим недоступен. Разрешите его в настройках доступа."
    }
    fun request() {
        val needed=buildList {
            if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if(needed.isEmpty()) start() else permission.launch(needed.toTypedArray())
    }
    val click={if(mode=="wake") explain=true else request()}
    if(compact) IconButton(onClick=click,enabled=!voice.active,modifier=Modifier.size(44.dp).testTag("voice-$mode")) {Icon(icon,label,Modifier.size(22.dp))}
    else FilledTonalButton(onClick=click,enabled=!voice.active,modifier=Modifier.fillMaxWidth().testTag("voice-$mode")) {Icon(icon,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(label)}
    if(explain) AlertDialog(onDismissRequest={explain=false},icon={Icon(Icons.Outlined.Mic,null)},title={Text("Слушать вызов в фоне?")},
        text={Text("Микрофон останется включённым, в том числе после блокировки экрана. Вызов распознаётся на телефоне; в облачном режиме речь после вызова отправляется выбранному речевому API. Уведомление позволит остановить прослушивание. После перезапуска телефона нужно включить его снова.")},
        confirmButton={TextButton(onClick={explain=false;request()}) {Text("Включить")}},dismissButton={TextButton(onClick={explain=false}) {Text("Отмена")}})
}

@Composable fun VoicePanel(graph: AppGraph) {
    val voice by graph.voice.state.collectAsStateWithLifecycle()
    if(!voice.active) return
    val color=MaterialTheme.colorScheme.primary
    val level by animateFloatAsState(if(voice.listening) voice.level else .15f,animationSpec=if(LocalReducedMotion.current) snap() else spring(),label="voice level")
    SoftCard(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).testTag("voice-session")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Canvas(Modifier.size(36.dp)) {
                for(i in 0..4) {
                    val h=size.height*(.2f+level*(if(i%2==0) .8f else .5f))
                    drawLine(color,androidx.compose.ui.geometry.Offset(size.width*(i+1)/6,size.height/2-h/2),androidx.compose.ui.geometry.Offset(size.width*(i+1)/6,size.height/2+h/2),strokeWidth=3.dp.toPx(),cap=StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {Text(voice.phase,style=MaterialTheme.typography.titleSmall)
                if(voice.partial.isNotBlank()) Text(voice.partial.take(200),maxLines=2,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(if(voice.mode=="dictation") "Скажите текст для сообщения" else "«Заверши разговор» или кнопка остановки",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(voice.mode=="dictation") IconButton(onClick={graph.voice.finishDictation()}) {Icon(Icons.Outlined.Check,"Завершить диктовку")}
            IconButton(onClick={graph.voice.stop()},modifier=Modifier.testTag("voice-stop")) {Icon(Icons.Outlined.StopCircle,"Остановить голос")}
        }
    }
}

@Composable fun VoiceSettingsContent(graph: AppGraph) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val download by graph.voice.model.state.collectAsStateWithLifecycle()
    val voices by graph.voice.output.voices.collectAsStateWithLifecycle()
    val session by graph.voice.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var config by remember {mutableStateOf(settings.voice)}
    fun change(value: VoiceSettings) {config=value;graph.update {it.copy(voice=value)}}
    val zipPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->if(uri!=null) {graph.voice.stop();graph.voice.model.importZip(uri)}}
    LaunchedEffect(Unit) {graph.voice.output.loadVoices()}
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        SoftCard {
            Text("Русский голосовой модуль",style=MaterialTheme.typography.titleMedium)
            Text("Vosk · 46 МБ загрузка · около 300 МБ памяти при работе. Нужен для локальной диктовки и вызова «Люма», в том числе с облачной речью.",style=MaterialTheme.typography.bodySmall)
            if(download.busy) {LumaProgress(Modifier.fillMaxWidth(),progress={download.progress});Text("${download.phase} · ${(download.progress*100).toInt()} %");TextButton(onClick={graph.voice.model.cancel()}) {Text("Отменить загрузку")}}
            else if(download.ready) Text("Модель установлена",color=MaterialTheme.colorScheme.primary)
            else Button(onClick={graph.voice.model.download()},modifier=Modifier.testTag("download-voice-model")) {Icon(Icons.Outlined.Download,null);Spacer(Modifier.width(8.dp));Text("Скачать голосовую модель")}
            if(!download.busy && !session.active) TextButton(onClick={zipPicker.launch(arrayOf("application/zip","application/octet-stream"))}) {Icon(Icons.Outlined.FolderZip,null);Spacer(Modifier.width(8.dp));Text("Установить модель из ZIP")}
            download.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Text("Vosk / Alpha Cephei · Apache 2.0",style=MaterialTheme.typography.labelSmall)
        }
        SoftCard {
            Text("Распознавание речи",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(config.recognition=="local",{change(config.copy(recognition="local"))},label={Text("На телефоне")})
                FilterChip(config.recognition=="cloud",{change(config.copy(recognition="cloud"))},label={Text("Через API")})
            }
            Text("Диктовка заполняет поле сообщения. В разговоре фразы отправляются выбранной модели автоматически после паузы.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(config.wakeWords,{change(config.copy(wakeWords=it.take(500)))},label={Text("Фразы вызова через запятую")},modifier=Modifier.fillMaxWidth(),supportingText={Text("Произнесите отдельно или перед командой. Можно добавить вариант, который распознаётся у вас точнее.")})
        }
        SoftCard {
            Text("Озвучка ответов",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(config.synthesis=="local",{change(config.copy(synthesis="local"))},label={Text("На телефоне")})
                FilterChip(config.synthesis=="cloud",{change(config.copy(synthesis="cloud"))},label={Text("Через API")})
            }
            if(config.synthesis=="local") {
                var expanded by remember {mutableStateOf(false)}
                Box {
                    OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {Text(voices.firstOrNull {it.id==config.deviceVoice}?.label ?: "Русский офлайн-голос по умолчанию",maxLines=2);Icon(Icons.Outlined.ArrowDropDown,null)}
                    DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=340.dp)) {
                        DropdownMenuItem(text={Text("По умолчанию")},onClick={expanded=false;change(config.copy(deviceVoice=""))})
                        voices.take(100).forEach {v->DropdownMenuItem(text={Text(v.label)},onClick={expanded=false;change(config.copy(deviceVoice=v.id))})}
                    }
                }
                TextButton(onClick={runCatching {context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}}) {Text("Установить голоса в Android")}
                Text("Доступны установленные офлайн-голоса движка Android. Для нескольких голосов установите дополнительные голосовые данные.",style=MaterialTheme.typography.bodySmall)
            } else {
                listOf(listOf("coral","nova","alloy"),listOf("onyx","shimmer","echo")).forEach {row ->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {row.forEach {name ->FilterChip(config.cloudVoice==name,{change(config.copy(cloudVoice=name))},label={Text(name)})}}
                }
                OutlinedTextField(config.cloudVoice,{change(config.copy(cloudVoice=it.take(200)))},label={Text("Голос API")},supportingText={Text("Можно указать другой голос, поддерживаемый вашим сервером.")},modifier=Modifier.fillMaxWidth())
            }
            Text("Скорость: %.1f×".format(config.speed))
            Slider(config.speed,{change(config.copy(speed=it))},valueRange=.5f..2f)
            OutlinedButton(onClick={graph.voice.read(-1,"Привет! Я Люма. Давай поговорим.")},enabled=!session.active,modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.VolumeUp,null);Spacer(Modifier.width(8.dp));Text("Послушать голос")}
            Text("Озвучку создаёт синтезатор речи, а не человек.",style=MaterialTheme.typography.labelSmall)
        }
        if(config.recognition=="cloud" || config.synthesis=="cloud") SoftCard {
            Text("Речевой API",style=MaterialTheme.typography.titleMedium)
            Text("Отдельно от модели чата. Совместимый сервер должен поддерживать /audio/transcriptions и /audio/speech. Возможна отдельная оплата у провайдера.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(config.endpoint,{change(config.copy(endpoint=it.take(2000)))},label={Text("Адрес API, включая /v1")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(config.key,{change(config.copy(key=it.take(4000)))},label={Text("Ключ речевого API")},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
            OutlinedTextField(config.sttModel,{change(config.copy(sttModel=it.take(200)))},label={Text("Модель распознавания")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(config.ttsModel,{change(config.copy(ttsModel=it.take(200)))},label={Text("Модель озвучки")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        }
        SoftCard {
            Text("Разговор с Luma",style=MaterialTheme.typography.titleMedium)
            Text("Подходит для любой подключённой модели. После ответа снова слушаю, пока вы не завершите разговор. Во время озвучки микрофон приостановлен. Голос не снимает блокировку телефона.",style=MaterialTheme.typography.bodySmall)
            VoiceStart(graph,"conversation","Начать разговор",Icons.Outlined.Headphones)
            VoiceStart(graph,"wake","Слушать «Люма» в фоне",Icons.Outlined.Hearing)
            if(session.active) FilledTonalButton(onClick={graph.voice.stop()},modifier=Modifier.fillMaxWidth()) {Text("Остановить голос")}
            Text("Для работы на заблокированном экране сначала включите прослушивание здесь. Энергосбережение производителя может останавливать службу; исключение настраивается в разделе «Доступ».",style=MaterialTheme.typography.bodySmall)
        }
    }
}
