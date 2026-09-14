package com.kert0n.medapp.fixture

import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.Settlement
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.storage.server.SyncOperationDao
import com.kert0n.medapp.storage.server.toState
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Строка очереди приводится в нужный статус **переходами состояния**, как её привело бы
 * хранение (C1 «Переходы операции — у типа»): своей SQL-двери у фикстуры нет — иначе проверки
 * ставили бы строки в состояния, которых приложение не выражает. Закрытие — `closed(Close)`,
 * возврат в ожидание — `retried`; [lastError] у закрытия — из причины, как в приложении.
 */
suspend fun SyncOperationDao.settle(
    id: Uuid,
    status: SyncOperationStatus,
    lastError: String? = null,
    at: Instant? = null,
    attempted: Int = 0,
    notBefore: Instant? = null,
    outcomeUnknown: Int = 0,
    refusalReason: RefusalReason? = null
) {
    val row = requireNotNull(find(id)) { "строки $id нет" }.operation
    val moment = at ?: row.createdAt
    val state = row.toState()
    val after = when (status) {
        SyncOperationStatus.APPLIED -> state.closed(Settlement.Transition.Close.Applied, moment)
        SyncOperationStatus.REFUSED -> state.closed(Settlement.Transition.Close.Refused(requireNotNull(refusalReason) { "отказ без причины не выражается" }), moment)
        SyncOperationStatus.ACCESS_LOST -> state.closed(Settlement.Transition.Close.AccessLost, moment)
        SyncOperationStatus.PENDING -> state.retried(lastError.orEmpty(), moment, attempted > 0, outcomeUnknown == 1, notBefore)
        SyncOperationStatus.SENDING, SyncOperationStatus.ANSWERED -> error("фикстура не заводит $status: это делает взятие и ответ")
    }
    requireNotNull(after) { "переход в $status из ${row.status} не применим" }
    check(save(id, after, row.prepared, was = row.status) == 1) { "строка $id не записана" }
}
