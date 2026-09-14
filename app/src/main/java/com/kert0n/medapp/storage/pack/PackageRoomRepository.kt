package com.kert0n.medapp.storage.pack

import androidx.room.withTransaction
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.domain.pack.PackageEnding
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.releaseSource
import com.kert0n.medapp.storage.course.toSourceStorageEntities
import com.kert0n.medapp.storage.course.toStorageEntity as toCourseStorageEntity
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.observing
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.server.SyncOperationDao
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PackageRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val packages: PackageDao,
    private val courses: CourseDao,
    private val queue: SyncOperationDao,
    private val intakes: IntakeDao,
    private val vocabulary: VocabularyDao
) : PackageStorageRepository {

    /**
     * Снимок словаря читается после строки, а не вместе с ней, и это безопасно: словарь только
     * растёт, а единица ложится в базу не позже строки, которая её называет.
     */
    override fun observe(id: Uuid): Flow<PackageProjection?> =
        onChange { projectionOf(id) }

    override suspend fun find(id: Uuid): Package? =
        packages.find(id)?.toDomain(vocabulary.snapshot())

    override suspend fun projection(id: Uuid): PackageProjection? = projectionOf(id)

    override fun list(query: PackageQuery, today: LocalDate): Flow<List<PackageProjection>> =
        onChange { listing(query, today) }

    override suspend fun add(pkg: Package, sync: PackageSyncState) = save(pkg, sync)

    private suspend fun save(pkg: Package, sync: PackageSyncState) = packages.save(pkg, sync)

    override suspend fun describe(packageId: Uuid, facts: PackageFacts): Boolean =
        change(packageId) { it.describe(facts) }

    override suspend fun answersToServer(packageId: Uuid): Boolean =
        packages.find(packageId)?.answersToServer ?: false

    override suspend fun mark(packageId: Uuid, status: PackageStatus, by: Uuid): Boolean =
        change(packageId) {
            when (status) {
                PackageStatus.CHANGING -> it.markChanging(by)
                PackageStatus.REMOVING -> it.markRemoving(by)
                PackageStatus.LOST -> it.markLost(by)
                PackageStatus.ACTIVE -> throw IllegalArgumentException("пометку снимает ответ полки, а не решение")
            }
        }

    override suspend fun end(ending: PackageEnding, at: Instant): Boolean = database.withTransaction {
        if (packages.find(ending.record.id) == null) return@withTransaction false
        finish(ending, at)
        true
    }

    private suspend fun finish(ending: PackageEnding, at: Instant) =
        packages.end(ending, courses, vocabulary.snapshot(), at)

    override suspend fun contentsOf(medKitId: Uuid): List<Package> = database.withTransaction {
        val words = vocabulary.snapshot()
        packages.ofMedKit(medKitId).map { it.toDomain(words) }
    }

    /**
     * Переход применяется к тому, что лежит в базе, и пишется вместе с сохранённой обвязкой:
     * версии и время сверки принадлежат снимку сервера, а не действию человека (PLAN E4).
     */
    private suspend fun change(packageId: Uuid, transition: (Package) -> Package): Boolean =
        database.withTransaction {
            val stored = packages.find(packageId) ?: return@withTransaction false
            val changed = transition(stored.toDomain(vocabulary.snapshot()))
            save(changed, stored.pack.syncState())
            true
        }

    override suspend fun applySnapshot(snapshot: PackageSnapshot, observedAt: Instant): SnapshotApplied =
        database.withTransaction { packages.applySnapshot(snapshot, observedAt) }

    override suspend fun saveClaims(packageId: Uuid, claims: Claims?) {
        if (claims == null) packages.deleteClaims(packageId)
        else packages.upsertClaims(claims.toStorageEntity(packageId))
    }

    override suspend fun adjust(
        adjustment: PackageAdjustment,
        reallocation: CourseReallocation?,
        at: Instant
    ): Boolean = database.withTransaction {
        val stored = packages.find(adjustment.packageId) ?: return@withTransaction false
        when (val after = adjustment.applyTo(stored.toDomain(vocabulary.snapshot()))) {
            is PackageAfter.Left -> {
                // Версии и время сверки остаются те, что записал снимок сервера: их двигает сеть (E4).
                save(after.pkg, stored.pack.syncState())
                // Пересчитанное обеспечение относится к пережившей переход коробке. У кончившейся
                // источник уже снят доменным переходом внутри конца, и считать по ней нечего.
                reallocation?.let { (plan, expected) ->
                    courses.updateAllocations(
                        plan.toCourseStorageEntity(),
                        plan.medicine.toSourceStorageEntities(plan.id),
                        expected
                    )
                }
            }
            is PackageAfter.Ended -> finish(after.ending, at)
        }
        true
    }

    /**
     * Проекция читается одним снимком: поток лишь уведомляет, что база изменилась, а
     * согласованный набор входных данных берётся транзакцией. `combine` независимых потоков
     * этого не даёт — его значения относятся к разным состояниям базы, и экран получал бы
     * комбинацию, которой в базе никогда не было: свежий остаток со старой очередью.
     */
    private fun <T> onChange(read: suspend () -> T): Flow<T> = database.observing(*AVAILABILITY_TABLES, read = read)

    private suspend fun projectionOf(id: Uuid): PackageProjection? = database.withTransaction {
        val words = vocabulary.snapshot()
        val pkg = packages.find(id)?.toDomain(words) ?: return@withTransaction null
        packages.projectionsOf(listOf(pkg), queue, intakes, words).single()
    }

    /**
     * Обвязка синхронизации пачки — своим методом, а не полем проекции: версии и момент сверки
     * принадлежат доставке, а не пачке, и нужны они одному экрану состояния синхронизации
     * (PLAN E4, H3 №28). `null` — пачки больше нет.
     */
    override fun observeSyncState(id: Uuid): Flow<PackageSyncState?> =
        packages.observe(id).map { it?.pack?.syncState() }

    /**
     * Список — готовые проекции одним чтением. «Есть свободное» запросом не выражается: это
     * вычитание чужих броней и выделения из оценки количества, а оценка зависит от очереди
     * (PLAN H4).
     */
    private suspend fun listing(query: PackageQuery, today: LocalDate): List<PackageProjection> =
        database.withTransaction {
            val words = vocabulary.snapshot()
            val found = packages.matching(query, today).map { it.toDomain(words) }
            val projected = packages.projectionsOf(found, queue, intakes, words)
            if (query.filter != PackageQuery.Filter.HasFree) projected
            else projected.filter { !it.availability.freeForAnyone.isZero }
        }

    private companion object {

        /** Из чего складывается проекция: пачка с её сведениями и бронями, очередь, выделения, приёмы. */
        val AVAILABILITY_TABLES = arrayOf(
            "packages",
            "package_records",
            "package_details",
            "claims",
            "sync_operations",
            "courses",
            "course_sources",
            "active_package_assignments",
            "intakes"
        )
    }
}
