package com.kert0n.medapp.network.delivery

import com.kert0n.medapp.queue.Packing
import com.kert0n.medapp.queue.Preparation
import com.kert0n.medapp.queue.PreparedRequest
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/** Посылки MedApp: путь, метод и тело по контракту сервера (PLAN B4). */
class MedAppPacking @Inject constructor() : Packing {

    override fun pack(operationId: Uuid, command: PackageSyncCommand, sync: PackageSyncState, send: Preparation.Send, at: Instant): PreparedRequest =
        command.toPreparedRequest(operationId, sync, confirmed = send.confirmed, mine = send.mine, at = at, known = send.known)

    override fun pack(command: MedKitSyncCommand, at: Instant): PreparedRequest = command.toPreparedRequest(at)
}
