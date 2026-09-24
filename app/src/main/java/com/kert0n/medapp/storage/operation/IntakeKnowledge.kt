package com.kert0n.medapp.storage.operation

import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.feature.intake.RecordedIntake
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.storage.intake.IntakeStorageEntity
import com.kert0n.medapp.storage.intake.toStorageEntity

/*
 * Учёт расхода приёма — знание очереди: где расход и какой операцией уехал (PLAN E1). Колонки
 * лежат в строке приёма, а типы очереди из них собираются только здесь (решение владельца
 * 2026-09-24). Приём их переносит, не толкуя.
 */

/** Учёт расхода из строки приёма. */
fun IntakeStorageEntity.syncState(): IntakeSyncState = IntakeSyncState(
    intakeId = id,
    accounting = IntakeAccounting.valueOf(accounting),
    operationId = operationId
)

/** Приём в строку вместе с учётом его расхода; пару держит [RecordedIntake]. */
fun RecordedIntake.toStorageEntity(): IntakeStorageEntity = intake.toStorageEntity(sync)

/** Приём в строку вместе с учётом его расхода; без учёта — расхода нет. */
fun Intake.toStorageEntity(sync: IntakeSyncState = IntakeSyncState(id)): IntakeStorageEntity {
    sync.requireOf(id)
    return toStorageEntity(sync.accountingColumn(), sync.operationId)
}

/** Учёт расхода как колонка строки приёма. */
fun IntakeSyncState.accountingColumn(): String = accounting.name
