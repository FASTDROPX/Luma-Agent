@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.luma.agent.ui

import dev.luma.agent.core.tr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Material 3 Expressive's morphing shape, with a static alternative for reduced motion. */
@Composable fun LumaLoading(modifier: Modifier=Modifier.size(24.dp)) {
    if(LocalReducedMotion.current) Icon(Icons.Outlined.HourglassTop,tr("Загрузка"),modifier,tint=MaterialTheme.colorScheme.primary)
    else LoadingIndicator(modifier=modifier)
}

@Composable fun LumaProgress(modifier: Modifier=Modifier,progress: (() -> Float)?=null) {
    val reduced=LocalReducedMotion.current
    if(progress==null) {
        if(reduced) LinearProgressIndicator(progress={.35f},modifier=modifier,drawStopIndicator={})
        else LinearWavyProgressIndicator(modifier=modifier,amplitude=.35f,wavelength=40.dp,waveSpeed=12.dp,trackColor=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.48f))
    } else {
        val amount by animateFloatAsState(progress().coerceIn(0f,1f),if(reduced) snap() else WavyProgressIndicatorDefaults.ProgressAnimationSpec,label="download progress")
        if(reduced) LinearProgressIndicator(progress={amount},modifier=modifier,drawStopIndicator={})
        else LinearWavyProgressIndicator(progress={amount},modifier=modifier,stopSize=0.dp,amplitude={.35f},wavelength=40.dp,waveSpeed=12.dp,trackColor=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.48f))
    }
}
