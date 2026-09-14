package com.kert0n.medapp.platform.notifications

import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import javax.inject.Inject

/** Пока настроек нет (B18) — умолчания D8; планирование читает их через порт уже сейчас. */
class DefaultNotificationSettings @Inject constructor() : NotificationSettingsSource {
    override suspend fun current(): NotificationSettings = NotificationSettings.DEFAULT
}
