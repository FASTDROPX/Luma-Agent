package dev.luma.agent.ui

import dev.luma.agent.core.tr

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.luma.agent.AppGraph
import dev.luma.agent.core.BackgroundAccess
import dev.luma.agent.core.OptionalAccess

private fun icon(id: String): ImageVector = when (id) {
    "notifications" -> Icons.Outlined.NotificationsNone; "write_settings" -> Icons.Outlined.BrightnessMedium; "dnd" -> Icons.Outlined.DoNotDisturbOn
    "bluetooth", "nearby_scan" -> Icons.Outlined.Bluetooth; "exact_alarms" -> Icons.Outlined.Alarm; "battery" -> Icons.Outlined.BatteryChargingFull
    "background" -> Icons.Outlined.Block; "data_saver" -> Icons.Outlined.DataSaverOff; "camera" -> Icons.Outlined.PhotoCamera; "microphone" -> Icons.Outlined.Mic
    "location", "background_location" -> Icons.Outlined.LocationOn; "contacts" -> Icons.Outlined.Contacts; "calendar" -> Icons.Outlined.CalendarMonth
    "phone" -> Icons.Outlined.Call; "call_log" -> Icons.Outlined.History; "sms" -> Icons.Outlined.Sms; "photos" -> Icons.Outlined.PhotoLibrary
    "audio" -> Icons.Outlined.LibraryMusic; "activity" -> Icons.Outlined.DirectionsWalk; "sensors" -> Icons.Outlined.MonitorHeart; "nearby_wifi" -> Icons.Outlined.Wifi
    "all_files" -> Icons.Outlined.FolderOpen; "overlay" -> Icons.Outlined.Layers; "notification_listener" -> Icons.Outlined.NotificationsActive
    "usage" -> Icons.Outlined.QueryStats; "install" -> Icons.Outlined.InstallMobile; "manage_media" -> Icons.Outlined.PermMedia
    else -> Icons.Outlined.Fullscreen
}

/**
 * «Необязательные доступы»: a collapsed card that opens into every optional access Android offers Luma, grouped by what it
 * is for; each row shows whether it is granted and opens Android's dialog or settings screen.
 */
@Composable fun OptionalAccessSection(graph: AppGraph, resumed: Int, refresh: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var open by rememberSaveable { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduced) snap() else spring(dampingRatio = .8f, stiffness = 500f), label = "chevron")
    val items = remember { OptionalAccess.items() }
    val granted = remember(resumed) { items.associate { it.id to it.granted(context) } }
    var asked by remember { mutableStateOf(emptyList<String>()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh()
        // Refused with «Don't ask again»: Android shows no dialog any more, only the app's settings can grant it.
        val blocked = asked.filter { result[it] != true && activity?.shouldShowRequestPermissionRationale(it) == false }
        if (blocked.isNotEmpty() && result.values.none { it }) {
            graph.uiMessage.value = tr("Android больше не показывает этот запрос — включите разрешение в настройках Luma.")
            BackgroundAccess.open(context, OptionalAccess.appDetails(context))
        }
    }
    fun ask(permissions: List<String>) { if (permissions.isNotEmpty()) { asked = permissions; request.launch(permissions.toTypedArray()) } }
    fun press(item: OptionalAccess.Item) {
        val runtime = item.runtime()
        when {
            item.id == "background_location" && granted["location"] != true -> graph.uiMessage.value = tr("Сначала разрешите обычное местоположение.")
            item.id == "background_location" && android.os.Build.VERSION.SDK_INT >= 30 -> BackgroundAccess.open(context, OptionalAccess.appDetails(context))
            runtime.isNotEmpty() && granted[item.id] != true -> ask(runtime)
            else -> if (!BackgroundAccess.open(context, item.screens(context))) graph.uiMessage.value = tr("Откройте настройки → Приложения → Luma вручную.")
        }
    }
    SoftCard(Modifier.testTag("optional-access")) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 4.dp).testTag("optional-access-toggle"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(tr("Необязательные доступы"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Выдано {0} из {1} · агент работает и без них" ,granted.values.count { it },items.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, if (open) tr("Свернуть") else tr("Развернуть"), Modifier.rotate(turn))
        }
        AnimatedVisibility(open, enter = if (reduced) EnterTransition.None else expandVertically(spring(stiffness = 500f)) + fadeIn(tween(200)),
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(200)) + fadeOut(tween(150))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionalAccess.Group.entries.forEach { group ->
                    val rows = items.filter { it.group == group }
                    if (rows.isEmpty()) return@forEach
                    Spacer(Modifier.height(4.dp))
                    Text(tr(group.title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(tr(group.note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rows.forEach { item ->
                        AccessRow(icon(item.id), item.title, item.purpose, granted[item.id] == true, "access-${item.id}") { press(item) }
                    }
                    if (group == OptionalAccess.Group.BACKGROUND) BackgroundAccess.vendorHint()?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(tr("Специальные возможности, папка для файлов и снимки экрана настраиваются выше. Роли по умолчанию (звонилка, SMS, лаунчер), администратор устройства, VPN и клавиатура сюда не входят: ") +
                    tr("они меняют работу всего телефона, а Luma их не использует."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun AccessRow(icon: ImageVector, title: String, subtitle: String, granted: Boolean, tag: String, click: () -> Unit) {
    Surface(onClick = click, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(tr(title), style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight, if (granted) tr("Выдано") else tr("Выдать"), Modifier.size(20.dp),
                tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
