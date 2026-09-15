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

    /**
     * Что успевает случиться, пока система гасит карточку: владелец доставки в этот миг держит
     * прочитанное до системы, и гонку в этом окне можно смоделировать точно.
     */
    var onDismiss: (suspend (NotificationKey) -> Unit)? = null

    /** То же для показа: человек отвечает, пока система рисует карточку. */
    var onShow: (suspend (Reminder) -> Unit)? = null

    override suspend fun show(reminder: Reminder): Delivery {
        if (reminder.key in failing) error("показ сорвался: ${reminder.key}")
        if (!allowed) return Delivery.NOT_ALLOWED
        if (reminder.key in vanished) return Delivery.SUBJECT_GONE
        onShow?.invoke(reminder)
        shown += reminder
        return Delivery.SHOWN
    }

    /** Ключи, первое гашение которых бросает: система не приняла отмену, карточка осталась висеть. */
    val dismissFailingOnce = mutableSetOf<NotificationKey>()

    override suspend fun dismiss(key: NotificationKey) {
        if (dismissFailingOnce.remove(key)) error("гашение сорвалось: $key")
        dismissed += key
        onDismiss?.invoke(key)
    }
}

/**
 * Постановки, какими их видит порт: [exactAt] и [inexactAt] — что стоит у каждой точности,
 * точная просьба и приблизительная — разные просьбы к системе, и одна другую не заменяет
 * (PLAN D8). [wakeAt] — ближайшая из двух, то, к чему система разбудит первым; [exact] — её
 * точность.
 */
class FakeReminders(override val canBeExact: Boolean = true) : ReminderAlarms {
    var exactAt: Instant? = null
        private set
    var inexactAt: Instant? = null
        private set
    val settings = mutableListOf<Instant>()

    val wakeAt: Instant? get() = listOfNotNull(exactAt, inexactAt).minOrNull()
    val exact: Boolean get() = exactAt != null && exactAt == wakeAt

    override suspend fun wakeAt(at: Instant, exact: Boolean) {
        if (exact) exactAt = at else inexactAt = at
        settings += at
    }

    override suspend fun stopWaking(exact: Boolean) {
        if (exact) exactAt = null else inexactAt = null
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
