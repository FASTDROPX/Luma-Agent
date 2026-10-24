@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.luma.agent.ui

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.core.*

@Composable fun ConnectionsScreen(graph: AppGraph, onboarding: Boolean = false) {
    if (onboarding) { ApiConnectionsScreen(graph, true); return }
    var modelTab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            SegmentedButton(modelTab==0, { modelTab=0 }, SegmentedButtonDefaults.itemShape(0,3)) { Text("API") }
            SegmentedButton(modelTab==1, { modelTab=1 }, SegmentedButtonDefaults.itemShape(1,3)) { Text("На телефоне",maxLines=1) }
            SegmentedButton(modelTab==2, { modelTab=2 }, SegmentedButtonDefaults.itemShape(2,3)) { Text("Каталог") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { when(modelTab) {1->DeviceModelsScreen(graph);2->HuggingFaceScreen(graph);else->ApiConnectionsScreen(graph)} }
    }
}

@Composable private fun ApiConnectionsScreen(graph: AppGraph, onboarding: Boolean = false) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val catalog by graph.catalog.collectAsStateWithLifecycle()
    val provider = settings.provider.takeUnless {it==Provider.DEVICE} ?: Provider.LOCAL; val connection = settings.connection(provider)
    val context = LocalContext.current
    var key by remember(provider) { mutableStateOf(connection.key) }
    var endpoint by remember(provider) { mutableStateOf(connection.endpoint) }
    var reveal by remember(provider) { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    val busy = catalog.busy && catalog.provider == provider
    DisposableEffect(context) {
        val window = (context as? Activity)?.window; window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); graph.cancelCatalog() }
    }
    val pasteKey: () -> Unit = {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        val value = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
        if (value.isNotBlank() && value.length <= 4096) { key = value.trim(); reveal = false; graph.cancelCatalog() }
        else graph.uiMessage.value = "В буфере нет текстового API-ключа."
    }
    if (onboarding) CompactConnection(
        graph, provider, connection, key, endpoint, reveal, busy,
        catalog.error.takeIf { catalog.provider == provider },
        editKey = { key = it; graph.cancelCatalog() }, editEndpoint = { endpoint = it; graph.cancelCatalog() },
        toggleReveal = { reveal = !reveal }, paste = pasteKey,
        save = { reveal = false; graph.connect(provider, key, endpoint) }, pickModel = { modelSheet = true }
    ) else MotionLazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("ПОДКЛЮЧЕНИЯ", "Провайдер и модель", "Ключ хранится на телефоне в Android Keystore. Платные запросы оплачиваются на стороне провайдера.") }
        item {
            Row(Modifier.fillMaxWidth().motionHorizontalScroll(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.entries.filter {it!=Provider.DEVICE}.forEach { p -> FilterChip(selected = provider == p, onClick = { graph.switchProvider(p) }, label = { Text(p.label) }, leadingIcon = if (provider == p) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null) }
            }
        }
        item {
            SoftCard(Modifier.animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(if (provider == Provider.LOCAL) Icons.Outlined.Computer else Icons.Outlined.AutoAwesome, null, Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Column { Text(provider.label, style = MaterialTheme.typography.titleLarge); Text(if (provider == Provider.LOCAL) "Ollama · LM Studio · OpenAI API" else t("Подключение по вашему API-ключу"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (provider == Provider.LOCAL) {
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it; graph.cancelCatalog() }, label = { Text("Адрес API") }, placeholder = { Text("http://192.168.1.10:11434/v1/") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text("Сервер в той же сети — адрес вида http://192.168.…; на самом телефоне — localhost. Из другого города: Tailscale на ПК и телефоне и адрес http://100.… или http://имя.ts.net, либо свой HTTPS-адрес. Модель запускается на сервере.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value = key, onValueChange = { key = it; graph.cancelCatalog() }, label = { Text(if (provider.keyRequired) "API-ключ" else "Ключ, если нужен") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Скрыть ключ" else "Показать ключ") } })
                TextButton(onClick = pasteKey) { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Вставить из буфера") }
                Button(onClick = { reveal = false; graph.connect(provider, key, endpoint) }, enabled = !busy && (!provider.keyRequired || key.isNotBlank()) && (provider != Provider.LOCAL || endpoint.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (connection.models.isEmpty()) "Сохранить и загрузить модели" else "Сохранить и обновить")
                }
                AnimatedVisibility(busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LumaProgress(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Загружаю модели… До 20 с", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { graph.cancelCatalog() }) { Text("Отмена") } }
                    }
                }
                if (catalog.provider == provider && catalog.error != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) { Text(catalog.error!!, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item {
            SoftCard {
                Text("Модель", style = MaterialTheme.typography.titleMedium)
                if (connection.models.isNotEmpty()) {
                    OutlinedButton(onClick = { modelSheet = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.weight(1f)) {
                            Text(connection.models.firstOrNull { it.id == connection.model }?.name ?: "Выбрать модель", style = MaterialTheme.typography.bodyLarge)
                            if (connection.model.isNotEmpty()) Text(connection.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }; Icon(Icons.Outlined.ExpandMore, null)
                    }
                    Text("Доступно: ${connection.models.size}. Выбор применяется к следующим сообщениям и задачам.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val vision = connection.models.firstOrNull { it.id == connection.model }?.vision == true
                    if (connection.model.isNotEmpty()) AssistChip(onClick = {}, label = { Text(if (vision) "Поддерживает снимки экрана" else "Работа с текстом и элементами UI") }, leadingIcon = { Icon(if (vision) Icons.Outlined.Image else Icons.Outlined.TextFields, null, Modifier.size(16.dp)) })
                    if (provider == Provider.LOCAL && connection.model.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Модель понимает изображения", style = MaterialTheme.typography.bodyMedium); Text("Включайте только для vision-модели сервера", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Switch(vision, { value -> graph.update { settings ->
                                val current = settings.connection(provider)
                                settings.copy(connections = settings.connections + (provider to current.copy(models = current.models.map { if (it.id == current.model) it.copy(vision = value) else it })))
                            } })
                        }
                    }
                } else Text("После сохранения ключа здесь появятся модели, которые вернёт API. Для сервера без авторизации оставьте ключ пустым.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (connection.model.isNotEmpty()) item { ConnectionCheckCard(graph) }
        if (connection.key.isNotBlank() || connection.models.isNotEmpty()) item { TextButton(onClick = { removeDialog = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.LinkOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Удалить подключение ${provider.label}") } }
        item { Spacer(Modifier.height(8.dp)) }
    }
    if (modelSheet) ModelPicker(connection.models, connection.model, onDismiss = { modelSheet = false }, onSelect = { graph.chooseModel(provider, it); modelSheet = false })
    if (removeDialog) AlertDialog(onDismissRequest = { removeDialog = false }, title = { Text("Удалить подключение?") }, text = { Text("Ключ и список моделей ${provider.label} будут удалены с телефона. Остальные подключения сохранятся.") }, confirmButton = { TextButton(onClick = { graph.cancelCatalog(); graph.update { it.copy(connections = it.connections - provider) }; key = ""; endpoint = ""; removeDialog = false }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { removeDialog = false }) { Text("Отмена") } })
}

/** The first-run form has a bounded layout; catalogs and help open separately. */
@Composable private fun CompactConnection(
    graph: AppGraph, provider: Provider, connection: Connection, key: String, endpoint: String,
    reveal: Boolean, busy: Boolean, error: String?, editKey: (String) -> Unit,
    editEndpoint: (String) -> Unit, toggleReveal: () -> Unit, paste: () -> Unit,
    save: () -> Unit, pickModel: () -> Unit
) {
    var providers by remember { mutableStateOf(false) }
    var localKey by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp).testTag("setup-connection")) {
        val tight = maxHeight / LocalDensity.current.fontScale < 300.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (tight) 4.dp else 10.dp, Alignment.CenterVertically)) {
            if (!tight) Text("Подключение модели", style = MaterialTheme.typography.headlineSmall)
            Surface(onClick = { providers = true }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("setup-provider")) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (provider == Provider.LOCAL) Icons.Outlined.Computer else Icons.Outlined.Hub, null, tint = MaterialTheme.colorScheme.primary)
                    Text(provider.label, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (tight) IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, "О ключах и подключении", Modifier.size(20.dp)) }
                    Icon(Icons.Outlined.ExpandMore, "Выбрать провайдера")
                }
            }
            if (provider == Provider.LOCAL) OutlinedTextField(endpoint, editEndpoint, Modifier.fillMaxWidth().testTag("setup-endpoint"), singleLine = true, label = { Text("Адрес API") }, placeholder = { Text("http://192.168.1.10:11434/v1/") }, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), trailingIcon = { IconButton(onClick = { localKey = true }) { Icon(Icons.Outlined.Key, "Ключ сервера") } })
            else SetupKeyField(key, editKey, reveal, toggleReveal, paste, Modifier.testTag("setup-key"))
            if (busy) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LumaLoading(Modifier.size(20.dp))
                Text("Модели · до 20 с", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { graph.cancelCatalog() }) { Text("Отмена") }
            } else Button(onClick = { keyboard?.hide(); save() }, enabled = (!provider.keyRequired || key.isNotBlank()) && (provider != Provider.LOCAL || endpoint.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-save")) {
                Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (connection.models.isNotEmpty()) "Обновить модели" else if (provider == Provider.LOCAL) "Подключить сервер" else "Сохранить ключ")
            }
            OutlinedButton(onClick = pickModel, enabled = connection.models.isNotEmpty(), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-model")) {
                Text(connection.models.firstOrNull { it.id == connection.model }?.name ?: "Выбрать модель", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.ExpandMore, null)
            }
            if (error != null) Surface(onClick = { showError = true }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("setup-error")) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp)); Text("Не удалось загрузить · подробнее", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall) }
            } else if (!tight) Text(if (connection.models.isEmpty()) t("Сохрани ключ, затем выбери модель из API.") else "Моделей: ${connection.models.size}. Выбор применяется сразу.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!tight) TextButton(onClick = { help = true }, contentPadding = PaddingValues(horizontal = 0.dp)) { Icon(Icons.Outlined.Info, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("О ключах и подключении", style = MaterialTheme.typography.labelMedium) }
        }
    }
    if (providers) AlertDialog(onDismissRequest = { providers = false }, title = { Text("Провайдер AI") }, text = {
        MotionLazyColumn { items(Provider.entries.filter {it!=Provider.DEVICE}) { p -> ListItem(headlineContent = { Text(p.label) }, trailingContent = { if (p == provider) Icon(Icons.Outlined.Check, "Выбрано") }, modifier = Modifier.clickable { graph.switchProvider(p); providers = false }) } }
    }, confirmButton = { TextButton(onClick = { providers = false }) { Text("Закрыть") } })
    if (localKey) AlertDialog(onDismissRequest = { localKey = false }, title = { Text("Ключ сервера") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(t("Оставь пустым, если сервер не требует авторизации."), style = MaterialTheme.typography.bodySmall); SetupKeyField(key, editKey, reveal, toggleReveal, paste) }
    }, confirmButton = { TextButton(onClick = { localKey = false }) { Text("Готово") } })
    if (showError && error != null) AlertDialog(onDismissRequest = { showError = false }, title = { Text("Подключение не удалось") }, text = { Text(error, Modifier.motionScroll()) }, confirmButton = { TextButton(onClick = { showError = false }) { Text("Понятно") } })
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("О ключах и подключении") }, text = { Text(t("Ключ хранится зашифрованным на телефоне. Облачный провайдер получает отправленные сообщения и материалы; платные запросы оплачиваются у него.\n\nЛокальный API: сервер может быть в той же сети, на самом телефоне или на ПК в другом месте. Укажи OpenAI-совместимый адрес с /v1/. Ключ сервера необязателен.\n\nУдалённо, например из другого города: установи Tailscale на ПК и телефон под одним аккаунтом, на ПК открой сервер для сети (у Ollama — переменная OLLAMA_HOST=0.0.0.0) и укажи адрес ПК в Tailscale: http://100.x.y.z:11434/v1/ или http://имя-пк.имя-сети.ts.net:11434/v1/. Трафик Tailscale шифруется. Не открывай сервер в интернет без пароля: у Ollama нет своей защиты. Для адреса в интернете Luma требует HTTPS.\n\nВыбор модели и все параметры можно изменить во вкладке «Модели»."), Modifier.motionScroll()) }, confirmButton = { TextButton(onClick = { help = false }) { Text("Понятно") } })
}

