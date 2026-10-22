package dev.luma.agent.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.luma.agent.R
import kotlinx.coroutines.delay

/** Transform-only entrances keep the final layout reserved throughout the animation. */
@Composable fun Modifier.appear(order: Int = 0): Modifier {
    val reduced = LocalReducedMotion.current
    val effects = LocalVisualSettings.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    val lift = with(LocalDensity.current) { 14.dp.toPx() }
    LaunchedEffect(reduced) {
        if (reduced) progress.snapTo(1f)
        else progress.animateTo(1f, tween(420, delayMillis = 100 + order * 85, easing = FastOutSlowInEasing))
    }
    return graphicsLayer { alpha = progress.value; translationY = lift * (1f - progress.value); scaleX = .97f + progress.value * .03f; scaleY = scaleX
        renderEffect = if(android.os.Build.VERSION.SDK_INT >= 31 && effects.motionBlur && !reduced && progress.value in .01f.. .98f)
            androidx.compose.ui.graphics.BlurEffect(1f,(1f-progress.value)*effects.blurStrength*9f,androidx.compose.ui.graphics.TileMode.Decal) else null
    }
}

@Composable fun LumaBadge(modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(27), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .16f))) {
        Icon(painterResource(R.drawable.ic_luma_foreground), "Luma", Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable fun FlyingBadge(modifier: Modifier, distancePx: Float, animate: Boolean, finished: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val effects=LocalVisualSettings.current
    val complete by rememberUpdatedState(finished)
    LaunchedEffect(reduced) {
        if(!reduced && animate) delay(850)
        complete()
    }
    BoxWithConstraints(modifier) {
        val radius=with(LocalDensity.current) {maxWidth.toPx()}*.279f
        // Keep the same scene after entrancePlayed changes; returning to this page starts settled.
        val initialDistance=remember {if(reduced || !animate || radius<=0) 0f else distancePx/radius}
        val scene=remember(effects.particleCount,effects.orbitSpeed,effects.returnDelay) {
            OrbPhysics(effects.particleCount,effects.orbitSpeed,initialDistance,effects.returnDelay)
        }
        LumaOrb(Modifier.fillMaxSize().testTag("luma-flying"),simulation=scene,interactive=true)
    }
}
