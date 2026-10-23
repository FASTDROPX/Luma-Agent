package dev.luma.agent

import android.service.notification.NotificationListenerService

/**
 * Exists so «Доступ к уведомлениям» can be granted to Luma in advance («Необязательные доступы» → «Про запас»).
 * It reads, stores and sends nothing: no Luma tool uses notifications of other apps yet.
 */
class LumaNotificationListener : NotificationListenerService()
