package dev.luma.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.luma.agent.core.RunState
import kotlinx.coroutines.*

class AgentTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var completed = false
    private var watcher: Job? = null
    private val graph get() = (application as LumaApp).graph
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { graph.agent.stop(); return START_NOT_STICKY }
        val chat = intent?.getLongExtra("chat", 0) ?: 0
        val goal = intent?.getStringExtra("goal").orEmpty()
        if (!graph.consumeAgentRequest(intent?.getStringExtra("request"))) {
            if (!graph.agent.state.value.active) { completed = true; stopSelf() }
            return START_NOT_STICKY
        }
        if (chat == 0L || goal.isBlank()) { completed = true; stopSelf(); return START_NOT_STICKY }
        completed = false; watcher?.cancel()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("agent", "Работа агента", NotificationManager.IMPORTANCE_LOW))
        val notification = notification(RunState(stage = "Подготавливаю задачу"))
        if (Build.VERSION.SDK_INT >= 34) startForeground(11, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(11, notification)
        graph.agent.start(chat, goal) { completed = true; notifyFinished(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
        watcher = scope.launch { graph.agent.state.collect { state ->
            if (state.active && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED))
                getSystemService(NotificationManager::class.java).notify(11, notification(state))
        } }
        return START_NOT_STICKY
    }
    private fun notification(state: RunState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentTaskService::class.java).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (state.approval != null) "Нужно ваше подтверждение" else if (state.paused) "На паузе" else state.stage
        return Notification.Builder(this, "agent").setSmallIcon(R.drawable.ic_luma).setContentTitle("Luma выполняет задачу").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
    }
    /** While Luma is in the background, a finished task gets its own notification with sound and vibration. */
    private fun notifyFinished() {
        if (graph.appVisible) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val state = graph.agent.state.value
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("agent-done", "Задача завершена", NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true) })
        val time = if (state.startedAt > 0) " · " + dev.luma.agent.core.formatElapsed(android.os.SystemClock.elapsedRealtime() - state.startedAt) else ""
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(12, Notification.Builder(this, "agent-done").setSmallIcon(R.drawable.ic_luma)
            .setContentTitle(if (state.error != null) "Luma: нужна помощь" else "Luma закончила задачу$time")
            .setContentText(dev.luma.agent.core.Tone.adapt(state.error ?: "Шагов: ${state.step}. Откройте чат, чтобы посмотреть итог.", graph.vault.state.value.formal))
            .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
    }
    override fun onDestroy() { if (!completed) graph.agent.stop("Android остановил выполнение задачи."); scope.cancel(); super.onDestroy() }
}
