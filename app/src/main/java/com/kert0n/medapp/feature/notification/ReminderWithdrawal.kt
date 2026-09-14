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
}
