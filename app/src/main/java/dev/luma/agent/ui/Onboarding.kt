package dev.luma.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph

@Composable fun Onboarding(graph: AppGraph) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var entrancePlayed by rememberSaveable { mutableStateOf(false) }
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = page > 0) { page-- }
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().testTag("setup")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) IconButton(onClick = { page-- }) { Icon(Icons.Outlined.ArrowBack, "Назад") }
            Text("Luma", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${page + 1} / 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            repeat(3) { index -> Box(Modifier.padding(3.dp).size(if (page == index) 8.dp else 5.dp).background(if (page == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)) }
        }
        AnimatedContent(page, modifier = Modifier.weight(1f).fillMaxWidth(), transitionSpec = { (fadeIn(tween(if (reduced) 0 else 250)) + slideInHorizontally { if (reduced) 0 else it / 8 }) togetherWith fadeOut(tween(if (reduced) 0 else 150)) }, label = "onboarding") { step ->
            Box(Modifier.fillMaxSize().screenMotionBlur(transition)) { when (step) {
                0 -> Welcome(!entrancePlayed) { entrancePlayed = true }
                1 -> AccessScreen(graph, onboarding = true)
                else -> ConnectionsScreen(graph, onboarding = true)
            } }
        }
        // The form owns the space above the keyboard while typing.
        if (!keyboardOpen) Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).appear(if (page == 0) 18 else 1), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = { if (page < 2) page++ else graph.update { it.copy(onboarded = true) } }, enabled = page != 2 || settings.connection().model.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-next")) {
                    Text(when (page) { 0 -> "Настроить доступ"; 1 -> "Подключить модель"; else -> "Открыть Luma" })
                    Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp))
                }
                TextButton(onClick = { if (page == 1) page++ else graph.update { it.copy(onboarded = true) } }, modifier = Modifier.testTag("setup-skip")) {
                    Text(when (page) { 0 -> "Настроить позже"; 1 -> "Пропустить разрешения"; else -> "Подключить позже" })
                }
            }
        }
    }
}

@Composable private fun Welcome(animate: Boolean, finished: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp).testTag("setup-welcome")) {
        val compact = maxHeight / LocalDensity.current.fontScale < 410.dp
        val distance = with(LocalDensity.current) { (maxWidth + 80.dp).toPx() }
        val showDescription = maxHeight / LocalDensity.current.fontScale >= 290.dp
        Column(Modifier.fillMaxSize().padding(top = if (compact) 4.dp else 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FlyingBadge(Modifier.size(if (compact) 56.dp else 76.dp), distance, animate, finished)
                Text("Привет, я Luma", Modifier.weight(1f).appear(9), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(if (compact) 16.dp else 28.dp))
            Text("Чат с ИИ\nи помощь на телефоне", Modifier.fillMaxWidth().appear(13), style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
            if (showDescription) {
                Spacer(Modifier.height(10.dp))
                Text("Вопросы — в чат, действия — агенту.", Modifier.fillMaxWidth().appear(14), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(if (compact) 14.dp else 24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WelcomeFeature(Icons.Outlined.ChatBubbleOutline, "Чат с любой моделью", 15)
                WelcomeFeature(Icons.Outlined.TouchApp, t("Агент действует за вас"), 16)
                WelcomeFeature(Icons.Outlined.VerifiedUser, t("Доступ включаете вы"), 17)
            }
        }
    }
}

@Composable private fun WelcomeFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, order: Int) {
    Surface(Modifier.fillMaxWidth().appear(order), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
    }
}
