package com.kert0n.medapp.storage.server

import androidx.room.withTransaction
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.queue.SnapshotStorage
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.MedKitDao
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.applySnapshot
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Укладка полного снимка — одной транзакцией (PLAN E4, F5). Ничего не толкует: что именно ляжет,
 * решает дверь `PackageDao.applySnapshot` по версиям, а у полки снимок трогает **только** число
 * участников — название и место хранения серверу неизвестны (F1).
 */
class SnapshotRoomStorage @Inject constructor(
    private val database: MedAppDatabase,
    private val medKits: MedKitDao,
    private val packages: PackageDao
) : SnapshotStorage {

    override suspend fun lay(
        participants: Map<Uuid, Long>,
        packages: List<PackageSnapshot>,
        at: Instant
    ) = database.withTransaction {
        for ((medKitId, count) in participants) medKits.applyServerParticipants(medKitId, count, at)
        for (snapshot in packages) this.packages.applySnapshot(snapshot, observedAt = at)
    }
}
