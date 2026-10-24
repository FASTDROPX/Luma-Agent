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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
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
import kotlin.math.*

/** A separate, immersive voice surface. It never changes the phone-control overlays. */
@Composable fun ConversationHost(graph: AppGraph) {
    val visible by graph.voice.screen.collectAsStateWithLifecycle()
    if (!visible) return
    val state by graph.voice.state.collectAsStateWithLifecycle()
    val muted by graph.voice.muted.collectAsStateWithLifecycle()
    val outputLevel by graph.voice.output.level.collectAsStateWithLifecycle()
    val agent by graph.chats.agentMode.collectAsStateWithLifecycle()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    var controls by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val reduced=LocalReducedMotion.current
    LaunchedEffect(Unit) {entered=true}
    LaunchedEffect(closing) {if(closing) {kotlinx.coroutines.delay(if(reduced) 0 else 280);graph.voice.stop()}}
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
            val label=when {muted->tr("Микрофон выключен"); speaking->tr("Luma отвечает");thinking->if(agent) tr("Luma выполняет задачу") else tr("Luma думает");else->tr("Я слушаю")}
            Box(Modifier.fillMaxSize().background(background).testTag("conversation-screen")) {
                ConversationEdgeLight(Modifier.matchParentSize(),outputLevel,speaking)
                // The same identity as the app's orb, expanded into a responsive particle field.
                VoiceConstellation(Modifier.fillMaxWidth().fillMaxHeight(.78f).align(Alignment.Center),state.level,outputLevel,speaking,thinking,muted)
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=24.dp)) {
                    Row(Modifier.fillMaxWidth().height(60.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={graph.voice.screen.value=false},modifier=Modifier.testTag("conversation-minimize")) {Icon(Icons.Outlined.KeyboardArrowDown,tr("Свернуть разговор"))}
                        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text("Luma",style=MaterialTheme.typography.titleLarge)
                            Text(settings.connection().models.firstOrNull {it.id==settings.connection().model}?.name ?: settings.connection().model,
                                style=MaterialTheme.typography.labelSmall,color=on.copy(alpha=.5f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        IconButton(onClick={controls=true},modifier=Modifier.testTag("conversation-settings")) {Icon(Icons.Outlined.Tune,tr("Настройки разговора"))}
                    }
                    Spacer(Modifier.weight(1f))
                    AnimatedContent(label,transitionSpec={fadeIn(tween(300)) togetherWith fadeOut(tween(180))},label="voice phase") {
                        Text(it,Modifier.fillMaxWidth(),style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(92.dp),contentAlignment=Alignment.TopCenter) {
                        Text(state.partial.take(500),Modifier.fillMaxWidth().padding(horizontal=12.dp),maxLines=3,overflow=TextOverflow.Ellipsis,
                            color=on.copy(alpha=.6f),style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.Center)
                    }
                    Row(Modifier.align(Alignment.CenterHorizontally),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!agent,{graph.setAgentMode(false)},label={Text(tr("Чат"))},leadingIcon={Icon(Icons.Outlined.ChatBubbleOutline,null,Modifier.size(16.dp))})
                        FilterChip(agent,{graph.setAgentMode(true)},label={Text(tr("Агент"))},leadingIcon={Icon(Icons.Outlined.Bolt,null,Modifier.size(16.dp))})
                    }
                    if(run.active && (run.approval!=null || run.permission!=null || run.systemAction!=null)) {
                        FilledTonalButton(onClick={graph.voice.screen.value=false},modifier=Modifier.align(Alignment.CenterHorizontally)) {Text(tr("Нужно подтверждение"))}
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
        val colors=listOf(Color(0xff9fe8ff),Color(0xff83b7ff),Color(0xffc2abff),Color(0xff8ccfff),Color(0xff9fe8ff))
        val width=(14f+amplitude*34f).dp.toPx()*presence
        rotate(0f) {
            // Nested low-alpha strokes produce an inward halo without software blur.
            for(layer in 24 downTo 1) {
                val glow=Brush.sweepGradient(colors.map {it.copy(alpha=presence*(.006f+amplitude*.011f))},center=Offset(size.width*(.5f+.1f*sin(phase*.02f)),size.height*.5f))
                drawRoundRect(glow,cornerRadius=androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),style=Stroke(width*layer/12f))
            }
            val rim=Brush.sweepGradient(colors.map {it.copy(alpha=presence*(.22f+amplitude*.46f))})
            drawRoundRect(rim,cornerRadius=androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),style=Stroke((1f+amplitude*1.5f).dp.toPx()))
        }
    }
}

