package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.notification.ReminderAlarms
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import java.time.Clock
import javax.inject.Inject

/**
 * Сказать и запомнить (PLAN D8). Обязательство сперва заводится, потом показывается, и только
 * после показа помечается сказанным: падение между показом и отметкой оставляет его `DUE`, и
 * следующий проход повторяет показ той же парой `tag/id`, которую система склеивает, — обратный
 * порядок терял бы уведомление (C1).
 *
 * Показанное вторым разом не показывается: обещание исполнено. Повтор бывает только тогда, когда
 * человек отложил, — у обязательства появляется новый срок.
 */
class NotificationDelivery @Inject constructor(
    private val notifier: Notifier,
    private val alarms: ReminderAlarms,
    private val reminders: ReminderStorageRepository,
    private val clock: Clock
) {

    /** Сколько сказано этим проходом. */
    suspend fun deliver(planned: List<Reminder>): Int {
        reminders.raiseAll(planned)
        val now = clock.instant()
        var shown = 0
        for (key in planned.map { it.key }) {
            val reminder = reminders.find(key) ?: continue
            if (!reminder.isDue(now)) continue
            if (!notifier.show(reminder)) continue
            reminders.markShown(key, clock.instant())
            shown++
        }
        return shown
    }

    /**
     * Будильники на напоминания — по **сохранённому** сроку, а не по пересчитанному: отложенное
     * человеком обязательство проход дня к плановому моменту не возвращает (PLAN D8).
     */
    suspend fun arm(planned: List<Reminder>) {
        reminders.raiseAll(planned)
        for (key in planned.map { it.key }) {
            val stored = reminders.find(key) ?: continue
            if (stored.state == Reminder.State.DUE) alarms.schedule(key, stored.dueAt)
        }
    }

    /** Повод исчез — гасим показанное, снимаем будильник и забываем обязательство. */
    suspend fun withdraw(key: NotificationKey) {
        notifier.dismiss(key)
        alarms.cancel(key)
        reminders.forget(listOf(key))
    }
}
