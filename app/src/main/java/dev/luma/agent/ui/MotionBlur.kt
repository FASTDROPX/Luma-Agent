package dev.luma.agent.ui

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

internal val MotionBlurAmount=SemanticsPropertyKey<Float>("Motion blur amount")
@Composable fun Modifier.motionBlur(moving: Boolean,horizontal: Boolean=false): Modifier {
    val effects=LocalVisualSettings.current
    val reduced=LocalReducedMotion.current
    val amount by animateFloatAsState(if(moving && effects.motionBlur && !reduced) 1f else 0f,tween(if(moving) 75 else 180),label="scroll blur")
    return blurLayer(if(effects.motionBlur && !reduced) amount else 0f,horizontal,effects.blurStrength)
}
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable fun Modifier.screenMotionBlur(transition: Transition<EnterExitState>): Modifier {
    val effects=LocalVisualSettings.current;val reduced=LocalReducedMotion.current
    val amount by transition.animateFloat(transitionSpec={tween(if(effects.motionBlur && !reduced) 300 else 0)},label="screen blur") {if(it==EnterExitState.Visible) 0f else 1f}
    return blurLayer(if(effects.motionBlur && !reduced) amount else 0f,true,effects.blurStrength)
}
@Composable private fun Modifier.blurLayer(amount: Float,horizontal: Boolean,strength: Float): Modifier {
    val radius=with(LocalDensity.current) {3.5.dp.toPx()}*strength*amount
    val tagged=semantics {this[MotionBlurAmount]=amount}
    return if(Build.VERSION.SDK_INT>=31) tagged.graphicsLayer {
        renderEffect=if(radius>.05f) BlurEffect(if(horizontal) radius else .25f,if(horizontal) .25f else radius,TileMode.Clamp) else null
    } else tagged.drawWithContent {
        drawContent()
        if(radius>.05f) {
            // Directional multi-sampling on Android 8–11; never blur a resting frame.
            val paint=Paint().apply {alpha=amount*.14f}
            for(sign in listOf(-1,1)) {
                drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero,size),paint)
                translate(if(horizontal) sign*radius else 0f,if(horizontal) 0f else sign*radius) {this@drawWithContent.drawContent()}
                drawContext.canvas.restore()
            }
        }
    }
}
@Composable fun MotionLazyColumn(modifier: Modifier=Modifier,state: LazyListState=rememberLazyListState(),
    contentPadding: PaddingValues=PaddingValues(0.dp),verticalArrangement: Arrangement.Vertical=Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal=Alignment.Start,content: LazyListScope.()->Unit) {
    LazyColumn(modifier.motionBlur(motionIn(state) {state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset}),state=state,contentPadding=contentPadding,
        verticalArrangement=verticalArrangement,horizontalAlignment=horizontalAlignment,content=content)
}
@Composable fun Modifier.motionScroll(state: ScrollState=rememberScrollState()): Modifier=motionBlur(motionIn(state) {state.value}).verticalScroll(state)
@Composable fun Modifier.motionHorizontalScroll(state: ScrollState=rememberScrollState()): Modifier=motionBlur(motionIn(state) {state.value},true).horizontalScroll(state)

@Composable private fun motionIn(key: Any,position: ()->Any): Boolean {
    val enabled=LocalVisualSettings.current.motionBlur && !LocalReducedMotion.current
    val moving by produceState(false,key,enabled) {
        value=false
        if(enabled) snapshotFlow {position()}.drop(1).collectLatest {
            value=true
            delay(90)
            value=false
        }
    }
    return moving
}
