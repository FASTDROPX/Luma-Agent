package dev.luma.agent

import dev.luma.agent.core.tr

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Explicitly enabled microphone session; never restarted from boot or after process death. */
class VoiceService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val graph get()=(application as LumaApp).graph
    private var wakeLock: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop") {graph.voice.disableAndStop();return START_NOT_STICKY}
        val mode=(intent?.getStringExtra("mode") ?: if(graph.vault.state.value.voice.alwaysListening) "wake" else null)?.takeIf {it in setOf("wake","conversation","dictation","read")} ?: run {stopSelf();return START_NOT_STICKY}
        try {
            val type=if(mode=="read") ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if(Build.VERSION.SDK_INT>=29) startForeground(74,notification(tr("Подготовка")),type) else startForeground(74,notification(tr("Подготовка")))
            if(mode!="read" && wakeLock==null) {
                // Listening is explicitly enabled and can last until the user stops it.
                @Suppress("WakelockTimeout")
                val lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Luma:voice").apply {acquire()}
                wakeLock=lock
            }
            graph.voice.begin(mode) {stopSelf(startId)}
        } catch(e: Exception) {graph.uiMessage.value=tr("Android не разрешил запуск голоса. Откройте Luma и проверьте доступ к микрофону.");stopSelf()}
        return if(graph.vault.state.value.voice.alwaysListening) START_STICKY else START_NOT_STICKY
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("voice",tr("Голос Luma"),NotificationManager.IMPORTANCE_LOW).apply {description=tr("Микрофон и разговорный режим");setSound(null,null)})
        scope.launch {graph.voice.state.map {it.active to it.phase}.distinctUntilChanged().collect {(active,phase) ->
            if(active) getSystemService(NotificationManager::class.java).notify(74,notification(phase))
        }}
    }
    private fun notification(status: String): Notification {
        val open=PendingIntent.getActivity(this,74,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,75,Intent(this,VoiceService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"voice").setSmallIcon(R.drawable.ic_luma).setContentTitle(tr("Luma · голос")).setContentText(tr(status))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null,tr("Остановить"),stop).build()).build()
    }
    override fun onDestroy() {scope.cancel();graph.voice.serviceDestroyed();wakeLock?.let {if(it.isHeld) it.release()};wakeLock=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
