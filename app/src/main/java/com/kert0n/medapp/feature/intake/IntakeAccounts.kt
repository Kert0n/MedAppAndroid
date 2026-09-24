package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.queue.intake.IntakeSyncState
import kotlin.uuid.Uuid

/**
 * Учёт расхода приёма — где расход и какой операцией уехал (PLAN E1). Это знание очереди, поэтому
 * отдельным портом от записей приёма: исполняет его журнал (`storage/operation`), а хранение
 * приёма учёт только переносит, не толкуя. `null` — приёма нет.
 */
interface IntakeAccounts {

    suspend fun of(intakeId: Uuid): IntakeSyncState?
}
