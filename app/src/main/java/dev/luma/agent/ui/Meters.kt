package dev.luma.agent.ui

import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.luma.agent.core.TokenStats
import dev.luma.agent.core.formatElapsed
import dev.luma.agent.core.formatRate
import kotlinx.coroutines.delay

/** Milliseconds since [startedAt] (SystemClock.elapsedRealtime), ticking once a second; 0 while idle. */
@Composable fun rememberElapsed(startedAt: Long): Long {
    val elapsed by produceState(0L, startedAt) {
        if (startedAt <= 0L) { value = 0L; return@produceState }
        while (true) { value = SystemClock.elapsedRealtime() - startedAt; delay(1000 - value % 1000) }
    }
    return elapsed
}

/** "↑ 812 · ↓ 38 т/с": prompt and generation speed of the latest reply. Tap for details. */
@Composable fun TokenMeter(stats: TokenStats?, busy: Boolean, modifier: Modifier = Modifier, usage: dev.luma.agent.core.UsageSummary? = null) {
    var details by remember { mutableStateOf(false) }
    val pulse = if (busy && !LocalReducedMotion.current) {
        val transition = rememberInfiniteTransition(label = "meter pulse")
        transition.animateFloat(.45f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "meter alpha").value
    } else 1f
    val label = if (stats == null) "т/с —" else "↑ ${formatRate(stats.inputPerSecond)} · ↓ ${formatRate(stats.outputPerSecond)} т/с"
    AnimatedContent(label, modifier.testTag("token-meter").clip(RoundedCornerShape(8.dp)).clickable(enabled = stats != null || usage != null) { details = true }
        .graphicsLayer { alpha = pulse }.padding(horizontal = 6.dp, vertical = 1.dp),
        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 }) togetherWith (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 2 }) },
        label = "meter value") { text ->
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
    if (details && (stats != null || usage != null)) AlertDialog(onDismissRequest = { details = false }, icon = { Icon(Icons.Outlined.Speed, null) },
        title = { Text(if (stats != null) "Скорость последнего ответа" else "Расход токенов") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stats != null) TokenDetails(stats)
                usage?.let { u ->
                    if (stats != null) HorizontalDivider()
                    Text("Расход токенов", style = MaterialTheme.typography.titleSmall)
                    MeterRow("Этот чат", tokens(u.chatInput + u.chatOutput), "↑ ${tokens(u.chatInput)} · ↓ ${tokens(u.chatOutput)}")
                    MeterRow("Сегодня", tokens(u.dayInput + u.dayOutput), "↑ ${tokens(u.dayInput)} · ↓ ${tokens(u.dayOutput)}")
                }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Понятно") } })
}
private fun tokens(n: Long) = "%,d".format(java.util.Locale.ROOT, n).replace(',', ' ')
@Composable private fun TokenDetails(stats: TokenStats) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MeterRow("Вход", "${stats.inputTokens} ток.", if (stats.firstTokenMs > 0) "${formatRate(stats.inputPerSecond)} т/с за ${seconds(stats.firstTokenMs)}" else "без потока — не измерить")
                MeterRow("Выход", "${if (stats.estimated) "≈" else ""}${stats.outputTokens} ток.", "${formatRate(stats.outputPerSecond)} т/с")
                if (stats.thinkingMs > 0) MeterRow("Рассуждения", seconds(stats.thinkingMs), "")
                MeterRow("Всего", seconds(stats.totalMs), "")
                Text("Вход считается до первого токена ответа и включает задержку сети. " +
                    if (stats.estimated) "Сервер не сообщил число токенов — выход оценён по длине текста." else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
}
@Composable private fun MeterRow(title: String, value: String, extra: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.labelLarge)
            if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
private fun seconds(ms: Long) = if (ms < 60_000) "%.1f с".format(java.util.Locale.ROOT, ms / 1000.0).replace('.', ',') else formatElapsed(ms)

/** DeepSeek-style collapsible reasoning: "Рассуждения · 12,4 с ›". */
@Composable fun ThinkingBlock(reasoning: String, stats: TokenStats?, modifier: Modifier = Modifier) {
    var open by rememberSaveable(reasoning.hashCode()) { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduced) snap() else spring(dampingRatio = .8f, stiffness = 500f), label = "chevron")
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.testTag("thinking-block")) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (stats != null && stats.thinkingMs > 0) "Рассуждения · ${seconds(stats.thinkingMs)}" else "Рассуждения",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, if (open) "Скрыть рассуждения" else "Показать рассуждения", Modifier.size(18.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open, enter = if (reduced) EnterTransition.None else expandVertically(spring(stiffness = 600f)) + fadeIn(tween(220)),
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(200)) + fadeOut(tween(150))) {
            SelectionContainer {
                Text(reasoning, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp).drawBehind {
                    drawLine(line, Offset(-8.dp.toPx(), 0f), Offset(-8.dp.toPx(), size.height), 2.dp.toPx())
                }.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "hf.co/user/Qwen3-8B-GGUF:Q6_K" → "Qwen3-8B-GGUF:Q6_K", "luma-qwen3:latest" → "luma-qwen3". */
fun shortModelName(id: String): String = id.substringAfterLast('/').removeSuffix(":latest").ifBlank { id }

/** One line that fades at the end instead of being cut mid-letter. */
@Composable fun OneLine(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Text(text, modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
