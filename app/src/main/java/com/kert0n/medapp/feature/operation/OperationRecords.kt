package com.kert0n.medapp.feature.operation

import androidx.annotation.CheckResult
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Хранение очереди. Номер выдаёт база при постановке, поэтому команду ставят, а не сочиняют
 * операцию целиком (PLAN E2).
 */
interface OperationRecords : OperationReadings {

    suspend fun enqueue(
        id: Uuid,
        command: SyncCommand,
        at: Instant,
        groupId: Uuid? = null,
        dependsOn: Set<Uuid> = emptySet()
    ): SyncOperation

    suspend fun find(id: Uuid): SyncOperation?

    suspend fun withStatus(status: SyncOperationStatus): List<SyncOperation>

    /**
     * Операции, которые нечем прочитать: чужая версия payload после обновления приложения,
     * неизвестный вид команды, повреждённые параметры подготовленного запроса. Работник их
     * пропускает, а экран показывает с причиной — молча они не теряются (PLAN F4).
     */
    suspend fun unreadable(): List<StoredSyncOperation.Unreadable>

    /** Строка как она лежит: собранная, недочитанная словарём или нечитаемая; `null` — её нет. */
    suspend fun stored(id: Uuid): StoredSyncOperation?

    /**
     * Отказ разобран человеком в [at] (PLAN H3 №28, C1): строка остаётся — приём держится за учёт
     * своего расхода, — но экрану и вниманию к очереди больше не нужна; зависимые, закрытые следом
     * за ней (`SUPERSEDED`), разбираются тем же решением. `false` — строки нет или она уже
     * разобрана. Годится ли она к разбору, решает сценарий по [StoredSyncOperation.needsDecision].
     */
    @CheckResult
    suspend fun dismiss(id: Uuid, at: Instant): Boolean
}
