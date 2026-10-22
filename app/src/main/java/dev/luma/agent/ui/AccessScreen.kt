package dev.luma.agent.ui

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
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) notificationsAllowed = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                graph.agent.stop("Выбранная папка изменилась. Запустите задачу заново.")
                graph.update { it.copy(folder = uri.toString()) }
            } catch (e: Exception) { graph.uiMessage.value = "Не удалось сохранить доступ. Выберите доступную для записи папку." }
        }
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> notificationsAllowed = allowed; graph.uiMessage.value = if (allowed) "Уведомления включены" else "Остановить агента можно в приложении и панели" }
    val requestNotification: () -> Unit = {
        if (notificationsAllowed) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) else notification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val addTile: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                context.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(context, PanelTileService::class.java), "Панель Luma", Icon.createWithResource(context, dev.luma.agent.R.drawable.ic_luma), context.mainExecutor) { result ->
                    graph.uiMessage.value = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) "Плитка доступна в пункте управления" else "Плитку можно добавить вручную через редактирование пункта управления"
                }
            } catch (e: Exception) { graph.uiMessage.value = "Добавьте плитку «Панель Luma» через редактирование пункта управления." }
        } else graph.uiMessage.value = "Откройте пункт управления → редактирование плиток → перетащите «Панель Luma»."
    }
    if (onboarding) CompactAccess(graph, connected, notificationsAllowed, { explanation = true }, { folder.launch(null) }, requestNotification, addTile)
    else MotionLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("ДОСТУП", "Ты управляешь доступом", "Для обычного чата разрешения не нужны. Включи только возможности, которые понадобятся агенту.") }
        item {
            AccessCard(Icons.Outlined.TouchApp, "Управление экраном", if (connected) "Luma подключена к специальным возможностям" else "Чтение элементов, нажатия, прокрутка и ввод текста", connected, if (connected) "Настройки службы" else "Как включить") { explanation = true }
        }
        item {
            AccessCard(Icons.Outlined.FolderOpen, "Выбранная папка", if (settings.folder.isBlank()) "Доступ только к папке, которую выберешь ты" else "Папка: ${Uri.parse(settings.folder).lastPathSegment?.substringAfter(':').orEmpty()}", settings.folder.isNotBlank(), if (settings.folder.isBlank()) "Выбрать папку" else "Изменить папку") { folder.launch(null) }
            if (settings.folder.isNotBlank()) TextButton(onClick = {
                graph.agent.stop("Доступ к папке отключён.")
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(settings.folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                graph.update { it.copy(folder = "") }
            }) { Text("Отключить доступ к папке") }
        }
        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ScreenshotMonitor, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Снимки для модели", style = MaterialTheme.typography.titleMedium); Text(if (Build.VERSION.SDK_INT >= 30) "Передавать изображение экрана во время задачи" else "На этой версии Android доступно дерево элементов без снимка", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = settings.screenshots, onCheckedChange = { graph.update { s -> s.copy(screenshots = it) } }, enabled = Build.VERSION.SDK_INT >= 30) }
                Text("Если используется облачная модель, содержимое экрана получает выбранный провайдер. Снимки не сохраняются в историю. Нужна модель с поддержкой изображений.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.BubbleChart, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Плавающая панель", style = MaterialTheme.typography.titleMedium); Text("Появляется только во время задачи", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(settings.panel, { graph.update { s -> s.copy(panel = it) } }) }
                Text("Пауза, подтверждения и кнопка «Стоп» рядом с текущим приложением. Панель можно перетащить. Отдельное разрешение поверх приложений не требуется.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = addTile, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.DashboardCustomize, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Добавить плитку") }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) item {
            AccessCard(Icons.Outlined.NotificationsNone, "Уведомление о работе", "Статус задачи и остановка из шторки", notificationsAllowed, if (notificationsAllowed) "Настройки уведомлений" else "Разрешить уведомления", requestNotification)
        }
        item {
            Text("Служба не записывает экран, пока нет запущенной задачи. Защищённые окна Android и парольные поля недоступны агенту.", Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, icon = { Icon(Icons.Outlined.AccessibilityNew, null) }, title = { Text("Включить управление") }, text = {
        Column(Modifier.motionScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("1. Открой «Специальные возможности».\n\n2. Найди «Luma · управление телефоном» в установленных службах.\n\n3. Прочитай системное описание и включи службу.")
            Text("Luma сможет читать доступные элементы экрана и выполнять запущенные тобой задачи. Отключить службу можно там же.", style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 33) {
                TextButton(onClick = { restrictedHelp = !restrictedHelp }) { Text("Если Android запрещает включение") }
                AnimatedVisibility(restrictedHelp) { Text("Для APK, установленного вручную: настройки Android → приложения → Luma → меню ⋮ → «Разрешить ограниченные настройки», если этот пункт доступен. Затем вернись в специальные возможности. Названия пунктов зависят от производителя.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = { TextButton(onClick = { explanation = false; runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { graph.uiMessage.value = "Откройте специальные возможности вручную в настройках Android." } }) { Text("Открыть настройки") } }, dismissButton = { TextButton(onClick = { explanation = false }) { Text("Позже") } })
}

@Composable private fun CompactAccess(graph: AppGraph, connected: Boolean, notifications: Boolean, screen: () -> Unit, folder: () -> Unit, notify: () -> Unit, addTile: () -> Unit) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp).testTag("setup-access")) {
        val tight = maxHeight / LocalDensity.current.fontScale < 330.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (tight) 6.dp else 12.dp, Alignment.CenterVertically)) {
            Text("Доступ для агента", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
            Text("Для чата разрешения не нужны.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PermissionRow(Icons.Outlined.TouchApp, "Управление экраном", if (connected) "Включено" else "Включить службу Luma", connected, "setup-screen-access", screen)
            PermissionRow(Icons.Outlined.FolderOpen, "Файлы", if (settings.folder.isBlank()) "Выбрать доступную папку" else "Папка выбрана", settings.folder.isNotBlank(), "setup-folder-access", folder)
            if (Build.VERSION.SDK_INT >= 33) PermissionRow(Icons.Outlined.NotificationsNone, "Уведомления", if (notifications) "Разрешены" else "Разрешить статус и остановку", notifications, "setup-notification-access", notify)
            OutlinedButton(onClick = { options = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-access-options")) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Панель и снимки") }
            if (!tight) Text("Каждый доступ включается отдельно. Изменить его можно во вкладке «Доступ».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text("Панель и снимки") }, text = {
        Column(Modifier.motionScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Плавающая панель", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(settings.panel, { value -> graph.update { it.copy(panel = value) } }) }
            Text("Появляется только во время задачи. Служба Luma показывает её без отдельного разрешения поверх приложений.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = addTile, modifier = Modifier.fillMaxWidth()) { Text("Добавить плитку") }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Снимки для модели", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(settings.screenshots, { value -> graph.update { it.copy(screenshots = value) } }, enabled = Build.VERSION.SDK_INT >= 30) }
            Text(if (Build.VERSION.SDK_INT >= 30) "Нужны включённая служба Luma и модель с поддержкой изображений. Облачный провайдер получает содержимое экрана во время задачи. Снимки не сохраняются в историю." else "На Android 8–10 доступны элементы экрана без снимков.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { options = false }) { Text("Готово") } })
}

@Composable private fun PermissionRow(icon: ImageVector, title: String, subtitle: String, granted: Boolean, tag: String, click: () -> Unit) {
    Surface(onClick = click, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight, if (granted) "Разрешено" else "Настроить", Modifier.size(20.dp), tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun AccessCard(icon: ImageVector, title: String, text: String, enabled: Boolean, action: String, onClick: () -> Unit) {
    SoftCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (enabled) Icon(Icons.Outlined.CheckCircle, "Подключено", tint = MaterialTheme.colorScheme.secondary)
        }
        FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action) }
    }
}
