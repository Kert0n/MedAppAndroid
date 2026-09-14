package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.PlannedNotification
import com.kert0n.medapp.domain.notification.ReminderAlarms
import java.time.Instant

/** Показы, какими их видит порт: что показано, что погашено; разрешение можно отнять. */
class FakeNotifier(var allowed: Boolean = true) : Notifier {
    val shown = mutableListOf<PlannedNotification>()
    val dismissed = mutableListOf<NotificationKey>()

    override suspend fun show(notification: PlannedNotification): Boolean {
        if (!allowed) return false
        shown += notification
        return true
    }

    override suspend fun dismiss(key: NotificationKey) {
        dismissed += key
    }
}

/** Будильники по ключу: последняя постановка побеждает, снятие убирает. */
class FakeReminders(override val canBeExact: Boolean = true) : ReminderAlarms {
    val scheduled = LinkedHashMap<NotificationKey, Instant>()

    override suspend fun schedule(key: NotificationKey, at: Instant) {
        scheduled[key] = at
    }

    override suspend fun cancel(key: NotificationKey) {
        scheduled.remove(key)
    }
}

class FakeSettings(var settings: NotificationSettings = NotificationSettings.DEFAULT) : NotificationSettingsSource {
    override suspend fun current(): NotificationSettings = settings
}
