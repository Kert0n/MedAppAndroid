package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState

/**
 * Приём вместе с учётом его расхода — то, что записывается в базу как одно (PLAN D6, F5).
 *
 * Правило о **паре** живёт здесь, а не на одном из путей записи: у подтверждённого приёма расход
 * учтён, у неподтверждённого его нет. Пока оно стояло только в ответе на приём, соседняя запись
 * пропускала «принял, но расхода нет» — состояние, которого не бывает.
 */
class RecordedIntake(
    val intake: Intake,
    val sync: IntakeSyncState = IntakeSyncState(intake.id)
) {
    init {
        require(sync.intakeId == intake.id) { "учёт расхода принадлежит своему приёму" }
        require(
            intake.status != IntakeStatus.TAKEN ||
                sync.accounting != IntakeAccounting.NOT_APPLICABLE
        ) { "у подтверждённого приёма расход учтён" }
        require(
            intake.status == IntakeStatus.TAKEN ||
                sync.accounting == IntakeAccounting.NOT_APPLICABLE
        ) { "у неподтверждённого приёма расхода нет" }
    }
}
