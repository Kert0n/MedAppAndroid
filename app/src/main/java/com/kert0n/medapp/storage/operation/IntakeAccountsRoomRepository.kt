package com.kert0n.medapp.storage.operation

import com.kert0n.medapp.feature.intake.IntakeAccounts
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.storage.intake.IntakeDao
import javax.inject.Inject
import kotlin.uuid.Uuid

/** Учёт расхода приёма из его строки — журнал понимает колонки, которые приём только несёт. */
class IntakeAccountsRoomRepository @Inject constructor(private val intakes: IntakeDao) : IntakeAccounts {

    override suspend fun of(intakeId: Uuid): IntakeSyncState? = intakes.findEntity(intakeId)?.syncState()
}
