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
                graph.agent.stop("Выбранная папка изменилась. Запустите задачу заново.")
                graph.update { it.copy(folder = uri.toString()) }
            } catch (e: Exception) { graph.uiMessage.value = "Не удалось сохранить доступ. Выберите доступную для записи папку." }
        }
    }
    // The same description the agent gets: name, location, rights and whether access still works.
    val workspace by produceState(dev.luma.agent.core.WorkspaceInfo(if (settings.folder.isBlank()) dev.luma.agent.core.WorkspaceInfo.NOT_SELECTED else dev.luma.agent.core.WorkspaceInfo.READY), settings.folder, resumed) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.luma.agent.core.Workspace.describe(context, settings.folder) }
    }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> resumed++; if (!allowed) graph.uiMessage.value = "Без «Устройств поблизости» Luma открывает настройки Bluetooth" }
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
        item { PageTitle("ДОСТУП", "Разрешения агента", "Обычному чату они не нужны. Включайте только то, что понадобится агенту.") }
        item {
            AccessCard(Icons.Outlined.TouchApp, "Управление экраном", if (connected) "Luma подключена к специальным возможностям" else "Чтение элементов, нажатия, прокрутка и ввод текста", connected, if (connected) "Настройки службы" else "Как включить") { explanation = true }
        }
        item {
            AccessCard(Icons.Outlined.FolderOpen, "Выбранная папка", when (workspace.status) {
                dev.luma.agent.core.WorkspaceInfo.NOT_SELECTED -> "Агент видит только выбранную папку. Для модели она называется workspace:/"
                dev.luma.agent.core.WorkspaceInfo.LOST -> "Доступ к «${workspace.name}» потерян — выберите папку заново"
                else -> "«${workspace.name}»" + (if (workspace.location.isNotEmpty()) " · ${workspace.location}" else "") +
                    "\nДля модели: workspace:/ · " + (if (workspace.canWrite) "чтение и запись" else "только чтение")
            }, workspace.usable, if (settings.folder.isBlank()) "Выбрать папку" else "Изменить папку") { folder.launch(null) }
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
        item { SystemActionsCard(context, resumed, nearby = { nearby.launch(Manifest.permission.BLUETOOTH_CONNECT) }) }
        item { BackgroundCard(context, resumed) { graph.uiMessage.value = it } }
        if (Build.VERSION.SDK_INT >= 33) item {
            AccessCard(Icons.Outlined.NotificationsNone, "Уведомление о работе", "Статус задачи и остановка из шторки", notificationsAllowed, if (notificationsAllowed) "Настройки уведомлений" else "Разрешить уведомления", requestNotification)
        }
        item {
            Text("Служба не записывает экран, пока нет запущенной задачи. Защищённые окна Android и парольные поля недоступны агенту.", Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, icon = { Icon(Icons.Outlined.AccessibilityNew, null) }, title = { Text("Включить управление") }, text = {
        Column(Modifier.motionScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("1. Открой «Специальные возможности».\n\n2. Найди «Luma · управление телефоном» в установленных службах.\n\n3. Прочитай системное описание и включи службу."))
            Text("Luma сможет читать доступные элементы экрана и выполнять запущенные тобой задачи. Отключить службу можно там же.", style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 33) {
                TextButton(onClick = { restrictedHelp = !restrictedHelp }) { Text("Если Android запрещает включение") }
                AnimatedVisibility(restrictedHelp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Android блокирует специальные возможности для APK, установленного вручную. Сначала попробуй включить службу, чтобы Android показал запрет. Затем: настройки приложения Luma → меню ⋮ → «Разрешить ограниченные настройки» → вернись в специальные возможности. Названия пунктов зависят от производителя.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
                                .onFailure { graph.uiMessage.value = "Откройте настройки → приложения → Luma вручную." }
                        }, modifier = Modifier.fillMaxWidth().testTag("open-app-info")) { Text("Открыть настройки Luma") }
                    }
                }
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

/** Special permissions some direct actions need. Each one is granted by the user on a system screen, never by the agent. */
@Composable private fun SystemActionsCard(context: android.content.Context, resumed: Int, nearby: () -> Unit) {
    val pkg = Uri.fromParts("package", context.packageName, null)
    fun open(intent: Intent) { runCatching { context.startActivity(intent) } }
    val write = remember(resumed) { Settings.System.canWrite(context) }
    val dnd = remember(resumed) { context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted }
    val bluetooth = remember(resumed) { Build.VERSION.SDK_INT < 31 || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    val exact = remember(resumed) { Build.VERSION.SDK_INT < 31 || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() }
    SoftCard {
        Text("Системные действия", style = MaterialTheme.typography.titleMedium)
        Text("Фонарик, громкость, медиа, будильники, ссылки и шторка работают без них. Эти разрешения нужны отдельным действиям; Android спрашивает о каждом сам.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PermissionRow(Icons.Outlined.BrightnessMedium, "Изменение системных настроек", if (write) "Разрешено: яркость и автоповорот" else "Для яркости и автоповорота", write, "system-write-settings") { open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)) }
        PermissionRow(Icons.Outlined.DoNotDisturbOn, "«Не беспокоить»", if (dnd) "Разрешено" else "Для режима «Не беспокоить» и звонка в нём", dnd, "system-dnd") { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        if (Build.VERSION.SDK_INT >= 31) PermissionRow(Icons.Outlined.Bluetooth, "Устройства поблизости", if (bluetooth) "Разрешено: запрос на включение Bluetooth" else "Чтобы включать Bluetooth одним подтверждением", bluetooth, "system-nearby", nearby)
        if (Build.VERSION.SDK_INT >= 31) PermissionRow(Icons.Outlined.Alarm, "Будильники и напоминания", if (exact) "Напоминания Luma точно в срок" else "Без него напоминание может прийти с опозданием до 10 минут", exact, "system-exact-alarms") {
            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)) }
        Text("Wi‑Fi, мобильный интернет и NFC Android 10+ переключает только в своей панели; геолокацию, авиарежим и точку доступа — только в настройках. Luma откроет нужное место, а переключите вы.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
/** Optional: Luma keeps working with the screen off or in another app. Each item opens Android's own screen. */
@Composable private fun BackgroundCard(context: android.content.Context, resumed: Int, message: (String) -> Unit) {
    val status = remember(resumed) { dev.luma.agent.core.BackgroundAccess.status(context) }
    fun open(intents: List<Intent>) { if (!dev.luma.agent.core.BackgroundAccess.open(context, intents)) message("Откройте настройки → Приложения → Luma вручную.") }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Работа в фоне", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("необязательно", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Без этого агент тоже работает, но Android может приостановить долгую задачу или ответ локальной модели, когда экран выключен или открыто другое приложение. " +
            "Заряд расходуется только во время задачи.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PermissionRow(Icons.Outlined.BatteryChargingFull, "Без ограничений батареи",
            if (status.unrestrictedBattery) "Включено: Android не усыпит задачу" else "Отключить энергосбережение для Luma", status.unrestrictedBattery, "background-battery") {
            open(dev.luma.agent.core.BackgroundAccess.batteryRequest(context)) }
        if (Build.VERSION.SDK_INT >= 28) PermissionRow(Icons.Outlined.Block, "Фоновая активность",
            if (status.backgroundRestricted) "Ограничена в настройках Android — снимите ограничение" else "Разрешена", !status.backgroundRestricted, "background-activity") {
            open(dev.luma.agent.core.BackgroundAccess.appSettings(context)) }
        PermissionRow(Icons.Outlined.DataSaverOff, "Экономия трафика",
            if (status.dataSaverBlocks) "Запрещает Luma интернет в фоне — разрешите исключение" else "Не мешает Luma", !status.dataSaverBlocks, "background-data") {
            open(dev.luma.agent.core.BackgroundAccess.dataSaver(context)) }
        dev.luma.agent.core.BackgroundAccess.vendorHint()?.let { hint ->
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { open(dev.luma.agent.core.BackgroundAccess.appSettings(context)) }) { Text("Открыть настройки Luma") }
        }
    }
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
