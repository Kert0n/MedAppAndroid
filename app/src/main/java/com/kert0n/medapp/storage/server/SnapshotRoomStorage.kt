package com.kert0n.medapp.storage.server

import androidx.room.withTransaction
import com.kert0n.medapp.di.ArrivedMedKitName
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.queue.ServerKnowledge
import com.kert0n.medapp.queue.ServerSnapshot
import com.kert0n.medapp.queue.SnapshotStorage
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.MedKitDao
import com.kert0n.medapp.storage.medkit.loseAccess
import com.kert0n.medapp.storage.medkit.toStorageEntity
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
 * решает дверь `PackageDao.applySnapshot` по версиям, и она же пишет необъяснённую разницу; что
 * появилось и чего не стало — решено в очереди, а у знакомой полки снимок трогает **только**
 * число участников: название и место хранения серверу неизвестны (F1).
 */
class SnapshotRoomStorage @Inject constructor(
    private val database: MedAppDatabase,
    private val medKits: MedKitDao,
    private val packages: PackageDao,
    private val courses: CourseDao,
    private val movements: StockMovementDao,
    private val vocabulary: VocabularyDao,
    private val queue: SyncOperationDao,
    @ArrivedMedKitName private val arrivedName: String
) : SnapshotStorage {

    override suspend fun serverKnows(): ServerKnowledge = database.withTransaction {
        ServerKnowledge(
            medKits = medKits.knownToServer().toSet(),
            packages = packages.knownToServer().toSet(),
            heldMedKits = medKits.held().toSet(),
            heldPackages = packages.held().toSet()
        )
    }

    override suspend fun lay(snapshot: ServerSnapshot, at: Instant) = database.withTransaction {
        val words = vocabulary.snapshot()
        // Полка, пришедшая с сервера, у нас заводится: имени и места хранения сервер не знает, и
        // человек назовёт её сам. Уже заведённую — вступлением, пока снимок летел, — не трогаем.
        for (medKitId in snapshot.arrivedMedKits) {
            val arrived = MedKit(
                id = medKitId,
                name = arrivedName,
                location = null,
                publication = MedKit.Publication.PUBLISHED,
                participantCount = snapshot.participants.getValue(medKitId),
                createdAt = at
            )
            medKits.insertIfMissing(arrived.toStorageEntity(syncedAt = at))
        }
        for ((medKitId, count) in snapshot.participants) medKits.applyServerParticipants(medKitId, count, at)
        val inFlight = queue.packagesInFlight().toSet()
        for (resolved in snapshot.packages) {
            val packageId = resolved.pack.id
            // Коробку с запросом в полёте кладёт ответ на него: снимок мог уже увидеть наш расход,
            // а подтверждённое число его ещё не знает (PLAN E1).
            if (packageId in inFlight) continue
            // Убранное, пока снимок летел, он не возвращает: коробку выбросили или унесли вместе с
            // полкой, и ответ, прочитанный раньше, об этом не знает (PLAN C0).
            val removed = packageId in snapshot.heldPackages && packages.find(packageId) == null
            if (removed || medKits.find(resolved.pack.medKit.id) == null) continue
            packages.applySnapshot(resolved, observedAt = at, movements, words)
        }
        // Коробка кончается своим переходом со следом, полка уходит вместе с содержимым — обе
        // двери те же, какими пользуется ответ сервера на нашу команду (PLAN D7, E6).
        for (packageId in snapshot.gonePackages) {
            val pkg = packages.find(packageId)?.toDomain(words) ?: continue
            packages.end(pkg.lost(Uuid.random(), at), courses, movements, words, at)
        }
        for (medKitId in snapshot.goneMedKits) medKits.loseAccess(medKitId, packages, courses, movements, words, at)
    }
}
