package com.kert0n.medapp.platform.settings

import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.feature.settings.SettingsStore
import javax.inject.Inject

/** Доменный порт настроек уведомлений — чтением того же хранилища, что и у экрана (PLAN D8). */
class StoredNotificationSettings @Inject constructor(private val store: SettingsStore) : NotificationSettingsSource {
    override suspend fun current(): NotificationSettings = store.current().notifications
}
