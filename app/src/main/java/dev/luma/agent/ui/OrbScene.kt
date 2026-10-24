package dev.luma.agent.ui

import dev.luma.agent.core.tr

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.*

@Composable fun LumaOrb(modifier: Modifier=Modifier,working: Boolean=false,simulation: OrbPhysics?=null,interactive: Boolean=false) {
    val reduced=LocalReducedMotion.current
    val effects=LocalVisualSettings.current
    val scene=simulation ?: remember(working,effects.particleCount,effects.orbitSpeed,effects.returnDelay) {OrbPhysics(if(working) 56 else effects.particleCount,effects.orbitSpeed,returnDelay=effects.returnDelay)}
    val lifecycle=LocalLifecycleOwner.current
    var frame by remember {mutableIntStateOf(0)}
    val blue=lerp(Color(0xFF92D7FF),MaterialTheme.colorScheme.primary,.12f)
    val slop=LocalViewConfiguration.current.touchSlop
    DisposableEffect(scene,interactive) {onDispose {scene.endTouch()}}
    LaunchedEffect(scene,reduced,lifecycle) {
        if(reduced) {scene.settleMotion();frame++;return@LaunchedEffect}
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous=0L
            try {while(isActive) withInfiniteAnimationFrameNanos {now ->
                    if(previous!=0L) scene.advance(((now-previous)/1_000_000_000f).coerceAtMost(.05f))
                    previous=now;frame++
                }
            } finally {scene.endTouch()}
        }
    }
    val gestures=if(interactive && !reduced) Modifier.pointerInput(scene,slop) {
        try {awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=true)
            val radius=minOf(size.width,size.height)*.279f
            val middle=Offset(size.width/2f,size.height/2f)
            if((down.position-middle).getDistance()>radius*1.1f || !scene.beginTouch()) return@awaitEachGesture
            down.consume()
            var movement=Offset.Zero
            var dragging=false
            var lastTime=down.uptimeMillis
            try {
                while(true) {
                    val event=awaitPointerEvent()
                    val change=event.changes.firstOrNull {it.id==down.id} ?: break
                    if(change.isConsumed || !change.pressed || event.changes.count {it.pressed}>1) break
                    val delta=change.positionChange();movement+=delta
                    if(!dragging && movement.getDistance()>slop) dragging=true
                    if(dragging && delta!=Offset.Zero) {
                        scene.drag(delta.x/radius,delta.y/radius,(change.uptimeMillis-lastTime)/1000f)
                    }
                    lastTime=change.uptimeMillis
                    change.consume()
                }
            } finally {scene.endTouch()}
        }} finally {scene.endTouch()}
    } else Modifier
    val flight=if(scene.hasArrival) Modifier.graphicsLayer {
        frame
        translationX=scene.arrivalX*size.minDimension*.279f
        translationY=scene.arrivalY*size.minDimension*.279f
    } else Modifier
    // Arrival trails and expanded orbits may extend beyond the reserved, unchanged layout slot.
    val bounds=if(!interactive && !scene.hasArrival) Modifier.clipToBounds() else Modifier
    Canvas(flight.then(modifier).then(gestures).then(bounds).testTag("luma-orb").semantics {contentDescription=tr("Сфера Luma")}) {
        frame
        drawSatelliteOrb(scene,blue)
    }
}
internal fun DrawScope.drawSatelliteOrb(scene: OrbPhysics,blue: Color) {
    val c=center;val radius=size.minDimension*.279f
    drawCircle(Brush.radialGradient(listOf(blue.copy(alpha=.13f),Color.Transparent),c,radius*1.55f),radius*1.55f,c)
    fun satellites(front: Boolean) {
        for(p in scene.particles) {
            if((p.z>=0)!=front || p.behindSphere) continue
            val q=c+Offset(p.x*radius,p.y*radius)
            val depth=((p.z+1.7f)/3.4f).coerceIn(0f,1f)
            val alpha=(.24f+depth*.54f+p.shade*.18f).coerceAtMost(.9f)
            val color=lerp(Color(0xFFD4C6FF),Color(0xFFA6E7FF),p.shade).copy(alpha=alpha)
            val dot=(p.size.dp.toPx()*(.6f+depth*.4f)).coerceAtMost(radius*.023f)
            if(p.shade>.82f) drawCircle(color.copy(alpha=alpha*.10f),dot*3.5f,q)
            drawCircle(color,dot,q)
        }
    }
    satellites(false)
    val light=c+Offset(-radius*.37f,-radius*.42f)
    drawCircle(Brush.radialGradient(colorStops=arrayOf(0f to Color(0xFFF0FBFF),.31f to lerp(blue,Color.White,.25f),.65f to blue,1f to Color(0xFF5264A4)),center=light,radius=radius*1.43f),radius,c)
    drawCircle(Color.White.copy(alpha=.18f),radius,c,style=Stroke(.6.dp.toPx()))
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=.12f),Color.Transparent),light,radius*.7f),radius*.7f,light)
    val star=Path()
    val points=floatArrayOf(0f,-.24f,.058f,-.058f,.24f,0f,.058f,.058f,0f,.24f,-.058f,.058f,-.24f,0f,-.058f,-.058f)
    val starFront=scene.project(0f,0f,1f).z
    if(starFront>0f) {
        for(i in points.indices step 2) {
            val x=points[i];val y=points[i+1]
            val p=scene.project(x,y,sqrt(1f-x*x-y*y))
            val q=c+Offset(p.x*radius,p.y*radius)
            if(i==0) star.moveTo(q.x,q.y) else star.lineTo(q.x,q.y)
        }
        star.close();drawPath(star,Color.White.copy(alpha=.9f*starFront.coerceAtMost(1f)))
    }
    satellites(true)
}
