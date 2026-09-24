package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.Settlement
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.queue.intake.IntakeAccounting
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек разобрал операцию, которая ждала его решения (PLAN H3 №28, C1 «Отказ разобран
 * человеком»): отвергнутую сервером — посмотрел и понял, что делать (пересчёт — своим сценарием);
 * нечитаемую — оставил. Строка не удаляется: приём держится за учёт своего расхода. Разобранное
 * уходит из `observeOutstanding()`, а обещание `SYNC_ATTENTION` снимает сверка по сигналу таблицы.
 *
 * Нечитаемая строка ещё и **закрывается** отказом `UNREADABLE` той же дверью, какой закрывает
 * работник: иначе она оставалась бы `PENDING` навсегда, и остаток очереди звал бы заход без конца.
 * Зависимые от неё закрываются следом, их приёмы получают `REMOTE_REFUSED` — факт цел, серверный
 * остаток его не включает (E3).
 *
 * Читает и пишет одной транзакцией (F5): пока человек нажимал, работник мог закрыть строку сам.
 */
class OperationDismissing @Inject constructor(
    private val operations: OperationRecords,
    private val queue: QueueStorage,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun dismiss(operationId: Uuid): Outcome = transactions.run {
        val stored = operations.stored(operationId) ?: return@run Outcome.GONE
        if (!stored.needsDecision) return@run Outcome.NOT_AWAITING_DECISION
        val at = clock.instant()
        if (stored is StoredSyncOperation.Unreadable) {
            queue.settle(
                operationId,
                Settlement(
                    Settlement.Transition.Close.Refused(RefusalReason.UNREADABLE),
                    listOf(
                        Settlement.Effect.Account(IntakeAccounting.REMOTE_REFUSED),
                        Settlement.Effect.Cascade(Settlement.Transition.Close.Refused(RefusalReason.SUPERSEDED), IntakeAccounting.REMOTE_REFUSED),
                        Settlement.Effect.Settled
                    )
                ),
                at
            )
        }
        if (operations.dismiss(operationId, at)) Outcome.DISMISSED else Outcome.GONE
    }

    /**
     * Разобрано — строка ушла с экрана; операции нет; операция решения человека не ждёт — она
     * ждёт срока, отправляется или уже применена, и разбирать в ней нечего.
     */
    enum class Outcome { DISMISSED, GONE, NOT_AWAITING_DECISION }
}
