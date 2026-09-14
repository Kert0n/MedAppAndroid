package com.kert0n.medapp.feature.settings

import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.queue.SyncInterval

/**
 * Всё, что человек решил о поведении приложения, одной величиной (PLAN D8, E4): уведомления —
 * словами домена, интервал фоновых заходов — словами очереди. Умолчания — у самих частей.
 */
data class AppSettings(
    val notifications: NotificationSettings = NotificationSettings.DEFAULT,
    val syncInterval: SyncInterval = SyncInterval.DEFAULT
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}
