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
        setContent { AppRoot(graph, requestedScreen) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); requestedScreen = intent.getStringExtra("screen") ?: "chat"; setIntent(intent) }
}
private data class Destination(val title: String, val icon: ImageVector)
@Composable fun AppRoot(graph: AppGraph, requested: String = "chat") {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val notice by graph.uiMessage.collectAsStateWithLifecycle()
    val conversations by graph.chats.conversations.collectAsStateWithLifecycle()
    val selectedChat by graph.chats.selected.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    BackHandler(enabled = settings.onboarded && tab != 0 && !history && deleting == null) { tab = 0 }
    LaunchedEffect(requested) { tab = if (requested == "access") 2 else 0 }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); graph.uiMessage.value = null } }
    LumaTheme(settings) {
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
                            if (tab == 0) Text(if (settings.connection().model.isBlank()) "Твой AI-помощник" else "${settings.provider.label} · ${settings.connection().model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }, navigationIcon = { LumaBadge(Modifier.padding(start = 16.dp, end = 10.dp).size(36.dp)) }, actions = {
                        if (tab == 0) { IconButton(onClick = { history = true }) { Icon(Icons.Outlined.History, "История чатов") }; IconButton(onClick = { graph.cancelChat(); scope.launch { graph.chats.select(null) } }) { Icon(Icons.Outlined.Add, "Новый чат") } }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
                    bottomBar = { if (!keyboardOpen) NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = 0.dp) { destinations.forEachIndexed { i, destination -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(destination.icon, destination.title) }, label = { Text(destination.title, maxLines = 1) }) } } }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (run.active && (tab != 0 || selectedChat != run.chatId)) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable { tab = 0; scope.launch { graph.chats.select(run.chatId) } }) {
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (run.approval != null) "Нужно подтверждение" else "Luma выполняет задачу", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); IconButton(onClick = { graph.agent.stop() }) { Icon(Icons.Outlined.Stop, "Остановить") } }
                        }
                        val reduced = LocalReducedMotion.current
                        AnimatedContent(tab, modifier = Modifier.weight(1f), transitionSpec = { (fadeIn(tween(if (reduced) 0 else 300)) + slideInHorizontally(tween(if(reduced) 0 else 360)) { if (reduced) 0 else it / 10 }) togetherWith fadeOut(tween(if (reduced) 0 else 220)) }, label = "screen") { page ->
                            Box(Modifier.fillMaxSize().screenMotionBlur(transition)) { when (page) { 0 -> ChatScreen(graph, { tab = 1 }, { tab = 2 }); 1 -> ConnectionsScreen(graph); 2 -> AccessScreen(graph); else -> SettingsScreen(graph, { tab = 2 }, { tab = 1 }) } }
                        }
                    }
                }
                if (history) ModalBottomSheet(onDismissRequest = { history = false }) {
                    Text("Твои чаты", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
                    MotionLazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                        items(conversations, key = { it.id }) { chat -> ListItem(headlineContent = { Text(chat.title, maxLines = 2) }, leadingContent = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, trailingContent = { IconButton(onClick = { deleting = chat.id }, enabled = !(run.active && run.chatId == chat.id)) { Icon(Icons.Outlined.DeleteOutline, "Удалить чат") } }, modifier = Modifier.clickable { scope.launch { graph.chats.select(chat.id) }; tab = 0; history = false }) }
                        if (conversations.isEmpty()) item { Text("Здесь появятся твои разговоры", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить чат?") }, text = { Text("Сообщения будут удалены с этого телефона.") }, confirmButton = { TextButton(onClick = { scope.launch { graph.chats.delete(id) }; deleting = null }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }) }
            }
        }
    }
}
