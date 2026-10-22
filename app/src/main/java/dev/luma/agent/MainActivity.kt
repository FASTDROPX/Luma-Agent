@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var requestedScreen by mutableStateOf("chat")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); requestedScreen = intent.getStringExtra("screen") ?: "chat"
        val graph = (application as LumaApp).graph
        if (savedInstanceState == null) takeShared(intent)
        setContent { AppRoot(graph, requestedScreen) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); requestedScreen = intent.getStringExtra("screen") ?: "chat"; setIntent(intent); takeShared(intent) }
    override fun onStart() { super.onStart(); (application as LumaApp).graph.visibleScreens.incrementAndGet() }
    override fun onStop() { (application as LumaApp).graph.visibleScreens.updateAndGet { (it - 1).coerceAtLeast(0) }; super.onStop() }
    /** Share → Luma from another app: the chat screen picks the text and files up. */
    private fun takeShared(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .filter { it.isNotBlank() }.distinct().joinToString("\n").take(12_000)
        @Suppress("DEPRECATION")
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        // Only content:// links: a file:// path from another app could point into Luma's own storage.
        val safe = uris.filter { it.scheme == "content" }.take(4)
        if (text.isBlank() && safe.isEmpty()) return
        (application as LumaApp).graph.sharedContent.value = SharedContent(text, safe)
        requestedScreen = "chat"
    }
}
private data class Destination(val title: String, val icon: ImageVector)
@Composable fun AppRoot(graph: AppGraph, requested: String = "chat") {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val notice by graph.uiMessage.collectAsStateWithLifecycle()
    val conversations by graph.chats.conversations.collectAsStateWithLifecycle()
    val selectedChat by graph.chats.selected.collectAsStateWithLifecycle()
    val tokenStats by graph.api.stats.collectAsStateWithLifecycle()
    val chatBusy by graph.chatBusy.collectAsStateWithLifecycle()
    val usage by graph.chats.usage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(false) }
    val shared by graph.sharedContent.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared != null) { tab = 0; history = false } }
    BackHandler(enabled = settings.onboarded && tab != 0 && !history) { tab = 0 }
    LaunchedEffect(requested) { tab = if (requested == "access") 2 else 0 }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(dev.luma.agent.core.Tone.adapt(it, graph.vault.state.value.formal)); graph.uiMessage.value = null } }
    LumaTheme(settings) { CompositionLocalProvider(LocalFormal provides settings.formal, LocalChatTextScale provides settings.chatTextScale.coerceIn(.85f, 1.4f), LocalHaptics provides settings.haptics) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!settings.onboarded) {
                Box { Onboarding(graph); SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) }
            } else {
                val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val destinations = listOf(Destination("Чат", Icons.Outlined.ChatBubbleOutline), Destination("Модели", Icons.Outlined.Hub), Destination("Доступ", Icons.Outlined.Shield), Destination("Настройки", Icons.Outlined.Tune))
                Scaffold(modifier = Modifier.imePadding(), containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = { TopAppBar(title = {
                        Column { Text(if (tab == 0) "Luma" else destinations[tab].title, style = MaterialTheme.typography.titleLarge)
                            // Tap the model line to switch models without leaving the chat.
                            if (tab == 0) Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable { models = true }.testTag("header-model"), verticalAlignment = Alignment.CenterVertically) {
                                OneLine(if (settings.connection().model.isBlank()) "Модель не выбрана" else "${shortModelName(settings.connection().model)} · ${if (settings.provider == dev.luma.agent.core.Provider.LOCAL) "локально" else settings.provider.label}",
                                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.Outlined.ExpandMore, "Сменить модель", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                    }, navigationIcon = { LumaBadge(Modifier.padding(start = 16.dp, end = 10.dp).size(36.dp)) }, actions = {
                        if (tab == 0) Column(Modifier.padding(end = 4.dp), horizontalAlignment = Alignment.End) {
                            Row {
                                IconButton(onClick = { history = true }, Modifier.size(42.dp)) { Icon(Icons.Outlined.History, "История чатов") }
                                IconButton(onClick = { graph.cancelChat(); scope.launch { graph.chats.select(null) } }, Modifier.size(42.dp)) { Icon(Icons.Outlined.Add, "Новый чат") }
                            }
                            if (settings.tokenMeter) TokenMeter(tokenStats, chatBusy || run.active, usage = usage)
                        }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
                    bottomBar = { if (!keyboardOpen) NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = 0.dp) { destinations.forEachIndexed { i, destination -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(destination.icon, destination.title) }, label = { Text(destination.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) } } }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (run.active && (tab != 0 || selectedChat != run.chatId)) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable { tab = 0; scope.launch { graph.chats.select(run.chatId) } }) {
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (run.approval != null) "Нужно подтверждение" else "Luma выполняет задачу · ${dev.luma.agent.core.formatElapsed(rememberElapsed(run.startedAt))}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); IconButton(onClick = { graph.agent.stop() }) { Icon(Icons.Outlined.Stop, "Остановить") } }
                        }
                        val reduced = LocalReducedMotion.current
                        AnimatedContent(tab, modifier = Modifier.weight(1f), transitionSpec = { (fadeIn(tween(if (reduced) 0 else 300)) + slideInHorizontally(tween(if(reduced) 0 else 360)) { if (reduced) 0 else it / 10 }) togetherWith fadeOut(tween(if (reduced) 0 else 220)) }, label = "screen") { page ->
                            Box(Modifier.fillMaxSize().screenMotionBlur(transition)) { when (page) { 0 -> ChatScreen(graph, { tab = 1 }, { tab = 2 }); 1 -> ConnectionsScreen(graph); 2 -> AccessScreen(graph); else -> SettingsScreen(graph, { tab = 2 }, { tab = 1 }) } }
                        }
                    }
                }
                if (history) HistorySheet(graph, { history = false }) { id -> scope.launch { graph.chats.select(id) }; tab = 0; history = false }
                if (models) ModelSheet(graph, { models = false }) { tab = 1 }
            }
        }
    } }
}
