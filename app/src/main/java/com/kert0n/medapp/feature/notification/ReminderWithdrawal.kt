package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Повод напоминать о пункте исчез — он отвечен или курса больше нет: обязательство помечается
 * отозванным (PLAN D8).
 *
 * Зовётся **внутри** транзакции, которая записала ответ: отзыв — такая же строка в той же базе, и
 * откат уносит его вместе с ответом. Гасит карточку и переставляет будильник [ReminderOutbox] —
 * уже после коммита, по сигналу изменившейся таблицы; звать систему из сценария больше не нужно
 * (F5).
 */
class ReminderWithdrawal @Inject constructor(
    private val reminders: ReminderStorageRepository
) {
    /** Пункт отвечен или его больше нет: снимается **всё**, что о нём обещали. */
    suspend fun withdraw(intakeId: Uuid) = withdrawAll(listOf(intakeId))

    suspend fun withdrawAll(intakeIds: Collection<Uuid>) {
        withdrawKeys(intakeIds.flatMap { NotificationKey.allOf(it) })
    }

    /**
     * Пункт перестал быть плановым, но ответа так и не получил: напоминать о приёме больше нечего,
     * а о **пропуске** мы как раз собираемся сказать — его обязательство не трогаем.
     */
    suspend fun withdrawTheReminder(intakeIds: Collection<Uuid>) {
        withdrawKeys(intakeIds.map { NotificationKey.intake(it, NotificationKind.INTAKE_DUE) })
    }

    /** Снять названные обязательства: прочитать, перевести переходом и записать. */
    suspend fun withdrawKeys(keys: Collection<NotificationKey>) {
        if (keys.isEmpty()) return
        val known = reminders.findAll(keys).onEach { it.withdraw() }
        reminders.saveAll(known)
    }
}
