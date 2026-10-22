package dev.luma.agent.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.luma.agent.core.AppSettings
import dev.luma.agent.core.VisualSettings

val LocalReducedMotion = compositionLocalOf { false }
val LocalVisualSettings = compositionLocalOf { VisualSettings() }
private val Dark = darkColorScheme(primary = Color(0xFFB5C4FF), onPrimary = Color(0xFF172552), secondary = Color(0xFF8BDCCB),
    primaryContainer = Color(0xFF2C3B68), onPrimaryContainer = Color(0xFFDCE3FF), secondaryContainer = Color(0xFF254A46), onSecondaryContainer = Color(0xFFB5F1E2),
    background = Color(0xFF10131B), surface = Color(0xFF141822), surfaceContainer = Color(0xFF1D2230), surfaceContainerHigh = Color(0xFF272D3E),
    onSurface = Color(0xFFE9EDF8), onSurfaceVariant = Color(0xFFBFC5D6), outlineVariant = Color(0xFF3E4557))
private val Light = lightColorScheme(primary = Color(0xFF475D9C), onPrimary = Color.White, secondary = Color(0xFF226B5F),
    primaryContainer = Color(0xFFDCE3FF), onPrimaryContainer = Color(0xFF1A2853), secondaryContainer = Color(0xFFB7EADD), onSecondaryContainer = Color(0xFF062F28),
    background = Color(0xFFF7F8FF), surface = Color(0xFFFBF8FF), surfaceContainer = Color(0xFFEEEDF7), surfaceContainerHigh = Color(0xFFE6E7F2))
@Composable fun LumaTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    var systemReduced by remember { mutableStateOf(!android.animation.ValueAnimator.areAnimatorsEnabled()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        systemReduced = !android.animation.ValueAnimator.areAnimatorsEnabled()
    }
    val reduced = settings.reducedMotion || systemReduced
    val target = if (settings.dynamicColors && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else paletteScheme(settings.visuals.palette,dark)
    val colors = animatedScheme(if(dark && settings.visuals.blackBackground) target.copy(background=Color.Black,surface=Color(0xFF08090D)) else target,reduced)
    SideEffect { (context as? Activity)?.window?.let { window -> WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } } }
    CompositionLocalProvider(LocalReducedMotion provides reduced, LocalVisualSettings provides settings.visuals) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)), content = content)
    }
}
@Composable fun PageTitle(eyebrow: String, title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun paletteScheme(palette: String,dark: Boolean): ColorScheme {
    val base=if(dark) Dark else Light
    val accent=when(palette) { "lilac" -> if(dark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
        "mint" -> if(dark) Color(0xFF8EDAC5) else Color(0xFF006B54)
        "peach" -> if(dark) Color(0xFFFFB59A) else Color(0xFF984524)
        else -> return base }
    val container=androidx.compose.ui.graphics.lerp(base.surface,accent,if(dark) .24f else .18f)
    return base.copy(primary=accent,onPrimary=if(dark) Color(0xFF17202B) else Color.White,
        primaryContainer=container,onPrimaryContainer=if(dark) Color(0xFFF1EFFF) else Color(0xFF1E2330),
        secondary=accent,secondaryContainer=container,onSecondaryContainer=base.onSurface,
        surfaceTint=accent,outline=androidx.compose.ui.graphics.lerp(base.outline,accent,.25f))
}
@Composable private fun animatedScheme(target: ColorScheme,reduced: Boolean): ColorScheme {
    @Composable fun color(value: Color): Color = animateColorAsState(value,tween(if(reduced) 0 else 550),label="theme color").value
    return target.copy(
        primary=color(target.primary),
        onPrimary=color(target.onPrimary),
        primaryContainer=color(target.primaryContainer),
        onPrimaryContainer=color(target.onPrimaryContainer),
        inversePrimary=color(target.inversePrimary),
        secondary=color(target.secondary),
        onSecondary=color(target.onSecondary),
        secondaryContainer=color(target.secondaryContainer),
        onSecondaryContainer=color(target.onSecondaryContainer),
        tertiary=color(target.tertiary),
        onTertiary=color(target.onTertiary),
        tertiaryContainer=color(target.tertiaryContainer),
        onTertiaryContainer=color(target.onTertiaryContainer),
        background=color(target.background),
        onBackground=color(target.onBackground),
        surface=color(target.surface),
        onSurface=color(target.onSurface),
        surfaceVariant=color(target.surfaceVariant),
        onSurfaceVariant=color(target.onSurfaceVariant),
        surfaceTint=color(target.surfaceTint),
        inverseSurface=color(target.inverseSurface),
        inverseOnSurface=color(target.inverseOnSurface),
        error=color(target.error),
        onError=color(target.onError),
        errorContainer=color(target.errorContainer),
        onErrorContainer=color(target.onErrorContainer),
        outline=color(target.outline),
        outlineVariant=color(target.outlineVariant),
        scrim=color(target.scrim),
        surfaceBright=color(target.surfaceBright),
        surfaceDim=color(target.surfaceDim),
        surfaceContainer=color(target.surfaceContainer),
        surfaceContainerHigh=color(target.surfaceContainerHigh),
        surfaceContainerHighest=color(target.surfaceContainerHighest),
        surfaceContainerLow=color(target.surfaceContainerLow),
        surfaceContainerLowest=color(target.surfaceContainerLowest)
    )
}
