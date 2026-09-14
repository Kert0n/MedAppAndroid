package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Повод напоминать о пункте исчез — он отвечен или курса больше нет: обязательство помечается
 * отозванным (PLAN D8). Гасит показанное и снимает будильник [ReminderOutbox] — после коммита,
 * потому что система не откатывается вместе с базой (F5); сценарию для этого больше ничего звать
 * не нужно.
 */
class ReminderWithdrawal @Inject constructor(
    private val reminders: ReminderStorageRepository
) {
    suspend fun withdraw(intakeId: Uuid) = withdrawAll(listOf(intakeId))

    suspend fun withdrawAll(intakeIds: Collection<Uuid>) {
        reminders.withdraw(intakeIds.map { NotificationKey.intake(it, NotificationKind.INTAKE_DUE) })
    }
}
