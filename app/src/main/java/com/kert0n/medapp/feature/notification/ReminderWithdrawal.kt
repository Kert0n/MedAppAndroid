package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Notifier
import com.kert0n.medapp.domain.notification.ReminderAlarms
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Повод напоминать о пункте исчез — он отвечен или курса больше нет: показанное гасится,
 * будильник снимается (PLAN D8). Узкий шаг для сценариев приёма и конца курса; журнал напоминаний
 * о приёме не ведётся — ими управляет будильник.
 *
 * Зовётся **после** фиксации транзакции: система не откатывается вместе с базой, и снятый до
 * коммита будильник остался бы снятым у пункта, который так и не отменили (F5).
 */
class ReminderWithdrawal @Inject constructor(
    private val notifier: Notifier,
    private val alarms: ReminderAlarms
) {
    suspend fun withdraw(intakeId: Uuid) {
        val key = NotificationKey.intake(intakeId, NotificationKind.INTAKE_DUE)
        notifier.dismiss(key)
        alarms.cancel(key)
    }

    suspend fun withdrawAll(intakeIds: Collection<Uuid>) {
        for (id in intakeIds) withdraw(id)
    }
}