@Composable private fun SetupKeyField(key: String, edit: (String) -> Unit, reveal: Boolean, toggle: () -> Unit, paste: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(key, edit, modifier.fillMaxWidth(), label = { Text("API-ключ") }, singleLine = true, shape = MaterialTheme.shapes.medium,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { Row { IconButton(onClick = paste) { Icon(Icons.Outlined.ContentPaste, "Вставить из буфера") }; IconButton(onClick = toggle) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Скрыть ключ" else "Показать ключ") } } })
}
@Composable private fun ModelPicker(models: List<ModelInfo>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(t("Выберите модель"), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 16.dp), placeholder = { Text("Поиск по названию") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = MaterialTheme.shapes.medium)
            val filtered = remember(search, models) { models.filter { it.id.contains(search, true) || it.name.contains(search, true) } }
            MotionLazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                items(filtered, key = { it.id }) { model ->
                    ListItem(headlineContent = { Text(model.name) }, supportingContent = { Text(model.id, maxLines = 2, overflow = TextOverflow.Ellipsis) }, trailingContent = { if (model.id == selected) Icon(Icons.Outlined.CheckCircle, "Выбрано", tint = MaterialTheme.colorScheme.primary) else if (model.vision) Icon(Icons.Outlined.Image, "Изображения", tint = MaterialTheme.colorScheme.onSurfaceVariant) }, modifier = Modifier.clickable { onSelect(model.id) })
                }
                if (filtered.isEmpty()) item { Text("Ничего не найдено", Modifier.padding(20.dp)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** «Проверить подключение»: one short real request to the chosen model, with its reply and speed. */
@Composable private fun ConnectionCheckCard(graph: AppGraph) {
    val check by graph.connectionCheck.collectAsStateWithLifecycle()
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    // A result belongs to the model it was made with; switching models clears it.
    LaunchedEffect(settings.provider, settings.connection().model) { if (!check.busy) graph.connectionCheck.value = dev.luma.agent.ConnectionCheck() }
    SoftCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Проверка связи", style = MaterialTheme.typography.titleMedium)
                Text("Короткий запрос к выбранной модели", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { graph.checkConnection() }, enabled = !check.busy, modifier = Modifier.testTag("check-connection")) {
                if (check.busy) LumaLoading(Modifier.size(16.dp)) else Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Проверить")
            }
        }
        check.error?.let { error ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("check-error"))
            }
        }
        check.reply?.let { reply ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                Column {
                    Text("Работает" + (check.stats?.let { s -> " · ${dev.luma.agent.core.formatSeconds(s.totalMs)}" + (s.outputPerSecond?.let { " · ↓ ${dev.luma.agent.core.formatRate(it)} т/с" } ?: "") } ?: ""),
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("check-ok"))
                    if (reply.isNotBlank()) Text("«${reply.trim()}»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}
