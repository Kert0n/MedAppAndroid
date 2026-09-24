package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.queue.Transactions
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Повод напоминать о пункте исчез — он отвечен или курса больше нет: обязательство помечается
 * отозванным (PLAN D8).
 *
 * Читает и пишет **одной транзакцией**, поэтому верен и сам по себе, и внутри транзакции того, кто
 * записал ответ: вложенность безопасна, а откат уносит отзыв вместе с ответом. Гасит карточку и
 * переставляет будильник [ReminderOutbox] — уже после коммита, по сигналу изменившейся таблицы;
 * звать систему из сценария не нужно (F5).
 */
class ReminderWithdrawal @Inject constructor(
    private val reminders: ReminderRecords,
    private val transactions: Transactions
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

    /**
     * Снять названные обязательства: прочитать, перевести переходом и записать — одной транзакцией.
     * Уже отозванное не переписывается: сверка зовёт это на каждом проходе, и лишняя запись
     * будила бы владельца доставки без повода.
     */
    suspend fun withdrawKeys(keys: Collection<NotificationKey>) {
        if (keys.isEmpty()) return
        transactions.run {
            reminders.saveAll(reminders.findAll(keys).filter { it.state != Reminder.State.WITHDRAWN }.onEach { it.withdraw() })
        }
    }
}
