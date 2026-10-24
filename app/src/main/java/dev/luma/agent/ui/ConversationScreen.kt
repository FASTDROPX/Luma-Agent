@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent.ui

import dev.luma.agent.core.tr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.luma.agent.OrbAnchor
import dev.luma.agent.CursorSeed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.luma.agent.AppGraph
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

/** A separate, immersive voice surface. It never changes the phone-control overlays. */
@Composable fun ConversationHost(graph: AppGraph,openChat: ()->Unit={}) {
    val visible by graph.voice.screen.collectAsStateWithLifecycle()
    if (!visible) return
    val state by graph.voice.state.collectAsStateWithLifecycle()
    val muted by graph.voice.muted.collectAsStateWithLifecycle()
    val outputLevel by graph.voice.output.level.collectAsStateWithLifecycle()
    val agent by graph.chats.agentMode.collectAsStateWithLifecycle()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val showRun=run.active && run.acting
    var controls by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var rootCoordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    var headerBottom by remember {mutableFloatStateOf(0f)}
    var statusTop by remember {mutableFloatStateOf(0f)}
    var cardTop by remember {mutableFloatStateOf(0f)}
    val overlay by graph.overlayVisible.collectAsStateWithLifecycle()
    val actions=rememberAgentActions(graph)
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val stats by graph.api.stats.collectAsStateWithLifecycle()
    val reduced=LocalReducedMotion.current
    val cardReserve=with(LocalDensity.current) {280.dp.toPx()}
    LaunchedEffect(Unit) {entered=true}
    LaunchedEffect(closing) {if(closing) {kotlinx.coroutines.delay(if(reduced) 0 else 280);graph.voice.endConversation()}}
    Dialog(onDismissRequest={closing=true}, properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        val light=MaterialTheme.colorScheme.background.luminance()>.5f
        SideEffect {(view.parent as? DialogWindowProvider)?.window?.let {window ->
            WindowCompat.getInsetsController(window,view).apply {isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light}
        }}
        AnimatedVisibility(entered && !closing,
            enter=fadeIn(tween(if(reduced) 0 else 420))+scaleIn(initialScale=.92f,animationSpec=tween(if(reduced) 0 else 620, easing=FastOutSlowInEasing)),
            exit=fadeOut(tween(if(reduced) 0 else 260))+scaleOut(targetScale=.96f)) {
            val background=MaterialTheme.colorScheme.background
            val on=MaterialTheme.colorScheme.onSurface
            val speaking=state.phase=="Озвучиваю"
            val thinking=state.phase in listOf("Думаю","Расшифровываю","Подготовка")
            val label=when {speaking->tr("Luma отвечает");thinking->if(showRun) tr("Luma выполняет задачу") else tr("Luma думает");muted->tr("Микрофон выключен");state.phase=="Ошибка"->tr("Не удалось ответить");else->tr("Я слушаю")}
            Box(Modifier.fillMaxSize().background(background).onGloballyPositioned {rootCoordinates=it}.testTag("conversation-screen")) {
                ConversationEdgeLight(Modifier.matchParentSize(),outputLevel,speaking)
                // The same identity as the app's orb, expanded into a responsive particle field.
                VoiceConstellation(Modifier.fillMaxSize().graphicsLayer {alpha=if(overlay) 0f else 1f},state.level,outputLevel,speaking,thinking,muted,headerBottom,if(showRun) minOf(cardTop.takeIf {it>headerBottom} ?: Float.MAX_VALUE,(statusTop-cardReserve).coerceAtLeast(headerBottom+cardReserve*.35f)) else statusTop) {center,radius,seeds ->
                    if(!overlay) {
                        val screen=IntArray(2);val window=IntArray(2);view.getLocationOnScreen(screen);view.getLocationInWindow(window)
                        val at=rootCoordinates?.takeIf {it.isAttached}?.localToWindow(center) ?: center
                        graph.orbAnchor=OrbAnchor(at.x+screen[0]-window[0],at.y+screen[1]-window[1],radius,android.os.SystemClock.uptimeMillis(),immersive=true) {seeds}
                    }
                }
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=24.dp)) {
                    Row(Modifier.fillMaxWidth().height(60.dp).onGloballyPositioned {coordinates ->
                        rootCoordinates?.takeIf {it.isAttached}?.let {headerBottom=it.localPositionOf(coordinates,Offset(0f,coordinates.size.height.toFloat())).y}
                    },verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={graph.voice.screen.value=false;openChat()},modifier=Modifier.testTag("conversation-minimize")) {Icon(Icons.Outlined.KeyboardArrowDown,tr("Свернуть разговор"))}
                        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text("Luma",style=MaterialTheme.typography.titleLarge)
                            Text(settings.connection().models.firstOrNull {it.id==settings.connection().model}?.name ?: settings.connection().model,
                                style=MaterialTheme.typography.labelSmall,color=on.copy(alpha=.5f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        IconButton(onClick={controls=true},modifier=Modifier.testTag("conversation-settings")) {Icon(Icons.Outlined.Tune,tr("Настройки разговора"))}
                    }
                    Spacer(Modifier.weight(1f))
                    AnimatedVisibility(showRun,enter=fadeIn(tween(if(reduced) 0 else 420,delayMillis=if(reduced) 0 else 200))+expandVertically(tween(if(reduced) 0 else 550,delayMillis=if(reduced) 0 else 100,easing=FastOutSlowInEasing),expandFrom=Alignment.Top),exit=fadeOut(tween(220))+shrinkVertically(tween(450)),
                        modifier=Modifier.onGloballyPositioned {c ->rootCoordinates?.takeIf {it.isAttached}?.let {cardTop=it.localPositionOf(c,Offset.Zero).y}}) {
                        Box(Modifier.fillMaxWidth().heightIn(max=280.dp).verticalScroll(rememberScrollState()).padding(bottom=12.dp).testTag("conversation-run-card")) {
                            RunCard(run,approve={scope.launch {
                                if(run.approval?.background==true) {(context as? android.app.Activity)?.moveTaskToBack(true);kotlinx.coroutines.delay(400)}
                                graph.agent.decide(true)
                            }},reject={graph.agent.decide(false)},pause={graph.agent.togglePause()},stop={graph.agent.stop()},
                                maxSteps=if(settings.unlimitedAgent) 0 else settings.maxSteps,retry={graph.agent.retryRequest()},
                                grantAccess=actions.grant,skipAccess={graph.agent.skipAccess()},fallbackAccess={graph.agent.continueWithoutAccess()},
                                launchSystemAction=actions.launch,systemActionBusy=actions.busy,elapsed=rememberElapsed(run.startedAt),stats=stats,meterActive=settings.tokenMeter)
                        }
                    }
                    AnimatedContent(label,modifier=Modifier.onGloballyPositioned {coordinates ->
                        rootCoordinates?.takeIf {it.isAttached}?.let {statusTop=it.localPositionOf(coordinates,Offset.Zero).y}
                    },transitionSpec={fadeIn(tween(300)) togetherWith fadeOut(tween(180))},label="voice phase") {
                        Text(it,Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(if(showRun) 48.dp else 92.dp),contentAlignment=Alignment.TopCenter) {
                        Text(state.partial.take(500),Modifier.fillMaxWidth().padding(horizontal=12.dp),maxLines=3,overflow=TextOverflow.Ellipsis,
                            color=on.copy(alpha=.6f),style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.Center)
                    }
                    Row(Modifier.align(Alignment.CenterHorizontally),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!agent,{graph.setAgentMode(false)},label={Text(tr("Чат"))},leadingIcon={Icon(Icons.Outlined.ChatBubbleOutline,null,Modifier.size(16.dp))})
                        FilterChip(agent,{graph.setAgentMode(true)},label={Text(tr("Агент"))},leadingIcon={Icon(Icons.Outlined.Bolt,null,Modifier.size(16.dp))})
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick={graph.voice.toggleMute()},modifier=Modifier.size(68.dp).testTag("conversation-mute"),shape=CircleShape) {
                            Icon(if(muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,if(muted) tr("Включить микрофон") else tr("Выключить микрофон"),Modifier.size(28.dp))
                        }
                        Text(if(agent) tr("Может управлять телефоном") else tr("Голосовой разговор"),Modifier.weight(1f).padding(horizontal=12.dp),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelSmall,color=on.copy(alpha=.5f))
                        FilledIconButton(onClick={closing=true},modifier=Modifier.size(68.dp).testTag("conversation-end"),shape=CircleShape,
                            colors=IconButtonDefaults.filledIconButtonColors(containerColor=MaterialTheme.colorScheme.errorContainer,contentColor=MaterialTheme.colorScheme.onErrorContainer)) {
                            Icon(Icons.Outlined.Close,tr("Завершить разговор"),Modifier.size(28.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                if(controls) ModalBottomSheet(onDismissRequest={controls=false}) {
                    Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
                        Text(tr("Настройки разговора"),style=MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        Text(tr("Язык и голос меняются для следующего ответа. Для смены распознавания завершите разговор."),style=MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        VoiceOutputChoices(graph)
                        TextButton(onClick={controls=false}) {Text(tr("Готово"))}
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** Audio-reactive light belongs to this dialog's canvas, never to an overlay window. */
@Composable private fun ConversationEdgeLight(modifier: Modifier,level: Float,speaking: Boolean) {
    val reduced=LocalReducedMotion.current
    val presence by animateFloatAsState(if(speaking) 1f else 0f,tween(if(reduced) 0 else 850,easing=FastOutSlowInEasing),label="voice border entrance")
    val amplitude by animateFloatAsState(if(speaking && !reduced) level else 0f,tween(if(reduced) 0 else if(level>.2f) 140 else 420),label="voice border energy")
    val lifecycle=LocalLifecycleOwner.current
    var phase by remember {mutableFloatStateOf(0f)}
    LaunchedEffect(speaking,reduced,lifecycle) {
        if(speaking && !reduced) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous=0L
            while(isActive) withFrameNanos {now ->if(previous!=0L) phase+=((now-previous)/1e9f).coerceAtMost(.05f)*8f;previous=now}
        }
    }
    Canvas(modifier) {
        if(presence<.001f) return@Canvas
        // Soft travelling light pools: smooth attack/release, with no hard full-screen neon rim.
        val colors=listOf(Color(0xff9fe8ff),Color(0xff83b7ff),Color(0xffc2abff))
        val corner=androidx.compose.ui.geometry.CornerRadius(30.dp.toPx())
        val inset=1.dp.toPx()
        for(i in 0 until 3) {
            val angle=phase*.035f+i*2.0944f
            val spot=Offset(size.width*(.5f+.52f*cos(angle)),size.height*(.5f+.52f*sin(angle)))
            val reach=size.minDimension*(.65f+amplitude*.28f)
            for(layer in 16 downTo 1) {
                val width=(2f+layer*(1.4f+amplitude*1.5f)).dp.toPx()
                val brush=Brush.radialGradient(listOf(colors[i].copy(alpha=presence*(.012f+amplitude*.016f)),Color.Transparent),spot,reach)
                drawRoundRect(brush,Offset(inset,inset),androidx.compose.ui.geometry.Size(size.width-2*inset,size.height-2*inset),corner,style=Stroke(width))
            }
            val rim=Brush.radialGradient(listOf(colors[i].copy(alpha=presence*(.32f+amplitude*.32f)),Color.Transparent),spot,reach)
            drawRoundRect(rim,Offset(inset,inset),androidx.compose.ui.geometry.Size(size.width-2*inset,size.height-2*inset),corner,style=Stroke((1f+amplitude*.7f).dp.toPx()))
        }
    }
}

/** Draw-time animation: no recomposition per particle, no bitmap filters or per-frame allocations of particle state. */
@Composable internal fun VoiceConstellation(modifier: Modifier,input: Float,output: Float,speaking: Boolean,thinking: Boolean,muted: Boolean,contentTop: Float=0f,contentBottom: Float=0f,onOrb: (Offset,Float,List<CursorSeed>)->Unit={_,_,_->}) {
    val reduced=LocalReducedMotion.current
    val lifecycle=LocalLifecycleOwner.current
    val primary=MaterialTheme.colorScheme.primary
    val smoothBottom by animateFloatAsState(contentBottom,tween(if(reduced) 0 else 650,easing=FastOutSlowInEasing),label="voice sphere rises")
    var time by remember {mutableFloatStateOf(0f)}
    var orbitTime by remember {mutableFloatStateOf(0f)}
    val energy by animateFloatAsState(if(reduced) 0f else if(speaking) output.coerceAtLeast(.18f) else if(muted) 0f else input,
        animationSpec=if(reduced) snap() else tween(180, easing=LinearOutSlowInEasing),label="voice energy")
    val speech by animateFloatAsState(if(speaking) 1f else 0f,tween(if(reduced) 0 else 700),label="speech orbit")
    val thought by animateFloatAsState(if(thinking) 1f else 0f,tween(if(reduced) 0 else 700),label="thought orbit")
    LaunchedEffect(reduced,lifecycle) {
        if(!reduced) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last=0L
            while(isActive) withFrameNanos {now ->if(last!=0L) {val dt=((now-last)/1e9f).coerceAtMost(.05f);time+=dt;orbitTime+=dt*(1f+speech*2.4f+energy*3.5f)};last=now}
        }
    }
    Canvas(modifier) {
        val t=time
        val top=if(contentBottom>contentTop) contentTop else size.height*.13f
        val bottom=if(smoothBottom>contentTop) smoothBottom else size.height*.62f
        val available=bottom-top
        val c=Offset(center.x,top+available*.57f)
        val radius=min(size.minDimension*.29f,available*.46f)*(1f+energy*.065f+sin(t*1.3f)*.012f)
        val ice=Color(0xff9ee8ff);val violet=lerp(Color(0xffb4b8ff),primary,.2f)
        drawCircle(Brush.radialGradient(listOf(ice.copy(alpha=.10f+energy*.05f),violet.copy(alpha=.035f),Color.Transparent),c,radius*2.3f),radius*2.3f,c)
        fun particleField(front: Boolean) {
            for(i in 0 until 190) {
                val seed=i*2.399963f
                val cycle=(t*.10f+i*.618034f)%1f
                val inward=1.95f-cycle*.65f
                val outward=1.25f+cycle*.75f
                val orbit=(inward*(1f-speech)+outward*speech)*(1f-thought*.13f)+energy*.25f
                val a=seed+orbitTime*(.085f+(i%7)*.008f)*(if(i%2==0) 1 else -1)
                val z=sin(seed*.63f+t*.16f)
                if((z>0)!=front) continue
                val flow=if(speech>.1f) sin(t*1.9f-i*.12f)*speech*.11f else sin(t*.7f+i)*.025f
                val x=cos(a)*radius*(orbit+flow)
                val y=sin(a)*radius*(.48f+sin(seed*.37f)*.28f)+sin(seed*.71f+t*.2f)*radius*.34f
                val p=c+Offset(x,y)
                val alpha=(.16f+(z+1f)*.19f+energy*.25f)*sin(cycle*PI.toFloat()).coerceAtLeast(.02f)*(if(muted && !speaking) .35f else 1f)
                val color=lerp(ice,violet,(sin(seed)+1f)/2).copy(alpha=alpha)
                val dot=(.7f+(i%5)*.28f).dp.toPx()*(.75f+(z+1f)*.25f)*(1f+speech*.12f*sin(t*3f+seed)+energy*.22f)
                if(i%9==0) drawCircle(color.copy(alpha=alpha*.10f),dot*4f,p)
                drawCircle(color,dot,p)
                if(speech>.1f && i%3==0) drawLine(color.copy(alpha=alpha*.35f),p,p+Offset(-sin(a),cos(a))*(3f+energy*12f)*speech,strokeWidth=dot*.7f,cap=StrokeCap.Round)
            }
        }
        onOrb(c,radius,(0 until 64).map {i ->
            val a=i*2.399963f+orbitTime*(.085f+(i%7)*.008f)*(if(i%2==0) 1 else -1)
            CursorSeed(cos(a)*1.5f,sin(a)*.7f,sin(i*1.51f),.75f+(i%5)*.15f,(sin(i.toFloat())+1f)/2f)
        })
        particleField(false)
        // A soft spherical body with slowly moving internal aurora ribbons.
        val light=c+Offset(-radius*.30f,-radius*.38f)
        drawCircle(Brush.radialGradient(colorStops=arrayOf(0f to Color(0xfff0fcff),.38f to ice,.78f to Color(0xff72b8ef),1f to Color(0xff686bb6)),center=light,radius=radius*1.47f),radius,c)
        val circle=Path().apply {addOval(androidx.compose.ui.geometry.Rect(c-Offset(radius,radius),c+Offset(radius,radius)))}
        with(drawContext.canvas) {save();clipPath(circle)
            for(band in 0..2) {
                val path=Path()
                for(step in 0..64) {
                    val x=-radius+2*radius*step/64f
                    val y=radius*(.1f+band*.23f)+sin(x/radius*2.2f+t*.45f+band*.75f)*radius*(.18f+energy*.12f)
                    if(step==0) path.moveTo(c.x+x,c.y+y) else path.lineTo(c.x+x,c.y+y)
                }
                drawPath(path,Brush.verticalGradient(listOf(Color.Transparent,ice.copy(alpha=.055f),Color.Transparent),c.y-radius,c.y+radius),style=Stroke(radius*.55f))
            }
            for(i in 0..4) {
                val fog=c+Offset(sin(t*.24f+i*1.7f)*radius*.6f,cos(t*.19f+i*.9f)*radius*.6f)
                val shade=if(i%2==0) Color.White else ice
                drawCircle(Brush.radialGradient(listOf(shade.copy(alpha=.075f+energy*.035f),Color.Transparent),fog,radius*.85f),radius*.85f,fog)
            }
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=.23f),Color.Transparent),light,radius*.78f),radius*.78f,light)
            restore()
        }
        drawCircle(Color.White.copy(alpha=.24f),radius,c,style=Stroke(.7.dp.toPx()))
        // Luma's four-point mark remains anchored while the surrounding matter moves.
        val mark=Path();val s=radius*(.23f+energy*.015f)
        val points=floatArrayOf(0f,-1f,.23f,-.23f,1f,0f,.23f,.23f,0f,1f,-.23f,.23f,-1f,0f,-.23f,-.23f)
        for(i in points.indices step 2) {if(i==0) mark.moveTo(c.x+points[i]*s,c.y+points[i+1]*s) else mark.lineTo(c.x+points[i]*s,c.y+points[i+1]*s)}
        mark.close();drawPath(mark,Color.White.copy(alpha=.94f))
        if(thought>.01f) {
            for(i in 0 until 3) rotate(t*(20f+i*9f)*(if(i%2==0) 1 else -1)+i*120f,c) {
                val r=radius*(1.10f+i*.09f)
                drawArc(Brush.sweepGradient(listOf(Color.Transparent,ice.copy(alpha=.6f*thought),violet.copy(alpha=.1f*thought),Color.Transparent),c),-80f,150f,false,c-Offset(r,r),androidx.compose.ui.geometry.Size(r*2,r*2),style=Stroke((1.2f-i*.2f).dp.toPx(),cap=StrokeCap.Round))
                val spark=c+Offset(cos(t*.7f+i)*r,sin(t*.7f+i)*r)
                drawCircle(ice.copy(alpha=.8f*thought),2.dp.toPx(),spark)
            }
        }
        particleField(true)
    }
}
