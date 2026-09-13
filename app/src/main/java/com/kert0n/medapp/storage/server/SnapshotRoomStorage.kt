package com.kert0n.medapp.storage.server

import androidx.room.withTransaction
import com.kert0n.medapp.queue.ServerKnowledge
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.SnapshotStorage
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.MedKitDao
import com.kert0n.medapp.storage.medkit.loseAccess
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.applySnapshot
import com.kert0n.medapp.storage.pack.end
import com.kert0n.medapp.storage.stock.StockMovementDao
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Укладка полного снимка — одной транзакцией (PLAN E4, F5). Ничего не толкует: что именно ляжет,
 * решает дверь `PackageDao.applySnapshot` по версиям, чего не стало — решено в очереди, а у полки
 * снимок трогает **только** число участников: название и место хранения серверу неизвестны (F1).
 */
class SnapshotRoomStorage @Inject constructor(
    private val database: MedAppDatabase,
    private val medKits: MedKitDao,
    private val packages: PackageDao,
    private val courses: CourseDao,
    private val movements: StockMovementDao,
    private val vocabulary: VocabularyDao
) : SnapshotStorage {

    override suspend fun serverKnows(): ServerKnowledge = database.withTransaction {
        ServerKnowledge(medKits.knownToServer().toSet(), packages.knownToServer().toSet())
    }

    override suspend fun lay(snapshot: ServerSnapshot, at: Instant) = database.withTransaction {
        val words = vocabulary.snapshot()
        for ((medKitId, count) in snapshot.participants) medKits.applyServerParticipants(medKitId, count, at)
        for (resolved in snapshot.packages) packages.applySnapshot(resolved, observedAt = at)
        // Коробка кончается своим переходом со следом, полка уходит вместе с содержимым — обе
        // двери те же, какими пользуется ответ сервера на нашу команду (PLAN D7, E6).
        for (packageId in snapshot.gonePackages) {
            val pkg = packages.find(packageId)?.toDomain(words) ?: continue
            packages.end(pkg.lost(Uuid.random(), at), courses, movements, words, at)
        }
        for (medKitId in snapshot.goneMedKits) medKits.loseAccess(medKitId, packages, courses, movements, words, at)
    }
}
