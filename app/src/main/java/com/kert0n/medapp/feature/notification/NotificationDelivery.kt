package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.PlannedNotification
import com.kert0n.medapp.domain.notification.ReminderAlarms
import com.kert0n.medapp.storage.server.NotificationLogStorageRepository
import java.time.Clock
import javax.inject.Inject

/**
 * Показать и запомнить (PLAN D8). Журнал — **после** показа: падение между ними даёт на следующем
 * проходе повтор той же парой `tag/id`, который система склеивает, а обратный порядок терял бы
 * уведомление (C1). Показанное тем же способом второй раз не показывается — кроме напоминания о
 * приёме: им управляет будильник, и отложенное приходит снова.
 */
class NotificationDelivery @Inject constructor(
    private val notifier: Notifier,
    private val alarms: ReminderAlarms,
    private val log: NotificationLogStorageRepository,
    private val clock: Clock
) {

    /** Сколько показано этим проходом. */
    suspend fun deliver(notifications: List<PlannedNotification>): Int {
        var shown = 0
        for (notification in notifications) {
            if (notification.kind.onceOnly && log.wasShown(notification.key, notification.delivery)) continue
            if (!notifier.show(notification)) continue
            log.remember(notification.key, notification.delivery, clock.instant())
            shown++
        }
        return shown
    }

    /** Будильники на напоминания: тот же ключ — тот же будильник, повторная постановка его переставляет. */
    suspend fun arm(reminders: List<PlannedNotification>) {
        for (reminder in reminders) alarms.schedule(reminder.key, reminder.dueAt)
    }

    /** Повод исчез — гасим показанное и снимаем будильник. */
    suspend fun withdraw(key: NotificationKey) {
        notifier.dismiss(key)
        alarms.cancel(key)
        log.forget(key)
    }

    private val NotificationKind.onceOnly: Boolean get() = this != NotificationKind.INTAKE_DUE
}
