package dev.luma.agent

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*

class PanelTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listener: Job? = null
    private val graph get() = (application as LumaApp).graph
    override fun onStartListening() { super.onStartListening(); listener?.cancel(); listener = scope.launch { graph.vault.state.collect { refresh() } }; refresh() }
    override fun onStopListening() { listener?.cancel(); super.onStopListening() }
    override fun onClick() {
        super.onClick()
        if (!AgentAccessibilityService.connected.value) {
            val intent = Intent(this, MainActivity::class.java).putExtra("screen", "access").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 20, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) else launchBeforeAndroid14(intent)
        } else scope.launch { runCatching { graph.vault.update { it.copy(panel = !it.panel) } }; refresh() }
    }
    // The PendingIntent overload exists only on Android 14+. Older systems require Intent.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchBeforeAndroid14(intent: Intent) { startActivityAndCollapse(intent) }
    private fun refresh() {
        val tile = qsTile ?: return
        tile.label = "Панель Luma"; tile.state = if (graph.vault.state.value.panel) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (graph.vault.state.value.panel) "Только во время задачи" else "Скрыта"
        tile.updateTile()
    }
    override fun onDestroy() { listener?.cancel(); scope.cancel(); super.onDestroy() }
}
