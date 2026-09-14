package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Повод напоминать о пункте исчез — он отвечен: обязательство помечается отозванным (PLAN D8).
 *
 * Зовётся **внутри** транзакции, которая записала ответ: отзыв — такая же строка в той же базе, и
 * откат уносит его вместе с ответом. Гасит карточку и переставляет будильник [ReminderOutbox] —
 * уже после коммита, по сигналу изменившейся таблицы; звать систему из сценария больше не нужно
 * (F5).
 */
class ReminderWithdrawal @Inject constructor(
    private val reminders: ReminderStorageRepository
) {
    suspend fun withdraw(intakeId: Uuid) = withdrawAll(listOf(intakeId))

    suspend fun withdrawAll(intakeIds: Collection<Uuid>) {
        reminders.withdraw(intakeIds.map { NotificationKey.intake(it, NotificationKind.INTAKE_DUE) })
    }
}
