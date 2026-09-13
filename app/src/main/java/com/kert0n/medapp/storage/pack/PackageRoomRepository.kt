package com.kert0n.medapp.storage.pack

import androidx.room.withTransaction
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.domain.pack.PackageEnding
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.queue.PackageQueueState
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.releaseSource
import com.kert0n.medapp.storage.course.toSourceStorageEntities
import com.kert0n.medapp.storage.course.toStorageEntity as toCourseStorageEntity
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.chunkedForQuery
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.storage.server.SyncOperationDao
import com.kert0n.medapp.storage.server.SyncOperationStorageRow
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

    override fun list(query: PackageQuery, today: LocalDate): Flow<List<PackageProjection>> =
        onChange { listing(query, today) }

    override suspend fun add(pkg: Package, sync: PackageSyncState) = save(pkg, sync)

    private suspend fun save(pkg: Package, sync: PackageSyncState) = packages.save(pkg, sync)

    override suspend fun describe(packageId: Uuid, facts: PackageFacts): Boolean =
        change(packageId) { it.describe(facts) }

    override suspend fun mark(packageId: Uuid, status: PackageStatus): Boolean =
        change(packageId) {
            when (status) {
                PackageStatus.CHANGING -> it.markChanging()
                PackageStatus.REMOVING -> it.markRemoving()
                PackageStatus.LOST -> it.markLost()
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
    private fun <T> onChange(read: suspend () -> T): Flow<T> =
        database.invalidationTracker.createFlow(*AVAILABILITY_TABLES).map { read() }

    private suspend fun projectionOf(id: Uuid): PackageProjection? = database.withTransaction {
        val words = vocabulary.snapshot()
        val pkg = packages.find(id)?.toDomain(words) ?: return@withTransaction null
        projectionOf(pkg, packages.allocationsOf(listOf(id)).firstOrNull(), queue.unclosedOfPackages(listOf(id)), words)
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
            val ids = found.map { it.id }
            val allocations = ids.chunkedForQuery().flatMap { packages.allocationsOf(it) }
            val unclosed = unclosedOf(ids)
            val projected = found.map { pkg ->
                projectionOf(pkg, allocations.firstOrNull { it.packageId == pkg.id }, unclosed[pkg.id].orEmpty(), words)
            }
            if (query.filter != PackageQuery.Filter.HasFree) projected
            else projected.filter { !it.availability.freeForAnyone.isZero }
        }

    /**
     * Проекция пачки: оценка количества — незакрытые команды поверх подтверждённого остатка по
     * возрастанию номера; команда, которую нечем прочитать после обновления приложения, в число
     * не входит и названа среди нечитаемых отдельно (PLAN E1, F4). Выделение — из назначения
     * активному курсу (PLAN D4).
     */
    private suspend fun projectionOf(
        pkg: Package,
        allocation: PackageAllocationRow?,
        unclosed: List<SyncOperationStorageRow>,
        words: Vocabulary
    ): PackageProjection {
        val state = PackageQueueState(pkg, commandsOf(unclosed, words))
        val availability = PackageAvailability(
            pkg = pkg,
            effective = state.amount,
            myAllocation = allocation?.allocated(words, pkg.quantity.unit) ?: Quantity.zero(pkg.quantity.unit)
        )
        return pkg.projection(availability, state.hasUnconfirmedChanges)
    }

    /** Команды пачки из строк очереди; нечитаемую после обновления приложения пропускаем (PLAN F4). */
    private fun commandsOf(rows: List<SyncOperationStorageRow>, words: Vocabulary): List<PackageSyncCommand> =
        rows.mapNotNull {
            (it.toDomain(words) as? StoredSyncOperation.Readable)?.operation?.command as? PackageSyncCommand
        }

    /**
     * Незакрытые операции всего списка — одним чтением на порцию: спрашивать очередь про каждую
     * пачку значило бы двести запросов там, где хватает одного. Порядок по `sequence` внутри
     * пачки группировка сохраняет.
     */
    private suspend fun unclosedOf(ids: List<Uuid>): Map<Uuid, List<SyncOperationStorageRow>> =
        ids.chunkedForQuery()
            .flatMap { queue.unclosedOfPackages(it) }
            .groupBy { requireNotNull(it.operation.packageId) { "операция пачки называет свою пачку" } }

    private companion object {


        /** Из чего складывается доступность: пачка с её сведениями и бронями, очередь, выделения. */
        val AVAILABILITY_TABLES = arrayOf(
            "packages",
            "package_records",
            "package_details",
            "claims",
            "sync_operations",
            "courses",
            "course_sources",
            "active_package_assignments",
            "quantity_units",
            "form_types"
        )
    }
}
