package dev.luma.agent.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.OrbAnchor
import dev.luma.agent.CursorSeed
import dev.luma.agent.core.Attachment
import dev.luma.agent.core.AttachmentStore
import dev.luma.agent.core.safeError
import dev.luma.agent.core.ChatMessage
import dev.luma.agent.core.RunState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.roundToInt

@Composable fun ChatScreen(graph: AppGraph,openConnections: ()->Unit,openAccess: ()->Unit) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val messages by graph.chats.messages.collectAsStateWithLifecycle()
    val selected by graph.chats.selected.collectAsStateWithLifecycle()
    val busy by graph.chatBusy.collectAsStateWithLifecycle()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val overlayVisible by graph.overlayVisible.collectAsStateWithLifecycle()
    val chatStartedAt by graph.chatStartedAt.collectAsStateWithLifecycle()
    val tokenStats by graph.api.stats.collectAsStateWithLifecycle()
    val reduced=LocalReducedMotion.current
    var input by rememberSaveable(selected,stateSaver=TextFieldValue.Saver) {mutableStateOf(TextFieldValue(""))}
    var files by rememberSaveable(selected,stateSaver=Saver<List<Attachment>,String>(save={Attachment.encode(it)},restore={Attachment.decode(it)})) {mutableStateOf(emptyList())}
    var importing by remember {mutableStateOf(false)}
    val wipe=typingWipe(input)
    var asAgent by rememberSaveable {mutableStateOf(false)}
    var sendTick by remember {mutableIntStateOf(0)}
    var sentPreview by remember {mutableStateOf("")}
    val sending=remember {Animatable(1f)}
    val list=rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(selected) {mutableStateOf(true)}
    val atBottom by remember {derivedStateOf {!list.canScrollForward}}
    val shouldFollow by rememberUpdatedState(follow && !dragging)
    val scrollEvents=remember {Channel<Unit>(Channel.CONFLATED)}
    val openedAt=remember {System.currentTimeMillis()}
    val revealed=remember {mutableSetOf<Long>()}
    val keyboard=LocalSoftwareKeyboardController.current
    val context=LocalContext.current
    val rootView=LocalView.current
    val scope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris ->
        val chat=graph.chats.selected.value
        if(uris.isNotEmpty()) scope.launch {
            importing=true
            try {
                for(uri in uris.take(4-files.size)) {
                    val file=graph.chats.attachments.importFile(uri)
                    if(graph.chats.selected.value==chat && files.sumOf {it.size}+file.size<=16_000_000) files=files+file
                    else {withContext(Dispatchers.IO) {graph.chats.attachments.file(file).delete()};graph.uiMessage.value="Не больше 4 файлов и 16 МБ в одном сообщении."}
                }
                if(uris.size>4) graph.uiMessage.value="В одно сообщение можно добавить не больше 4 файлов."
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {graph.uiMessage.value=safeError(e)}
            finally {importing=false}
        }
    }
    DisposableEffect(graph) {onDispose {graph.orbAnchor=null}}
    val relevantRun=run.active && run.chatId==selected
    val pending=busy && !run.active && messages.lastOrNull()?.role!="assistant"
    val latestAnswer=messages.lastOrNull {it.role=="assistant"}?.id
    var origin by remember {mutableStateOf(Offset.Zero)}
    var home by remember {mutableStateOf(Offset.Unspecified)}
    var answer by remember(selected) {mutableStateOf(Offset.Unspecified)}
    var homeSize by remember {mutableStateOf(156.dp)}
    val scene=remember(settings.visuals.particleCount,settings.visuals.orbitSpeed,settings.visuals.returnDelay) {OrbPhysics(settings.visuals.particleCount,settings.visuals.orbitSpeed,returnDelay=settings.visuals.returnDelay)}
    LaunchedEffect(dragging) {if(dragging) follow=false}
    LaunchedEffect(atBottom,dragging) {if(atBottom && !dragging) follow=true}
    LaunchedEffect(messages.size,pending,relevantRun,run.approval,follow) {scrollEvents.trySend(Unit)}
    LaunchedEffect(selected) {
        for(event in scrollEvents) {
            // AndroidView.onLayout can emit growth synchronously. Never force a second
            // Compose measure from inside that layout, including with reduced motion.
            withFrameNanos { }
            if(list.layoutInfo.totalItemsCount==0) delay(32)
            if(shouldFollow && list.layoutInfo.totalItemsCount>0) try {
                if(reduced) list.scrollToItem(list.layoutInfo.totalItemsCount-1)
                else list.animateScrollToItem(list.layoutInfo.totalItemsCount-1)
            } catch(e: CancellationException) {currentCoroutineContext().ensureActive()}
        }
    }
    LaunchedEffect(sendTick) {if(sendTick>0 && !reduced) {sending.snapTo(0f);sending.animateTo(1f,tween(430,easing=FastOutSlowInEasing))}}
    fun submit() {
        if(busy || run.active) {graph.cancelChat();graph.agent.stop();return}
        if(settings.connection().model.isBlank()) {openConnections();return}
        if(importing || (input.text.isBlank() && files.isEmpty())) return
        if(!graph.send(input.text,asAgent,files)) return
        val preview=input.text.ifBlank {files.joinToString {it.name}}
        sentPreview=preview.substring(0,revealBoundary(preview,minOf(90,preview.length)));sendTick++;follow=true
        input=TextFieldValue("");files=emptyList();keyboard?.hide()
    }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {origin=it.positionInRoot()}) {
            val density=LocalDensity.current
            val widthPx=with(density) {maxWidth.toPx()}
            val heightPx=with(density) {maxHeight.toPx()}
            val left=with(density) {34.dp.toPx()}
            if(messages.isEmpty()) {
                EmptyChat(Modifier.fillMaxSize(),settings.connection().model.isBlank(),openConnections,orbSlot={slot ->
                    Box(slot.onGloballyPositioned {c->home=c.positionInRoot()+Offset(c.size.width/2f,c.size.height/2f)-origin;homeSize=with(density) {c.size.width.toDp()}})
                }) {prompt,agent ->asAgent=agent;input=TextFieldValue(prompt,TextRange(prompt.length))}
            } else {
                MotionLazyColumn(Modifier.fillMaxSize().testTag("chat-thread"),state=list,contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    // Items only fade in: a fade-out on the list's last item could stay drawn after removal.
                    items(messages,key={"message-${it.id}"}) {message ->
                        val animate=remember(message.id) {message.role=="assistant" && message.timestamp>=openedAt && revealed.add(message.id)}
                        Box(Modifier.animateItem(fadeInSpec=if(reduced) null else tween(260),placementSpec=if(reduced) null else spring(stiffness=Spring.StiffnessMediumLow),fadeOutSpec=null)) {
                            MessageBubble(message,animate,graph.chats.attachments,onCursor={y->if(message.id==latestAnswer && !pending && !relevantRun) answer=Offset(left,y-origin.y)},onGrowth={scrollEvents.trySend(Unit)})
                        }
                    }
                    if(pending) item(key="thinking") {
                        Row(Modifier.animateItem(fadeInSpec=if(reduced) null else tween(220),placementSpec=null,fadeOutSpec=null).fillMaxWidth().heightIn(min=48.dp).padding(start=52.dp).onGloballyPositioned {answer=Offset(left,it.positionInRoot().y-origin.y+it.size.height/2f)},verticalAlignment=Alignment.CenterVertically) {
                            val elapsed=rememberElapsed(chatStartedAt)
                            Text("Готовлю ответ…",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(elapsed>=1000) Text("  ${dev.luma.agent.core.formatElapsed(elapsed)}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f))
                        }
                    }
                    if(relevantRun) item(key="agent") {
                        Box(Modifier.animateItem(fadeInSpec=if(reduced) null else tween(260),placementSpec=if(reduced) null else spring(stiffness=Spring.StiffnessMediumLow),fadeOutSpec=null).onGloballyPositioned {answer=Offset(left,it.positionInRoot().y-origin.y+with(density) {38.dp.toPx()})}) {
                            RunCard(run,approve={scope.launch {if(run.approval?.background==true) {(context as? Activity)?.moveTaskToBack(true);delay(400)};graph.agent.decide(true)}},reject={graph.agent.decide(false)},pause={graph.agent.togglePause()},stop={graph.agent.stop()},maxSteps=if(settings.unlimitedAgent) 0 else settings.maxSteps,retry={graph.agent.retryRequest()},elapsed=rememberElapsed(run.startedAt),stats=tokenStats.takeIf {settings.tokenMeter},meterActive=settings.tokenMeter)
                        }
                    }
                    item(key="tail") {Spacer(Modifier.height(4.dp))}
                }
            }
            val empty=messages.isEmpty()
            val target=if(empty) home.takeIf {it!=Offset.Unspecified} ?: Offset(widthPx/2,heightPx*.35f) else answer.takeIf {it!=Offset.Unspecified} ?: Offset(left,with(density) {56.dp.toPx()})
            val center by animateOffsetAsState(target,if(reduced) snap() else spring(dampingRatio=.88f,stiffness=240f),label="Luma follows answer")
            val diameter by animateDpAsState(if(empty) homeSize else 44.dp,if(reduced) snap() else tween(480,easing=FastOutSlowInEasing),label="Luma orbit size")
            val answerInView by remember(pending,relevantRun,latestAnswer) {derivedStateOf {pending || relevantRun || list.layoutInfo.visibleItemsInfo.any {it.key=="message-$latestAnswer"}}}
            val visible=if(empty) (maxHeight-8.dp)/density.fontScale>=230.dp else answerInView && target.y>=-20 && target.y<=heightPx+20 && (latestAnswer!=null || pending || relevantRun)
            val alpha by animateFloatAsState(if(visible) 1f else 0f,tween(if(reduced) 0 else 180),label="orb visibility")
            val px=with(density) {diameter.toPx()}
            LumaOrb(Modifier.offset {IntOffset((center.x-px/2).roundToInt(),(center.y-px/2).roundToInt())}.size(diameter)
                .onGloballyPositioned {c ->if(visible && !overlayVisible) {
                    val location=IntArray(2);rootView.getLocationOnScreen(location)
                    val at=c.positionInRoot()+Offset(c.size.width/2f,c.size.height/2f)
                    graph.orbAnchor=OrbAnchor(at.x+location[0],at.y+location[1],px*.279f,android.os.SystemClock.uptimeMillis()) {
                        scene.particles.filterIndexed {i,_->i%3==0}.map {CursorSeed(it.x,it.y,it.z,it.size,it.shade)}
                    }
                }}.graphicsLayer {this.alpha=if(overlayVisible) 0f else alpha}.testTag("chat-follow-orb"),simulation=scene,interactive=empty && visible && !overlayVisible)
            androidx.compose.animation.AnimatedVisibility(visible=!follow && messages.isNotEmpty(),modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp),
                enter=if(reduced) EnterTransition.None else fadeIn(tween(180))+scaleIn(tween(220),initialScale=.85f)+slideInVertically(tween(220)) {it/2},
                exit=if(reduced) ExitTransition.None else fadeOut(tween(140))+scaleOut(tween(160),targetScale=.9f)) {
                FilledTonalButton(onClick={follow=true;scrollEvents.trySend(Unit)}) {Icon(Icons.Outlined.ArrowDownward,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("К последнему")}
            }
            if(sendTick>0 && sending.value<1f) Surface(Modifier.align(Alignment.BottomEnd).padding(horizontal=20.dp,vertical=8.dp).widthIn(max=240.dp).graphicsLayer {this.alpha=(1f-sending.value)*.8f;translationY=-sending.value*56.dp.toPx();scaleX=1f-sending.value*.12f;scaleY=scaleX}.clearAndSetSemantics {},shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                Text(sentPreview,Modifier.padding(horizontal=16.dp,vertical=10.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            }
        }
        Surface(Modifier.padding(horizontal=12.dp,vertical=8.dp).fillMaxWidth(),shape=RoundedCornerShape(30.dp),
            color=MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=.76f),shadowElevation=6.dp,
            border=BorderStroke(.8.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))) {
            Column(Modifier.padding(horizontal=10.dp,vertical=6.dp).testTag("chat-composer")) {
                Row(Modifier.padding(start=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    FilterChip(!asAgent,{asAgent=false},label={Text("Чат")},leadingIcon={Icon(Icons.Outlined.ChatBubbleOutline,null,Modifier.size(15.dp))})
                    FilterChip(asAgent,{asAgent=true},label={Text("Агент")},leadingIcon={Icon(Icons.Outlined.Bolt,null,Modifier.size(15.dp))})
                    Spacer(Modifier.weight(1f))
                    if(asAgent) IconButton(onClick=openAccess) {Icon(Icons.Outlined.Security,"Доступ агента",Modifier.size(20.dp))}
                }
                if(files.isNotEmpty()) AttachmentStrip(files,graph.chats.attachments) {file ->
                    if(!busy && files.any {it.id==file.id}) {files=files.filterNot {it.id==file.id};scope.launch(Dispatchers.IO) {graph.chats.attachments.file(file).delete()}}
                }
                if(importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=4.dp))
                // Attach, text and send share one 44 dp line; with more lines the buttons stay at the bottom.
                Row(Modifier.fillMaxWidth().padding(vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.Bottom) {
                    IconButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!busy && !run.active && !importing && files.size<4,modifier=Modifier.size(44.dp).testTag("chat-attach")) {Icon(Icons.Outlined.Add,"Прикрепить файлы",Modifier.size(24.dp))}
                    BasicTextField(input,{if(it.text.length<=12_000) input=it else graph.uiMessage.value="Сократите сообщение до 12 000 символов."},
                        Modifier.weight(1f).heightIn(min=44.dp).padding(vertical=11.dp).then(wipe.modifier).testTag("chat-input"),
                        textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),maxLines=4,
                        visualTransformation=smoothInput(input),onTextLayout={wipe.layout=it},cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),decorationBox={field ->
                            Box {if(input.text.isEmpty()) Text(if(asAgent) "Какую задачу выполнить?" else "Напиши сообщение…",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f),maxLines=1,overflow=TextOverflow.Ellipsis);field()}
                        })
                    GradientActionButton(busy || run.active,!importing && (input.text.isNotBlank() || files.isNotEmpty()),sendTick,::submit,diameter=44.dp)
                }
                AgentAccessMode(settings.confirmActions,enabled=!busy && !run.active) {ask->graph.update {it.copy(confirmActions=ask)}}
            }
        }
    }
}

