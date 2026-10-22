package dev.luma.agent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable private fun animatedPhase(speed: State<Float>): State<Float> {
    val reduced=LocalReducedMotion.current;val lifecycle=LocalLifecycleOwner.current
    val phase=remember {mutableFloatStateOf(0f)}
    LaunchedEffect(reduced,lifecycle) {
        if(reduced) return@LaunchedEffect
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last=0L
            while(isActive) withInfiniteAnimationFrameNanos {now ->
                if(last!=0L) phase.floatValue=(phase.floatValue+((now-last)/1_000_000_000f).coerceAtMost(.05f)*speed.value)%(2*PI.toFloat())
                last=now
            }
        }
    }
    return phase
}
@Composable fun GradientActionButton(active: Boolean,enabled: Boolean,tick: Int,click: ()->Unit,diameter: androidx.compose.ui.unit.Dp=52.dp) {
    val speed=remember {Animatable(.4f)}
    val phase=animatedPhase(speed.asState())
    val reduced=LocalReducedMotion.current
    val flight=remember {Animatable(1f)}
    LaunchedEffect(tick,reduced) {
        if(tick>0 && !reduced) {
            flight.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch {speed.animateTo(3f,tween(220,easing=FastOutSlowInEasing));speed.animateTo(.4f,tween(950,easing=FastOutSlowInEasing))}
                flight.animateTo(1f,tween(650,easing=FastOutSlowInEasing))
            }
        } else {flight.snapTo(1f);speed.snapTo(.4f)}
    }
    Box(Modifier.size(diameter).graphicsLayer {val pulse=sin(flight.value*PI.toFloat());scaleX=1f-pulse*.08f;scaleY=scaleX}.clip(CircleShape)
        .semantics {contentDescription=if(active) "Остановить" else "Отправить"}
        .clickable(enabled=enabled || active,role=Role.Button,onClick=click),contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val p=phase.value
            val delta=Offset(cos(p)*size.width*.65f,sin(p)*size.height*.65f)
            val mix=(sin(p*.7f)+1)/2f
            val gradient=Brush.linearGradient(listOf(lerp(Color(0xFFF9FDFF),Color(0xFFCCB7FF),mix),Color(0xFFB8E2FF),lerp(Color(0xFFCCB7FF),Color(0xFFF9FDFF),mix),Color(0xFFF2EBFF)),center-delta,center+delta)
            drawCircle(gradient,alpha=if(enabled || active) 1f else .35f)
            drawCircle(Color.White.copy(alpha=.32f),size.minDimension/2,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        val tint=Color(0xFF2D3550).copy(alpha=if(enabled || active) 1f else .55f)
        if(flight.value<1f) Icon(Icons.Outlined.ArrowUpward,null,Modifier.size(22.dp).graphicsLayer {translationY=-diameter.toPx()*flight.value},tint=tint)
        Box(Modifier.graphicsLayer {translationY=if(flight.value<1f) diameter.toPx()*(1f-flight.value) else 0f}) {
            Crossfade(active,animationSpec=tween(if(reduced) 0 else 180),label="send to stop") {stop ->
                Icon(if(stop) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,null,Modifier.size(22.dp),tint=tint)
            }
        }
    }
}
