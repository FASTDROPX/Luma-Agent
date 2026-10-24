package dev.luma.agent.ui

import dev.luma.agent.core.tr

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.*

@Composable fun AccessScreen(graph: AppGraph, onboarding: Boolean = false) {
    val context = LocalContext.current
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val connected by AgentAccessibilityService.connected.collectAsStateWithLifecycle()
    var explanation by remember { mutableStateOf(false) }
    var restrictedHelp by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var resumed by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { resumed++; notificationsAllowed = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED } }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                graph.agent.stop(tr("Выбранная папка изменилась. Запустите задачу заново."))
                graph.update { it.copy(folder = uri.toString()) }
            } catch (e: Exception) { graph.uiMessage.value = tr("Не удалось сохранить доступ. Выберите доступную для записи папку.") }
        }
    }
    // The same description the agent gets: name, location, rights and whether access still works.
    val workspace by produceState(dev.luma.agent.core.WorkspaceInfo(if (settings.folder.isBlank()) dev.luma.agent.core.WorkspaceInfo.NOT_SELECTED else dev.luma.agent.core.WorkspaceInfo.READY), settings.folder, resumed) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.luma.agent.core.Workspace.describe(context, settings.folder) }
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> notificationsAllowed = allowed; graph.uiMessage.value = if (allowed) tr("Уведомления включены") else tr("Остановить агента можно в приложении и панели") }
    val requestNotification: () -> Unit = {
        if (notificationsAllowed) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) else notification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val addTile: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                context.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(context, PanelTileService::class.java), tr("Панель Luma"), Icon.createWithResource(context, dev.luma.agent.R.drawable.ic_luma), context.mainExecutor) { result ->
                    graph.uiMessage.value = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) tr("Плитка доступна в пункте управления") else tr("Плитку можно добавить вручную через редактирование пункта управления")
                }
            } catch (e: Exception) { graph.uiMessage.value = tr("Добавьте плитку «Панель Luma» через редактирование пункта управления.") }
        } else graph.uiMessage.value = tr("Откройте пункт управления → редактирование плиток → перетащите «Панель Luma».")
    }
    if (onboarding) CompactAccess(graph, connected, notificationsAllowed, { explanation = true }, { folder.launch(null) }, requestNotification, addTile)
    else MotionLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle(tr("ДОСТУП"), tr("Разрешения агента"), tr("Обычному чату они не нужны. Включайте только то, что понадобится агенту.")) }
        item { AllAccessButton(graph) { resumed++ } }
        item {
            AccessCard(Icons.Outlined.TouchApp, tr("Управление экраном"), if (connected) tr("Luma подключена к специальным возможностям") else tr("Чтение элементов, нажатия, прокрутка и ввод текста"), connected, if (connected) tr("Настройки службы") else tr("Как включить")) { explanation = true }
        }
        item {
            AccessCard(Icons.Outlined.FolderOpen, tr("Выбранная папка"), when (workspace.status) {
                dev.luma.agent.core.WorkspaceInfo.NOT_SELECTED -> tr("Агент видит только выбранную папку. Для модели она называется workspace:/")
                dev.luma.agent.core.WorkspaceInfo.LOST -> tr("Доступ к «{0}» потерян — выберите папку заново" ,workspace.name)
                else -> "«${workspace.name}»" + (if (workspace.location.isNotEmpty()) " · ${workspace.location}" else "") +
                    tr("\nДля модели: workspace:/ · ") + (if (workspace.canWrite) tr("чтение и запись") else tr("только чтение"))
            }, workspace.usable, if (settings.folder.isBlank()) tr("Выбрать папку") else tr("Изменить папку")) { folder.launch(null) }
            if (settings.folder.isNotBlank()) TextButton(onClick = {
                graph.agent.stop(tr("Доступ к папке отключён."))
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(settings.folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                graph.update { it.copy(folder = "") }
            }) { Text(tr("Отключить доступ к папке")) }
        }
        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ScreenshotMonitor, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(tr("Снимки для модели"), style = MaterialTheme.typography.titleMedium); Text(if (Build.VERSION.SDK_INT >= 30) tr("Передавать изображение экрана во время задачи") else tr("На этой версии Android доступно дерево элементов без снимка"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = settings.screenshots, onCheckedChange = { graph.update { s -> s.copy(screenshots = it) } }, enabled = Build.VERSION.SDK_INT >= 30) }
                Text(tr("Если используется облачная модель, содержимое экрана получает выбранный провайдер. Снимки не сохраняются в историю. Нужна модель с поддержкой изображений."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.BubbleChart, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(tr("Плавающая панель"), style = MaterialTheme.typography.titleMedium); Text(tr("Появляется только во время задачи"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(settings.panel, { graph.update { s -> s.copy(panel = it) } }) }
                Text(tr("Пауза, подтверждения и кнопка «Стоп» рядом с текущим приложением. Панель можно перетащить. Отдельное разрешение поверх приложений не требуется."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = addTile, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.DashboardCustomize, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Добавить плитку")) }
            }
        }
        // Notifications, special permissions, background work and everything else Android offers: one collapsible card.
        item { OptionalAccessSection(graph, resumed) { resumed++ } }
        item {
            Text(tr("Служба не записывает экран, пока нет запущенной задачи. Защищённые окна Android и парольные поля недоступны агенту."), Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, icon = { Icon(Icons.Outlined.AccessibilityNew, null) }, title = { Text(tr("Включить управление")) }, text = {
        Column(Modifier.motionScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t(tr("1. Открой «Специальные возможности».\n\n2. Найди «Luma · управление телефоном» в установленных службах.\n\n3. Прочитай системное описание и включи службу.")))
            Text(tr("Luma сможет читать доступные элементы экрана и выполнять запущенные тобой задачи. Отключить службу можно там же."), style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 33) {
                TextButton(onClick = { restrictedHelp = !restrictedHelp }) { Text(tr("Если Android запрещает включение")) }
                AnimatedVisibility(restrictedHelp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Android блокирует специальные возможности для APK, установленного вручную. Сначала попробуй включить службу, чтобы Android показал запрет. Затем: настройки приложения Luma → меню ⋮ → «Разрешить ограниченные настройки» → вернись в специальные возможности. Названия пунктов зависят от производителя."), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
                                .onFailure { graph.uiMessage.value = tr("Откройте настройки → приложения → Luma вручную.") }
                        }, modifier = Modifier.fillMaxWidth().testTag("open-app-info")) { Text(tr("Открыть настройки Luma")) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { explanation = false; runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { graph.uiMessage.value = tr("Откройте специальные возможности вручную в настройках Android.") } }) { Text(tr("Открыть настройки")) } }, dismissButton = { TextButton(onClick = { explanation = false }) { Text(tr("Позже")) } })
}

@Composable private fun CompactAccess(graph: AppGraph, connected: Boolean, notifications: Boolean, screen: () -> Unit, folder: () -> Unit, notify: () -> Unit, addTile: () -> Unit) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp).testTag("setup-access")) {
        val tight = maxHeight / LocalDensity.current.fontScale < 330.dp
        Column(Modifier.fillMaxSize().motionScroll(), verticalArrangement = Arrangement.spacedBy(if (tight) 6.dp else 12.dp, Alignment.CenterVertically)) {
            Text(tr("Доступ для агента"), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
            Text(tr("Для чата разрешения не нужны."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AllAccessButton(graph)
            PermissionRow(Icons.Outlined.TouchApp, tr("Управление экраном"), if (connected) tr("Включено") else tr("Включить службу Luma"), connected, "setup-screen-access", screen)
            PermissionRow(Icons.Outlined.FolderOpen, tr("Файлы"), if (settings.folder.isBlank()) tr("Выбрать доступную папку") else tr("Папка выбрана"), settings.folder.isNotBlank(), "setup-folder-access", folder)
            if (Build.VERSION.SDK_INT >= 33) PermissionRow(Icons.Outlined.NotificationsNone, tr("Уведомления"), if (notifications) tr("Разрешены") else tr("Разрешить статус и остановку"), notifications, "setup-notification-access", notify)
            OutlinedButton(onClick = { options = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-access-options")) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Панель и снимки")) }
            if (!tight) Text(tr("Доступы можно выдать по одному или пройти все запросы подряд. Изменить их можно во вкладке «Доступ»."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(tr("Панель и снимки")) }, text = {
        Column(Modifier.motionScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Плавающая панель"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(settings.panel, { value -> graph.update { it.copy(panel = value) } }) }
            Text(tr("Появляется только во время задачи. Служба Luma показывает её без отдельного разрешения поверх приложений."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = addTile, modifier = Modifier.fillMaxWidth()) { Text(tr("Добавить плитку")) }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("Снимки для модели"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(settings.screenshots, { value -> graph.update { it.copy(screenshots = value) } }, enabled = Build.VERSION.SDK_INT >= 30) }
            Text(if (Build.VERSION.SDK_INT >= 30) tr("Нужны включённая служба Luma и модель с поддержкой изображений. Облачный провайдер получает содержимое экрана во время задачи. Снимки не сохраняются в историю.") else tr("На Android 8–10 доступны элементы экрана без снимков."), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { options = false }) { Text(tr("Готово")) } })
}

@Composable private fun PermissionRow(icon: ImageVector, title: String, subtitle: String, granted: Boolean, tag: String, click: () -> Unit) {
    Surface(onClick = click, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight, if (granted) tr("Разрешено") else tr("Настроить"), Modifier.size(20.dp), tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun AccessCard(icon: ImageVector, title: String, text: String, enabled: Boolean, action: String, onClick: () -> Unit) {
    SoftCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (enabled) Icon(Icons.Outlined.CheckCircle, tr("Подключено"), tint = MaterialTheme.colorScheme.secondary)
        }
        FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action) }
    }
}
