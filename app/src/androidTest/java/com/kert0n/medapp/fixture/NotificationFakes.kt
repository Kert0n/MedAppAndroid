package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.notification.Freshness
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.notification.ReminderAlarms
import java.time.Instant

/** Показы, какими их видит порт: что показано, что погашено; разрешение можно отнять. */
class FakeNotifier(var allowed: Boolean = true) : Notifier {
    val shown = mutableListOf<Reminder>()
    val dismissed = mutableListOf<NotificationKey>()

    override suspend fun show(reminder: Reminder): Boolean {
        if (!allowed) return false
        shown += reminder
        return true
    }

    override suspend fun dismiss(key: NotificationKey) {
        dismissed += key
    }
}

/** Один будильник: последняя постановка побеждает, `stopWaking` его снимает. */
class FakeReminders(override val canBeExact: Boolean = true) : ReminderAlarms {
    var wakeAt: Instant? = null
        private set
    var exact: Boolean = false
        private set
    val settings = mutableListOf<Instant>()

    override suspend fun wakeAt(at: Instant, exact: Boolean) {
        wakeAt = at
        this.exact = exact
        settings += at
    }

    override suspend fun stopWaking() {
        wakeAt = null
    }
}

class FakeSettings(var settings: NotificationSettings = NotificationSettings.DEFAULT) : NotificationSettingsSource {
    override suspend fun current(): NotificationSettings = settings
}

/** Свежесть, которой не нужна сеть: проверкам важно, что её спросили, а не что она принесла. */
class FakeFreshness : Freshness {
    var asked = 0
        private set

    override suspend fun refreshBriefly(within: java.time.Duration) {
        asked++
    }
}
