@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.core.Conversation
import dev.luma.agent.core.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tap on the model name in the chat header: every model of every connected provider, one tap to switch. */
@Composable fun ModelSheet(graph: AppGraph, close: () -> Unit, openModels: () -> Unit) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val groups = Provider.entries.mapNotNull { p ->
        val c = settings.connection(p)
        if ((p.keyRequired && c.key.isBlank()) || c.models.isEmpty()) null
        else p to c.models.filter { query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true) }
    }
    val total = groups.sumOf { it.second.size }
    ModalBottomSheet(onDismissRequest = close) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Модель", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { close(); openModels() }) { Text("Подключения") }
        }
        if (groups.sumOf { settings.connection(it.first).models.size } > 8) OutlinedTextField(query, { query = it.take(60) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("model-search"),
            placeholder = { Text("Найти модель") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        MotionLazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("model-sheet")) {
            if (groups.isEmpty()) item {
                Text(tone("Пока нет подключённых моделей. Открой «Подключения», чтобы добавить провайдера.", "Пока нет подключённых моделей. Откройте «Подключения», чтобы добавить провайдера."),
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for ((provider, models) in groups) {
                if (models.isEmpty()) continue
                item(key = "head-${provider.name}") {
                    Text(if (provider == Provider.LOCAL) "Локальный сервер" else provider.label, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                items(models.take(200), key = { "${provider.name}/${it.id}" }) { model ->
                    val current = settings.provider == provider && settings.connection(provider).model == model.id
                    ListItem(headlineContent = { Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(listOfNotNull(shortModelName(model.id).takeIf { it != model.name }, if (model.imageOutput) "рисует" else null, if (model.vision) "видит картинки" else null).joinToString(" · ").ifBlank { model.id },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                        leadingContent = { Icon(if (model.imageOutput) Icons.Outlined.Palette else Icons.Outlined.AutoAwesome, null, tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = { if (current) Icon(Icons.Outlined.Check, "Выбрана", tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f) else MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { graph.useModel(provider, model.id); close() }.testTag("model-${model.id}"))
                }
            }
            if (groups.isNotEmpty() && total == 0) item { Text("Ничего не нашлось", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Chat history: search, pinned chats first, and a menu to rename, pin, export or delete each chat. */
@Composable fun HistorySheet(graph: AppGraph, close: () -> Unit, open: (Long) -> Unit) {
    val conversations by graph.chats.conversations.collectAsStateWithLifecycle()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Conversation>?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var exporting by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(query, conversations) {
        if (query.isBlank()) { found = null; return@LaunchedEffect }
        delay(200); found = graph.chats.search(query.trim())
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val chat = exporting; exporting = null
        if (uri != null && chat != null) scope.launch {
            graph.uiMessage.value = runCatching {
                val text = graph.chats.exportMarkdown(chat)
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream") }
                "Чат сохранён в Markdown"
            }.getOrDefault("Не удалось сохранить чат")
        }
    }
    val shown = found ?: conversations
    ModalBottomSheet(onDismissRequest = close) {
        Text("История чатов", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it.take(80) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("history-search"),
            placeholder = { Text("Поиск по чатам") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Очистить") } },
            singleLine = true, shape = RoundedCornerShape(16.dp))
        MotionLazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
            items(shown, key = { it.id }) { chat ->
                var menu by remember { mutableStateOf(false) }
                ListItem(headlineContent = { Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(chat.updated)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingContent = { Icon(if (chat.pinned) Icons.Outlined.PushPin else Icons.Outlined.ChatBubbleOutline, if (chat.pinned) "Закреплён" else null, tint = if (chat.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }, Modifier.testTag("history-menu-${chat.id}")) { Icon(Icons.Outlined.MoreVert, "Действия с чатом") }
                            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp)) {
                                DropdownMenuItem(text = { Text("Переименовать") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; renaming = chat })
                                DropdownMenuItem(text = { Text(if (chat.pinned) "Открепить" else "Закрепить") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                    onClick = { menu = false; scope.launch { graph.chats.setPinned(chat.id, !chat.pinned) } })
                                DropdownMenuItem(text = { Text("Экспорт в Markdown") }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                    onClick = { menu = false; exporting = chat.id; export.launch(fileName(chat.title)) })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    enabled = !(run.active && run.chatId == chat.id), onClick = { menu = false; deleting = chat })
                            }
                        }
                    },
                    modifier = Modifier.clickable { open(chat.id) }.testTag("history-${chat.id}"))
            }
            if (shown.isEmpty()) item { Text(if (query.isBlank()) "Чатов пока нет" else "Ничего не нашлось", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(24.dp))
    }
    renaming?.let { chat ->
        var title by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Название чата") },
            text = { OutlinedTextField(title, { title = it.take(120) }, singleLine = true, modifier = Modifier.testTag("rename-field")) },
            confirmButton = { TextButton(onClick = { val t = title.trim(); if (t.isNotEmpty()) scope.launch { graph.chats.rename(chat.id, t) }; renaming = null }, enabled = title.isNotBlank()) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } })
    }
    deleting?.let { chat ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить чат?") }, text = { Text("Сообщения будут удалены с этого телефона.") },
            confirmButton = { TextButton(onClick = { scope.launch { graph.chats.delete(chat.id) }; deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
    }
}

private fun fileName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().take(60).ifBlank { "Чат" } + ".md"
