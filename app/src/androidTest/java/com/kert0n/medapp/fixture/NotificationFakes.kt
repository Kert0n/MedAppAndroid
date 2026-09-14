package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.notification.Delivery
import com.kert0n.medapp.domain.notification.Freshness
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.notification.ReminderAlarms
import java.time.Instant

/**
 * Показы, какими их видит порт: что показано, что погашено. Разрешение можно отнять ([allowed]) —
 * это причина **структурная**; можно уронить показ ([failing]) — это сбой; можно сделать вид, что
 * повода больше нет ([vanished]) — коробки или курса не нашлось. Поведение у трёх случаев разное.
 */
class FakeNotifier(var allowed: Boolean = true) : Notifier {
    val shown = mutableListOf<Reminder>()
    val dismissed = mutableListOf<NotificationKey>()

    /** Ключи, показ которых бросает: сбой хранения или системы посреди прохода. */
    val failing = mutableSetOf<NotificationKey>()

    /** Ключи, у которых повода больше нет: текст собрать не из чего. */
    val vanished = mutableSetOf<NotificationKey>()

    override suspend fun show(reminder: Reminder): Delivery {
        if (reminder.key in failing) error("показ сорвался: ${reminder.key}")
        if (!allowed) return Delivery.NOT_ALLOWED
        if (reminder.key in vanished) return Delivery.SUBJECT_GONE
        shown += reminder
        return Delivery.SHOWN
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

/**
 * Свежесть, которой не нужна сеть: проверкам важно, что её спросили, а не что она принесла.
 *
 * [meanwhile] — то, что успевает случиться, пока владелец доставки ждёт заход. Это ровно окно, в
 * котором он держит прочитанное до сети, и гонку в нём можно смоделировать точно, а не ожиданием.
 */
class FakeFreshness(var meanwhile: (suspend () -> Unit)? = null) : Freshness {
    var asked = 0
        private set

    override suspend fun refreshBriefly(within: java.time.Duration) {
        asked++
        meanwhile?.invoke()
    }
}