@Composable private fun EmptyChat(modifier: Modifier, needsModel: Boolean, connect: () -> Unit, orbSlot: @Composable (Modifier) -> Unit, select: (String, Boolean) -> Unit) {
    BoxWithConstraints(modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("chat-welcome")) {
        val density = LocalDensity.current
        val room = maxHeight / density.fontScale
        val icons = maxWidth / density.fontScale >= 320.dp
        val orbSize = (maxHeight * .30f).coerceAtMost(156.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            // Decorative content gives way first on a short screen or with a keyboard.
            if (room >= 230.dp) orbSlot(Modifier.size(orbSize))
            if (room >= 135.dp) Text("Привет, я Luma", Modifier.appear(1), style = MaterialTheme.typography.titleLarge)
            if (room >= 330.dp) Text("Что сделаем сегодня?", Modifier.appear(2), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (needsModel && room >= 110.dp) Surface(onClick = connect, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("chat-connect").appear(3)) {
                Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp))
                    Text("Подключить AI", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Icon(Icons.Outlined.ArrowForward, null, Modifier.size(20.dp))
                }
            }
            if (room >= 160.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Suggestion(Modifier.weight(1f).testTag("suggest-idea").appear(4), if (icons) Icons.Outlined.Lightbulb else null, "Идея") { select("Помоги придумать необычную идею для проекта", false) }
                Suggestion(Modifier.weight(1f).testTag("suggest-app").appear(5), if (icons) Icons.Outlined.Apps else null, "Открыть") { select("Найди и открой приложение ", true) }
                Suggestion(Modifier.weight(1f).testTag("suggest-files").appear(6), if (icons) Icons.Outlined.FolderOpen else null, "Файлы") { select("Покажи, что есть в выбранной папке", true) }
            }
        }
    }
}

