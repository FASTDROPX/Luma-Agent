@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent.ui

import dev.luma.agent.core.tr

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
        else graph.uiMessage.value=tr("Без микрофона голосовой режим недоступен. Разрешите его в настройках доступа.")
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
    if(explain) AlertDialog(onDismissRequest={explain=false},icon={Icon(Icons.Outlined.Mic,null)},title={Text(tr("Слушать вызов в фоне?"))},
        text={Text(tr("Микрофон останется включённым, в том числе после блокировки экрана. Вызов распознаётся на телефоне; в облачном режиме речь после вызова отправляется выбранному речевому API. Уведомление позволит остановить прослушивание. После перезапуска телефона нужно включить его снова."))},
        confirmButton={TextButton(onClick={explain=false;request()}) {Text(tr("Включить"))}},dismissButton={TextButton(onClick={explain=false}) {Text(tr("Отмена"))}})
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
            Column(Modifier.weight(1f)) {Text(tr(voice.phase),style=MaterialTheme.typography.titleSmall)
                if(voice.partial.isNotBlank()) Text(voice.partial.take(200),maxLines=2,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(if(voice.mode=="dictation") tr("Скажите текст для сообщения") else tr("«Заверши разговор» или кнопка остановки"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(voice.mode in setOf("conversation","wake")) IconButton(onClick={graph.voice.screen.value=true}) {Icon(Icons.Outlined.OpenInFull,tr("Разговор с Luma"))}
            if(voice.mode=="dictation") IconButton(onClick={graph.voice.finishDictation()}) {Icon(Icons.Outlined.Check,tr("Завершить диктовку"))}
            IconButton(onClick={graph.voice.stop()},modifier=Modifier.testTag("voice-stop")) {Icon(Icons.Outlined.StopCircle,tr("Остановить голос"))}
        }
    }
}

@Composable fun SpeechLanguageMenu(value: String,onChange: (String)->Unit,enabled: Boolean=true) {
    var expanded by remember {mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Language,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
            Text(dev.luma.agent.core.SpeechLanguages.get(value).name,Modifier.weight(1f));Icon(Icons.Outlined.ArrowDropDown,null)
        }
        DropdownMenu(expanded,{expanded=false}) {dev.luma.agent.core.SpeechLanguages.all.forEach {language ->
            DropdownMenuItem(text={Text(language.name)},onClick={expanded=false;onChange(language.code)})
        }}
    }
}

