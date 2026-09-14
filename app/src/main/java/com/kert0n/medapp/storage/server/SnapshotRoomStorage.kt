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
import com.kert0n.medapp.storage.course.followBox
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.medkit.loseAccess
import com.kert0n.medapp.storage.medkit.toStorageEntity
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.applySnapshot
import com.kert0n.medapp.storage.pack.end
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Укладка полного снимка — одной транзакцией (PLAN E4, F5). Ничего не толкует: что именно ляжет,
 * решает дверь `PackageDao.applySnapshot` по версиям; что появилось и чего не стало — решено в
 * очереди, а у знакомой полки снимок трогает **только** число участников: название и место
 * хранения серверу неизвестны (F1).
 *
 * Оба пути смотрят на нынешнее состояние одинаково. Положительный не кладёт коробку с запросом в
 * полёте и не возвращает убранную; отрицательный кончает только то, о чём сервер знает и ждать
 * нечего **на момент укладки**, а не на момент чтения. Односторонняя проверка стоила коробки:
 * снимок, начатый до уноса домой, кончал коробку, которая к этому мигу была уже местной и целой.
 */
class SnapshotRoomStorage @Inject constructor(
    private val database: MedAppDatabase,
    private val medKits: MedKitDao,
    private val packages: PackageDao,
    private val courses: CourseDao,
    private val intakes: IntakeDao,
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
            packages.applySnapshot(resolved, observedAt = at)
            // Курс следует за коробкой той же транзакцией: чужой расход или бронь зажимают
            // выделения, и бронь уезжает разницей — каждая своей пачке (PLAN D5, E4).
            queue.enqueueClaimChanges(courses.followBox(packageId, packages, intakes, queue, words, at), packages, at)
        }
        // «Сервер знал, и ждать нечего» посчитано до запроса, а применяется после него — и за это
        // время человек мог унести коробку домой, пометить её или сделать её полку местной.
        // Поэтому тот же вопрос задаётся второй раз, уже здесь: отсутствие кончает только то, о чём
        // ждать нечего **и сейчас**. Остальное объяснит ответ на его собственную команду (PLAN E4).
        val knownPackages = packages.knownToServer().toSet()
        val knownMedKits = medKits.knownToServer().toSet()
        // Коробка кончается своим переходом, полка уходит вместе с содержимым — обе двери те же,
        // какими пользуется ответ сервера на нашу команду (PLAN D3, E6).
        for (packageId in snapshot.gonePackages) {
            if (packageId !in knownPackages) continue
            val pkg = packages.find(packageId)?.toDomain(words) ?: continue
            packages.end(pkg.ended(), courses, words, at)
        }
        for (medKitId in snapshot.goneMedKits) {
            if (medKitId !in knownMedKits) continue
            medKits.loseAccess(medKitId, packages, courses, words, at)
        }
    }
}
