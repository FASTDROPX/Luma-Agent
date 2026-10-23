package dev.luma.agent

import android.service.notification.NotificationListenerService

/**
 * Active notifications are read only by an explicit agent tool after access is granted.
 * No notification history is recorded; the service reference is cleared on disconnection.
 */
class LumaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null; super.onListenerDisconnected() }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    companion object { @Volatile var instance: LumaNotificationListener? = null; private set }
}