@Composable private fun Suggestion(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)) }
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun MessageBubble(message: ChatMessage,animate: Boolean,files: AttachmentStore,onCursor: (Float)->Unit,onGrowth: ()->Unit) {
    if(message.role=="tool") {
        Row(Modifier.fillMaxWidth().padding(start=52.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {Icon(Icons.Outlined.CheckCircleOutline,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.secondary);Text(message.text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    } else if(message.role=="user") {
        Row(Modifier.fillMaxWidth().appear(),horizontalArrangement=Arrangement.End) {
            Surface(Modifier.widthIn(max=320.dp),shape=RoundedCornerShape(24.dp,24.dp,7.dp,24.dp),color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.65f)) {
                Column(Modifier.padding(horizontal=18.dp,vertical=13.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(message.attachments.isNotEmpty()) AttachmentStrip(message.attachments,files)
                    if(message.text.isNotBlank()) SelectionContainer {Text(message.text,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)}
                }
            }
        }
    } else {
        // A new answer arrives whole and fades in as a block; text is never revealed letter by letter.
        Column(Modifier.fillMaxWidth().padding(start=52.dp).then(if(animate) Modifier.appear() else Modifier).testTag("reply-${message.id}"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Luma",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            if(message.reasoning.isNotBlank()) ThinkingBlock(message.reasoning,message.stats)
            MarkdownContent(message.text,Modifier.fillMaxWidth(),onCursor=onCursor,onGrowth=onGrowth)
            AnswerActions(message)
        }
    }
}
@Composable fun Thinking(label: String) {Text(label,Modifier.padding(8.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable fun RunCard(run: RunState, approve: () -> Unit, reject: () -> Unit, pause: () -> Unit, stop: () -> Unit, maxSteps: Int = 20,retry: ()->Unit={},
    elapsed: Long = 0, stats: dev.luma.agent.core.TokenStats? = null, meterActive: Boolean = false) {
    SoftCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(30.dp))
            Column(Modifier.weight(1f)) { Text(if (run.approval != null) "Нужно подтверждение" else if (run.paused) "Агент на паузе" else "Luma работает", style = MaterialTheme.typography.titleSmall); Text((if(maxSteps==0) "Шаг ${run.step} · без лимита" else "Шаг ${run.step} из $maxSteps") + " · ${dev.luma.agent.core.formatElapsed(elapsed)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("run-timer"))
                if (meterActive) TokenMeter(stats, run.active && run.approval == null && !run.paused, Modifier.offset(x = (-6).dp)) }
            IconButton(onClick = stop) { Icon(Icons.Outlined.StopCircle, "Остановить") }
        }
        AnimatedContent(run.approval?.title ?: run.stage, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "run stage") { stage ->
            Text(stage, style = MaterialTheme.typography.bodyMedium)
        }
        run.approval?.let { approval ->
            Text(approval.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (approval.background) Text("После разрешения Luma свернётся и продолжит действие в предыдущем приложении.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = approve, modifier = Modifier.weight(1f)) { Text("Разрешить") }; OutlinedButton(onClick = reject, modifier = Modifier.weight(1f)) { Text("Отменить") } }
        } ?: if(run.retryPending) Button(onClick=retry,modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.Refresh,null);Spacer(Modifier.width(8.dp));Text("Повторить запрос")}
        else OutlinedButton(onClick = pause, modifier = Modifier.fillMaxWidth()) { Icon(if (run.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null); Spacer(Modifier.width(8.dp)); Text(if (run.paused) "Продолжить" else "Пауза") }
    }
}

/** Copy plus a quiet footnote: generation speed and total time of this answer, when known. */
@Composable private fun AnswerActions(message: ChatMessage) {
    val clipboard=LocalClipboardManager.current
    var copied by remember(message.id) {mutableStateOf(false)}
    LaunchedEffect(copied) {if(copied) {delay(1600);copied=false}}
    Row(Modifier.offset(x=(-10).dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text));copied=true},Modifier.size(36.dp).testTag("copy-${message.id}")) {
            Crossfade(copied,animationSpec=tween(180),label="copied") {done ->
                Icon(if(done) Icons.Outlined.Check else Icons.Outlined.ContentCopy,if(done) "Скопировано" else "Копировать ответ",Modifier.size(17.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        message.stats?.let {stats ->
            val parts=listOfNotNull(stats.outputPerSecond?.let {"↓ ${dev.luma.agent.core.formatRate(it)} т/с"},stats.totalMs.takeIf {it>0}?.let {"%.1f с".format(java.util.Locale.ROOT,it/1000.0).replace('.',',')})
            if(parts.isNotEmpty()) Text(parts.joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.75f))
        }
    }
}