/** Draw-time animation: no recomposition per particle, no bitmap filters or per-frame allocations of particle state. */
@Composable internal fun VoiceConstellation(modifier: Modifier,input: Float,output: Float,speaking: Boolean,thinking: Boolean,muted: Boolean) {
    val reduced=LocalReducedMotion.current
    val lifecycle=LocalLifecycleOwner.current
    val primary=MaterialTheme.colorScheme.primary
    var time by remember {mutableFloatStateOf(0f)}
    val energy by animateFloatAsState(if(muted || reduced) 0f else if(speaking) output.coerceAtLeast(.18f) else input,
        animationSpec=if(reduced) snap() else tween(180, easing=LinearOutSlowInEasing),label="voice energy")
    val speech by animateFloatAsState(if(speaking) 1f else 0f,tween(if(reduced) 0 else 700),label="speech orbit")
    val thought by animateFloatAsState(if(thinking) 1f else 0f,tween(if(reduced) 0 else 700),label="thought orbit")
    LaunchedEffect(reduced,lifecycle) {
        if(!reduced) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last=0L
            while(isActive) withFrameNanos {now ->if(last!=0L) time+=((now-last)/1e9f).coerceAtMost(.05f);last=now}
        }
    }
    Canvas(modifier) {
        val t=time;val c=center+Offset(0f,-size.height*.07f)
        val radius=size.minDimension*.29f*(1f+energy*.065f+sin(t*1.3f)*.012f)
        val ice=Color(0xff9ee8ff);val violet=lerp(Color(0xffb4b8ff),primary,.2f)
        drawCircle(Brush.radialGradient(listOf(ice.copy(alpha=.10f+energy*.05f),violet.copy(alpha=.035f),Color.Transparent),c,radius*2.3f),radius*2.3f,c)
        fun particleField(front: Boolean) {
            for(i in 0 until 190) {
                val seed=i*2.399963f
                val cycle=(t*.10f+i*.618034f)%1f
                val inward=1.95f-cycle*.65f
                val outward=1.25f+cycle*.75f
                val orbit=(inward*(1f-speech)+outward*speech)*(1f-thought*.13f)+energy*.25f
                val a=seed+t*(.085f+(i%7)*.008f)*(if(i%2==0) 1 else -1)+speech*.15f
                val z=sin(seed*.63f+t*.16f)
                if((z>0)!=front) continue
                val flow=if(speech>.1f) sin(t*1.9f-i*.12f)*speech*.11f else sin(t*.7f+i)*.025f
                val x=cos(a)*radius*(orbit+flow)
                val y=sin(a)*radius*(.48f+sin(seed*.37f)*.28f)+sin(seed*.71f+t*.2f)*radius*.34f
                val p=c+Offset(x,y)
                val alpha=(.16f+(z+1f)*.19f+energy*.25f)*sin(cycle*PI.toFloat()).coerceAtLeast(.02f)*(if(muted) .35f else 1f)
                val color=lerp(ice,violet,(sin(seed)+1f)/2).copy(alpha=alpha)
                val dot=(.7f+(i%5)*.28f).dp.toPx()*(.75f+(z+1f)*.25f)
                if(i%9==0) drawCircle(color.copy(alpha=alpha*.10f),dot*4f,p)
                drawCircle(color,dot,p)
                if(speech>.1f && i%3==0) drawLine(color.copy(alpha=alpha*.35f),p,p+Offset(-sin(a),cos(a))*(3f+energy*12f)*speech,strokeWidth=dot*.7f,cap=StrokeCap.Round)
            }
        }
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
        if(thought>.01f) rotate(t*16f,c) {drawArc(violet.copy(alpha=.3f*thought),-40f,75f,false,c-Offset(radius*1.1f,radius*1.1f),androidx.compose.ui.geometry.Size(radius*2.2f,radius*2.2f),style=Stroke(1.dp.toPx(),cap=StrokeCap.Round))}
        particleField(true)
    }
}