@Composable fun VoiceOutputChoices(graph: AppGraph) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val installed by graph.voice.packs.state.collectAsStateWithLifecycle()
    val config=settings.voice
    fun change(value: VoiceSettings) {graph.update {it.copy(voice=value)}}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(tr("Язык озвучки"),style=MaterialTheme.typography.titleSmall)
        SpeechLanguageMenu(config.outputLanguage,{change(config.copy(outputLanguage=it,deviceVoice=""))})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(config.gender=="female",{change(config.copy(gender="female",cloudVoice="coral"))},label={Text(tr("Женский"))})
            FilterChip(config.gender=="male",{change(config.copy(gender="male",cloudVoice="onyx"))},label={Text(tr("Мужской"))})
        }
        if(config.synthesis=="piper") {
            val pack=dev.luma.agent.core.SpeechPacks.selected(config)
            val ready=remember(installed.revision,pack) {graph.voice.packs.ready(pack)}
            Text(tr("{0} · {1} МБ" ,pack.name,pack.size/1_000_000),style=MaterialTheme.typography.bodyMedium)
            if(installed.busy && installed.id==pack.id) {
                LumaProgress(Modifier.fillMaxWidth(),progress={installed.progress})
                Text(if(installed.progress>=1f) tr("Проверяю и распаковываю") else "${(installed.progress*100).toInt()} %",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={graph.voice.packs.cancel()}) {Text(tr("Отменить загрузку"))}
            } else if(ready) Text(tr("Голос установлен"),color=MaterialTheme.colorScheme.primary)
            else Button(onClick={graph.voice.packs.download(pack)},enabled=!installed.busy,modifier=Modifier.fillMaxWidth().testTag("download-speech-pack")) {
                Icon(Icons.Outlined.Download,null);Spacer(Modifier.width(8.dp));Text(tr("Скачать голос"))
            }
            installed.error?.takeIf {installed.id==pack.id}?.let {Text(tr(it),color=MaterialTheme.colorScheme.error)}
            Text(tr("Piper · голос работает без интернета. Лицензии и авторы — в руководстве голосовых пакетов."),style=MaterialTheme.typography.labelSmall)
        } else if(config.synthesis=="local") {
            Text(tr("Пол голоса зависит от движка Android. Для точного выбора используйте голоса Luma."),style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun VoiceSettingsContent(graph: AppGraph) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val download by graph.voice.model.state.collectAsStateWithLifecycle()
    val voices by graph.voice.output.voices.collectAsStateWithLifecycle()
    val session by graph.voice.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val config=settings.voice
    fun change(value: VoiceSettings) {graph.update {it.copy(voice=value)}}
    val zipPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->if(uri!=null) {graph.voice.stop();graph.voice.model.importZip(uri)}}
    LaunchedEffect(Unit) {graph.voice.output.loadVoices()}
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        SoftCard {
            Text(tr("Язык распознавания"),style=MaterialTheme.typography.titleMedium)
            SpeechLanguageMenu(config.language,{code ->
                graph.voice.model.select(code)
                change(config.copy(language=code,wakeWords=dev.luma.agent.core.SpeechLanguages.get(code).aliases))
            },enabled=!session.active && !download.busy)
            Text(tr("Vosk · {0} МБ. Для диктовки и вызова Luma. Каждый язык скачивается отдельно." ,graph.voice.model.spec.size/1_000_000),style=MaterialTheme.typography.bodySmall)
            if(download.busy) {LumaProgress(Modifier.fillMaxWidth(),progress={download.progress});Text("${download.phase} · ${(download.progress*100).toInt()} %");TextButton(onClick={graph.voice.model.cancel()}) {Text(tr("Отменить загрузку"))}}
            else if(download.ready) Text(tr("Модель установлена"),color=MaterialTheme.colorScheme.primary)
            else Button(onClick={graph.voice.model.download()},enabled=!session.active,modifier=Modifier.testTag("download-voice-model")) {Icon(Icons.Outlined.Download,null);Spacer(Modifier.width(8.dp));Text(tr("Скачать язык"))}
            if(!download.busy && !session.active) TextButton(onClick={zipPicker.launch(arrayOf("application/zip","application/octet-stream"))}) {Icon(Icons.Outlined.FolderZip,null);Spacer(Modifier.width(8.dp));Text(tr("Установить модель из ZIP"))}
            download.error?.let {Text(tr(it),color=MaterialTheme.colorScheme.error)}
            Text("Vosk / Alpha Cephei · Apache 2.0",style=MaterialTheme.typography.labelSmall)
        }
        SoftCard {
            Text(tr("Распознавание речи"),style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(config.recognition=="local",{change(config.copy(recognition="local"))},label={Text(tr("На телефоне"))},enabled=!session.active)
                FilterChip(config.recognition=="cloud",{change(config.copy(recognition="cloud"))},label={Text(tr("Через API"))},enabled=!session.active)
            }
            Text(tr("Диктовка заполняет поле сообщения. В разговоре фразы отправляются выбранной модели автоматически после паузы."),style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(config.wakeWords,{change(config.copy(wakeWords=it.take(500)))},label={Text(tr("Фразы вызова через запятую"))},enabled=!session.active,modifier=Modifier.fillMaxWidth())
        }
        SoftCard {
            Text(tr("Озвучка ответов"),style=MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("piper" to "Luma","local" to "Android","cloud" to "API").forEachIndexed {index,(id,label) ->
                    SegmentedButton(config.synthesis==id,{change(config.copy(synthesis=id))},SegmentedButtonDefaults.itemShape(index,3)) {Text(label)}
                }
            }
            VoiceOutputChoices(graph)
            if(config.synthesis=="local") {
                var expanded by remember {mutableStateOf(false)}
                Box {
                    OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {Text(voices.firstOrNull {it.id==config.deviceVoice}?.label ?: tr("Офлайн-голос Android"),maxLines=2);Icon(Icons.Outlined.ArrowDropDown,null)}
                    DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=340.dp)) {
                        DropdownMenuItem(text={Text(tr("По умолчанию"))},onClick={expanded=false;change(config.copy(deviceVoice=""))})
                        voices.filter {it.language==config.outputLanguage}.take(100).forEach {v->DropdownMenuItem(text={Text(v.label)},onClick={expanded=false;change(config.copy(deviceVoice=v.id))})}
                    }
                }
                TextButton(onClick={runCatching {context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}}) {Text(tr("Установить голоса в Android"))}
            } else if(config.synthesis=="cloud") {
                OutlinedTextField(config.cloudVoice,{change(config.copy(cloudVoice=it.take(200)))},label={Text(tr("Голос API"))},supportingText={Text(tr("Можно указать другой голос, поддерживаемый вашим сервером."))},modifier=Modifier.fillMaxWidth())
            }
            Text(tr("Скорость: %.1f×").format(config.speed))
            Slider(config.speed,{change(config.copy(speed=it))},valueRange=.5f..2f)
            OutlinedButton(onClick={graph.voice.read(-1,dev.luma.agent.core.SpeechLanguages.get(config.outputLanguage).sample)},enabled=!session.active,modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.VolumeUp,null);Spacer(Modifier.width(8.dp));Text(tr("Послушать голос"))}
            Text(tr("Озвучку создаёт синтезатор речи, а не человек."),style=MaterialTheme.typography.labelSmall)
        }
        if(config.recognition=="cloud" || config.synthesis=="cloud") SoftCard {
            Text(tr("Речевой API"),style=MaterialTheme.typography.titleMedium)
            Text(tr("Отдельно от модели чата. Совместимый сервер должен поддерживать /audio/transcriptions и /audio/speech. Возможна отдельная оплата у провайдера."),style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(config.endpoint,{change(config.copy(endpoint=it.take(2000)))},label={Text(tr("Адрес API, включая /v1"))},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(config.key,{change(config.copy(key=it.take(4000)))},label={Text(tr("Ключ речевого API"))},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
            OutlinedTextField(config.sttModel,{change(config.copy(sttModel=it.take(200)))},label={Text(tr("Модель распознавания"))},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(config.ttsModel,{change(config.copy(ttsModel=it.take(200)))},label={Text(tr("Модель озвучки"))},modifier=Modifier.fillMaxWidth(),singleLine=true)
        }
        SoftCard {
            Text(tr("Разговор с Luma"),style=MaterialTheme.typography.titleMedium)
            Text(tr("Говорите, слушайте ответ и продолжайте разговор. Переключатель «Агент» позволяет выполнять действия на телефоне. Во время ответа микрофон приостановлен."),style=MaterialTheme.typography.bodySmall)
            VoiceStart(graph,"conversation",tr("Начать разговор"),Icons.Outlined.Headphones)
            VoiceStart(graph,"wake",tr("Слушать «Люма» в фоне"),Icons.Outlined.Hearing)
            if(session.active) FilledTonalButton(onClick={graph.voice.stop()},modifier=Modifier.fillMaxWidth()) {Text(tr("Остановить голос"))}
        }
    }
}
