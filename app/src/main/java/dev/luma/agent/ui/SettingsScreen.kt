@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.BuildConfig
import dev.luma.agent.core.*
import kotlin.math.roundToInt

@Composable fun SettingsScreen(graph: AppGraph, openAccess: () -> Unit = {}, openModels: () -> Unit = {}) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val busy by graph.chatBusy.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("") }
    BackHandler(page.isNotEmpty()) { page="" }
    val visuals=settings.visuals
    fun visual(change: (VisualSettings)->VisualSettings) { graph.update { it.copy(visuals=change(it.visuals).sanitized()) } }
    val provider=settings.provider;val model=settings.connection().model;val parameters=settings.connection().generation()
    fun generation(change: (GenerationSettings)->GenerationSettings) { graph.update { it.withGeneration(provider,model,change) } }
    val reduced=LocalReducedMotion.current
    AnimatedContent(page,transitionSpec={fadeIn(tween(if(reduced) 0 else 280)) togetherWith fadeOut(tween(if(reduced) 0 else 180))},label="settings page") {visiblePage ->
    MotionLazyColumn(Modifier.fillMaxSize().screenMotionBlur(transition).testTag("settings-list"), contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            if(visiblePage.isNotEmpty()) TextButton(onClick={page=""}) { Icon(Icons.Outlined.ArrowBack,null);Spacer(Modifier.width(8.dp));Text(tr("Все настройки")) }
            PageTitle(tr("НАСТРОЙКИ"),when(visiblePage) {"theme"->tr("Оформление");"effects"->tr("Эффекты");"model"->tr("Параметры модели");"prompt"->tr("Системный промпт");"agent"->tr("Управление");"search"->tr("Поиск в интернете");"voice"->tr("Голос");else->tr("Общие")},
                when(visiblePage) {"theme"->tr("Тема, цвета и показ скорости генерации.");"effects"->tr("Анимации, сфера и свечение во время работы агента.");"model"->tr("Сохраняются отдельно для каждой модели.");"prompt"->tr("Инструкции для выбранной модели.");"agent"->tr("Доступ, подтверждения и пределы задачи.");"search"->tr("Как модели ищут в интернете.");"voice"->tr("Вызов, диктовка, разговор и озвучка.");else->tr("Внешний вид, модель и работа агента.")})
        }
        when(visiblePage) {
            "voice" -> { item { VoiceSettingsContent(graph) } }
            "" -> {
                item { SettingsLink(tr("Оформление"),tr("Тема, палитра и скорость генерации"),Icons.Outlined.Palette) {page="theme"} }
                item { SettingsLink(tr("Голос"),tr("Диктовка, разговор и вызов «Люма»"),Icons.Outlined.Mic) {page="voice"} }
                item { SettingsLink(tr("Эффекты"),tr("Motion blur, частицы и плавный ввод"),Icons.Outlined.AutoAwesome) {page="effects"} }
                item { SettingsLink(tr("Параметры модели"),if(model.isBlank()) tr("Сначала подключи модель") else "${provider.label} · $model",Icons.Outlined.Tune) {page="model"} }
                item { SettingsLink(tr("Управление"),tr("Курсор, подтверждения и лимиты"),Icons.Outlined.TouchApp) {page="agent"} }
                item { SettingsLink(tr("Поиск в интернете"),if(!settings.webSearch) tr("Выключен") else if(settings.search.native(provider)) tr("{0} ищет сам" ,provider.label) else tr("Через {0}" ,WebSearchSettings.label(settings.search.engine)),Icons.Outlined.TravelExplore) {page="search"} }
                item { SoftCard {
                    Icon(Icons.Outlined.Lock,null,tint=MaterialTheme.colorScheme.primary)
                    Text(tr("Данные остаются на телефоне"),style=MaterialTheme.typography.titleMedium)
                    Text(tr("Ключи зашифрованы Android Keystore. История хранится локально. Сообщения, результаты действий и разрешённые снимки передаются выбранному провайдеру только для выполнения запросов."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { TextButton(onClick={graph.update {it.copy(onboarded=false)}},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.School,null);Spacer(Modifier.width(8.dp));Text(tr("Пройти знакомство ещё раз"))} }
                item { Text("Luma · ${BuildConfig.VERSION_NAME}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            "theme" -> {
                item { SoftCard {
                    Text(tr("Язык интерфейса"),style=MaterialTheme.typography.titleMedium)
                    var expanded by remember {mutableStateOf(false)}
                    val languages=listOf("system" to tr("Системный"),"ru" to tr("Русский"),"en" to "English","zh" to "中文","de" to "Deutsch","fr" to "Français")
                    Box {
                        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().testTag("ui-language")) {Text(languages.first {it.first==settings.language}.second);Icon(Icons.Outlined.ArrowDropDown,null)}
                        DropdownMenu(expanded,{expanded=false}) {languages.forEach {(code,label)->
                            DropdownMenuItem(text={Text(label)},onClick={expanded=false;graph.update {it.copy(language=code)}})
                        }}
                    }
                } }
                item { SoftCard {
                    Text(tr("Режим темы"),style=MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf("system" to tr("Авто"),"light" to tr("Светлая"),"dark" to tr("Тёмная")).forEach {(id,label)->FilterChip(settings.theme==id,{graph.update {it.copy(theme=id)}},label={Text(label)})}}
                    SettingToggle(tr("Цвета обоев"),tr("Палитра Android 12 и новее"),settings.dynamicColors) {v->graph.update {it.copy(dynamicColors=v)}}
                    SettingToggle(tr("Чёрный фон"),tr("В тёмной теме"),visuals.blackBackground) {v->visual {it.copy(blackBackground=v)}}
                } }
                item { SoftCard {
                    Text(tr("Чат"),style=MaterialTheme.typography.titleMedium)
                    SettingToggle(tr("Скорость генерации"),tr("Токены в секунду на вход и выход: в шапке чата, в карточке агента и на кнопке «Стоп»"),settings.tokenMeter) {v->graph.update {it.copy(tokenMeter=v)}}
                    SettingSlider(tr("Размер текста в чате"),settings.chatTextScale,.85f..1.4f,"${(settings.chatTextScale*100).roundToInt()} %",steps=10) {v->graph.update {it.copy(chatTextScale=(v*20).roundToInt()/20f)}}
                    SettingToggle(tr("Вибрация"),tr("Короткий отклик при отправке, подтверждении и завершении задачи"),settings.haptics) {v->graph.update {it.copy(haptics=v)}}
                } }
                item { SoftCard {
                    Text(tr("Тон общения"),style=MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!settings.formal,{graph.update {it.copy(tone="friendly")}},SegmentedButtonDefaults.itemShape(0,2),Modifier.testTag("tone-friendly")) {Text(tr("Дружеский"),maxLines=1)}
                        SegmentedButton(settings.formal,{graph.update {it.copy(tone="formal")}},SegmentedButtonDefaults.itemShape(1,2),Modifier.testTag("tone-formal")) {Text(tr("Официальный"),maxLines=1)}
                    }
                    Text(if(settings.formal) tr("Luma обращается на «вы», спокойно и по делу — в подсказках и в ответах модели.") else tr("Luma обращается на «ты», по-дружески — в подсказках и в ответах модели."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { SoftCard {
                    Text(tr("Палитра Luma"),style=MaterialTheme.typography.titleMedium)
                    Text(tr("Выбор палитры отключит цвета обоев."),style=MaterialTheme.typography.bodySmall)
                    listOf(Triple("sky",tr("Ледяной голубой"),0xff95c9ff),Triple("lilac",tr("Лаванда"),0xffc6aaff),Triple("mint",tr("Мята"),0xff8edac5),Triple("peach",tr("Персик"),0xffffb59a),
                        Triple("violet",tr("Электрический фиолетовый"),0xffa450ff),Triple("cobalt",tr("Кобальт"),0xff3978f6),
                        Triple("rose",tr("Розовый"),0xffee85ac),Triple("teal",tr("Бирюза"),0xff00bea6),
                        Triple("citrus",tr("Лимонный"),0xfff8df58),Triple("orange",tr("Мандарин"),0xfff2652c),Triple("graphite",tr("Графит"),0xffbcb6b1)).forEach {(id,label,color)->
                        FilterChip(!settings.dynamicColors && visuals.palette==id,onClick={graph.update {it.copy(dynamicColors=false,visuals=it.visuals.copy(palette=id))}},label={Text(label)},leadingIcon={Box(Modifier.size(16.dp).background(Color(color),CircleShape))},modifier=Modifier.fillMaxWidth())
                    }
                } }
            }
            "effects" -> {
                item { SoftCard {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {LumaOrb(Modifier.size(96.dp));Column(Modifier.weight(1f)) {Text(tr("Сфера"),style=MaterialTheme.typography.titleMedium);Text(tr("Так она выглядит на пустом экране чата."),style=MaterialTheme.typography.bodySmall)}}
                    SettingToggle(tr("Меньше анимаций"),tr("Неподвижная сфера и мгновенные переходы"),settings.reducedMotion) {v->graph.update {it.copy(reducedMotion=v)}}
                    SettingToggle("Motion blur",tr("Мягкое размытие при прокрутке и смене экранов"),visuals.motionBlur) {v->visual {it.copy(motionBlur=v)}}
                    if(visuals.motionBlur) SettingSlider(tr("Сила размытия"),visuals.blurStrength,.1f..1f,"${(visuals.blurStrength*100).roundToInt()} %") {v->visual {it.copy(blurStrength=v)}}
                    Text(tr("Эффект работает при движении списков и переходах Luma. На Android 8–11 используется многослойное размытие. «Меньше анимаций» отключает движение и размытие."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { SoftCard {
                    Text(tr("Сфера и орбиты"),style=MaterialTheme.typography.titleMedium)
                    SettingSlider(tr("Количество частиц"),visuals.particleCount.toFloat(),36f..180f,visuals.particleCount.toString(),steps=3) {v->visual {it.copy(particleCount=v.roundToInt())}}
                    SettingSlider(tr("Скорость орбит"),visuals.orbitSpeed,.4f..2f,"%.1f×".format(visuals.orbitSpeed)) {v->visual {it.copy(orbitSpeed=v)}}
                    SettingSlider(tr("Возврат после вращения"),visuals.returnDelay,2f..6f,tr("%.0f с").format(visuals.returnDelay)) {v->visual {it.copy(returnDelay=v)}}
                    Text(tr("Сферу на пустом экране чата можно вращать пальцем. Если удерживать её около 7 секунд, орбиты расширяются и частицы ускоряются."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("Во время работы агента частицы следуют за курсором."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { SoftCard {
                    Text(tr("Во время управления"),style=MaterialTheme.typography.titleMedium)
                    SettingToggle(tr("Шарик-курсор"),tr("Показывать точку следующего действия"),visuals.cursor) {v->visual {it.copy(cursor=v)}}
                    SettingToggle(tr("Свечение краёв"),tr("Цветная рамка по краям экрана во время задачи"),visuals.edgeGlow) {v->visual {it.copy(edgeGlow=v)}}
                    if(visuals.edgeGlow) SettingSlider(tr("Яркость свечения"),visuals.glowIntensity,.2f..1f,"${(visuals.glowIntensity*100).roundToInt()} %") {v->visual {it.copy(glowIntensity=v)}}
                    Text(tr("Кнопка остановки остаётся доступной при любых настройках эффектов."),style=MaterialTheme.typography.bodySmall)
                } }
                item { TextButton(onClick={visual {VisualSettings(palette=it.palette,blackBackground=it.blackBackground)}}) {Text(tr("Сбросить эффекты"))} }
            }
            "model" -> {
                item { SoftCard {
                    Text(tr(provider.label),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                    Text(model.ifBlank {tr("Модель не подключена")},style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick=openModels) {Text(tr("Выбрать модель или провайдера"))}
                } }
                if(model.isNotBlank()) {
                    item { SoftCard {
                        Text(tr("Размер ответа"),style=MaterialTheme.typography.titleMedium)
                        val known=settings.connection().models.firstOrNull {it.id==model}?.outputLimit?.takeIf {it>0}
                        SettingToggle(tr("Авто / максимум модели"),if(known!=null) tr("Без ограничения Luma. Модель отвечает до {0}" ,tokenLabel(known)) else tr("Без ограничения Luma. Предел задают модель и сервер"),parameters.maxTokens==0) {v->
                            generation {it.copy(maxTokens=if(v) 0 else minOf(known ?: 8192,8192))}}
                        if(parameters.maxTokens>0) {
                            // Presets from 256 up to the model's own maximum (or 128K when the provider does not publish it).
                            val steps=(listOf(256,512,1024,2048,4096,8192,16_384,32_768,65_536,131_072).filter {known==null || it<known} + listOfNotNull(known)).distinct()
                            val at=steps.indexOfFirst {it>=parameters.maxTokens}.let {if(it<0) steps.lastIndex else it}
                            SettingSlider(tr("Свой лимит ответа"),at.toFloat(),0f..steps.lastIndex.toFloat(),tokenLabel(steps[at]),steps=(steps.size-2).coerceAtLeast(0)) {v->generation {it.copy(maxTokens=steps[v.roundToInt().coerceIn(0,steps.lastIndex)])}}
                        }
                        SettingSlider(tr("Контекст чата"),parameters.contextTurns.toFloat(),2f..30f,tr("{0} сообщений" ,parameters.contextTurns),steps=13) {v->generation {it.copy(contextTurns=v.roundToInt())}}
                        Text(tr("«Авто» значит, что Luma не добавляет своего ограничения. Ответ всё равно конечен: его ограничивают максимальный ответ модели, контекстное окно и квоты провайдера или сервера. ") +
                            (when(provider) {Provider.CLAUDE->tr("Claude требует явный лимит — Luma отправляет максимум выбранной модели.");Provider.DEVICE->tr("На телефоне «Авто» даёт до 1024 новых токенов в пределах контекста. Для более длинного ответа выберите явный лимит.");Provider.LOCAL->tr("Локальный сервер сам решает, где остановиться; у Ollama размер контекста задаётся на сервере (OLLAMA_CONTEXT_LENGTH или num_ctx в Modelfile).");else->tr("Параметр лимита не отправляется, и провайдер использует свой предел.")}),
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tr("Лимит ответа — это не бюджет токенов задачи и не лимит шагов агента: они настраиваются в «Агент» → «Пределы задачи». Лимит ответа и системный промпт применяются к чату и агенту, контекст — к чату. Активная задача использует параметры, с которыми запущена."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    item { SoftCard {
                        Text(tr("Стиль ответа"),style=MaterialTheme.typography.titleMedium)
                        if(ProviderClient.supportsSampling(provider,model)) {
                            SettingToggle(tr("Своя температура"),tr("От точных ответов к более творческим"),parameters.temperature>=0) {v->generation {it.copy(temperature=if(v) .7f else -1f)}}
                            if(parameters.temperature>=0) SettingSlider(tr("Температура"),parameters.temperature,0f..1f,"%.2f".format(parameters.temperature)) {v->generation {it.copy(temperature=v)}}
                            SettingToggle(tr("Свой Top P"),tr("Дополнительный контроль разнообразия"),parameters.topP>=0) {v->generation {it.copy(topP=if(v) .9f else -1f)}}
                            if(parameters.topP>=0) SettingSlider("Top P",parameters.topP,.05f..1f,"%.2f".format(parameters.topP)) {v->generation {it.copy(topP=v)}}
                            if(provider==Provider.CLAUDE) Text(tr("У Claude при включённой температуре Top P не отправляется."),style=MaterialTheme.typography.bodySmall)
                        } else Text(tr("Эта reasoning-модель управляет сэмплированием сама. Температура и Top P не отправляются."),style=MaterialTheme.typography.bodyMedium)
                        Text(tr("По умолчанию используются параметры провайдера. В режиме агента пользовательское сэмплирование отключено."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    item { ModelInstructions(provider,model,parameters.instruction) {value->generation {it.copy(instruction=value)}} }
                    item { TextButton(onClick={generation {GenerationSettings()}}) {Text(tr("Сбросить параметры этой модели"))} }
                }
                item { BlockedWordsCard(settings.blockedWords) {value->graph.update {it.copy(blockedWords=value)}} }
            }
            "agent" -> {
                item { SoftCard {
                    AgentAccessMode(settings.confirmActions,enabled=!busy && !run.active) {ask->graph.update {it.copy(confirmActions=ask)}}
                    Text(tr("В полном доступе Luma выполняет порученную задачу без дополнительных подтверждений. Разрешения и системные окна Android остаются под вашим контролем."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingToggle(tr("Плавающая панель"),tr("Разворачиваемая панель шагов; кнопка «Стоп» доступна всегда"),settings.panel) {v->graph.update {it.copy(panel=v)}}
                    TextButton(onClick=openAccess) {Icon(Icons.Outlined.Security,null);Spacer(Modifier.width(8.dp));Text(tr("Разрешения и папка для файлов"))}
                } }
                item { SoftCard {
                    SettingToggle(tr("Глубокие размышления"),tr("Если шаг не удался, агент разбирает почему и пробует другой способ, а не повторяет то же"),settings.deepThinking) {v->graph.update {it.copy(deepThinking=v)}}
                    Text(tr("Пример: нажатие на ссылку в Telegram открыло меню сообщения — агент не жмёт снова, а открывает ссылку через браузер или копирует её. Каждый разбор — ещё один запрос к модели, до шести за задачу. То же действие на том же экране повторно не выполняется."),
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { SoftCard {
                    Text(tr("Вызов инструментов"),style=MaterialTheme.typography.titleMedium)
                    val modes=listOf("auto" to tr("Авто"),"native" to tr("Функции"),"json" to "JSON")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed {i,(value,title) -> SegmentedButton(settings.toolCalling==value,{graph.update {it.copy(toolCalling=value)}},SegmentedButtonDefaults.itemShape(i,modes.size),Modifier.testTag("tool-calling-$value"),enabled=!run.active) {Text(title,maxLines=1)}}
                    }
                    Text(when(settings.toolCalling) {
                        "native"->tr("Только встроенный вызов функций провайдера. Если сервер его не поддерживает, задача остановится с ошибкой.")
                        "json"->tr("Модель отвечает JSON-командами. Подходит для локальных моделей без поддержки инструментов.")
                        else->tr("Встроенный вызов функций, если сервер его поддерживает; иначе Luma сама переходит на JSON-команды.")
                    },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { SoftCard {
                    Text(tr("Пределы задачи"),style=MaterialTheme.typography.titleMedium)
                    SettingToggle(tr("Без лимита шагов"),tr("До завершения задачи, остановки или ограничения API. При повторениях без прогресса — пауза."),settings.unlimitedAgent) {v->graph.update {it.copy(unlimitedAgent=v)}}
                    if (!settings.unlimitedAgent) {
                    SettingSlider(tr("Максимум шагов"),settings.maxSteps.toFloat(),5f..30f,settings.maxSteps.toString(),steps=24) {v->graph.update {it.copy(maxSteps=v.roundToInt())}}
                    SettingSlider(tr("Время выполнения"),settings.taskMinutes.toFloat(),2f..15f,tr("{0} мин" ,settings.taskMinutes),steps=12) {v->graph.update {it.copy(taskMinutes=v.roundToInt())}}
                    }
                    // Token limit per task: off, then 50k … 2M. Reaching it pauses the agent.
                    val limits=listOf(0,50,100,200,500,1000,2000)
                    val at=limits.indexOfFirst {it>=settings.agentTokenLimit}.let {if(it<0) limits.lastIndex else it}
                    SettingSlider(tr("Лимит токенов на задачу"),at.toFloat(),0f..limits.lastIndex.toFloat(),if(limits[at]==0) tr("без лимита") else if(limits[at]>=1000) tr("{0} млн" ,limits[at]/1000) else tr("{0} тыс." ,limits[at]),steps=limits.size-2) {v->graph.update {it.copy(agentTokenLimit=limits[v.roundToInt().coerceIn(0,limits.lastIndex)])}}
                    Text(tr("Когда задача потратит столько токенов, агент встанет на паузу и спросит, продолжать ли."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("В API передаются задача, заметки, последние 10 действий и текущий результат. Скриншоты — по запросу модели, если разрешены в разделе доступа."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("Новые лимиты применяются при следующем запуске. Задачу можно остановить раньше."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            "search" -> item { SearchSettingsCards(graph) }
        }
    }
    }
}
@Composable private fun SettingsLink(title: String,subtitle: String,icon: ImageVector,click: ()->Unit) {
    Surface(onClick=click,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().appear()) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Icon(Icons.Outlined.ChevronRight,null)
        }
    }
}
private fun tokenLabel(tokens: Int): String = when {
    tokens>=1_000_000 -> tr("{0} млн токенов" ,tokens/1_000_000)
    tokens>=1024 && tokens%1024==0 -> tr("{0}K токенов" ,tokens/1024)
    tokens>=1000 -> tr("{0} тыс. токенов" ,"%.1f".format(java.util.Locale.ROOT,tokens/1000.0).removeSuffix(".0").replace('.',','))
    else -> tr("{0} токенов" ,tokens)
}
@Composable private fun SettingToggle(title: String,subtitle: String,checked: Boolean,change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.bodyLarge);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(checked,change,modifier=Modifier.testTag("toggle-$title"),thumbContent=if(checked) {{Icon(Icons.Outlined.Check,null,Modifier.size(14.dp))}} else null)
    }
}
@Composable private fun SettingSlider(title: String,value: Float,range: ClosedFloatingPointRange<Float>,label: String,steps: Int=0,change: (Float)->Unit) {
    var draft by remember(value) {mutableFloatStateOf(value.coerceIn(range))}
    Column {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
        Slider(value=draft,onValueChange={draft=it},onValueChangeFinished={change(draft)},valueRange=range,steps=steps)
    }
}
@Composable private fun BlockedWordsCard(saved: String,save: (String)->Unit) {
    var draft by rememberSaveable(saved,stateSaver=TextFieldValue.Saver) {mutableStateOf(TextFieldValue(saved))}
    SoftCard {
        Text(tr("Запрещённые слова"),style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(draft,{if(it.text.length<=BlockedWords.MAX_LENGTH) draft=it},Modifier.fillMaxWidth().testTag("blocked-words"),placeholder={Text(tr("Например: оплатить, перевести, удалить аккаунт"))},minLines=2,maxLines=6)
        FilledTonalButton(onClick={save(draft.text)},enabled=draft.text!=saved) {Text(tr("Сохранить слова"))}
        val count=BlockedWords.parse(draft.text).size
        Text(if(count==0) tr("Список пуст: в полном доступе подтверждаются только удаление, перемещение и перезапись файлов.") else tr("Слов в списке: {0}." ,count),style=MaterialTheme.typography.bodySmall)
        Text(tr("Через запятую или с новой строки, регистр не важен. Если слово есть в подписи нажимаемого элемента или во вводимом тексте, агент спросит разрешение даже в полном доступе. Действует для всех моделей."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ModelInstructions(provider: Provider,model: String,saved: String,save: (String)->Unit) {
    var draft by rememberSaveable(provider,model,saved,stateSaver=TextFieldValue.Saver) {mutableStateOf(TextFieldValue(saved))}
    SoftCard {
        Text(tr("Системный промпт"),style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(draft,{if(it.text.length<=4000) draft=it},Modifier.fillMaxWidth().testTag("model-instructions"),placeholder={Text(tr("Например: отвечай кратко и с примерами"))},minLines=3,maxLines=6,visualTransformation=smoothInput(draft))
        FilledTonalButton(onClick={save(draft.text)},enabled=draft.text!=saved) {Text(tr("Сохранить промпт"))}
        Text(tr("Стиль общения и дополнительные правила для чата и агента. Применяются со следующего сообщения или новой задачи. Сохраняются отдельно для каждой модели."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
