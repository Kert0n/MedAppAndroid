package com.kert0n.medapp.storage.operation

import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.queue.PackageQueueState
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.storage.database.chunkedForQuery
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.PackageStorageEntity
import com.kert0n.medapp.storage.pack.SnapshotApplied
import com.kert0n.medapp.storage.pack.save
import com.kert0n.medapp.storage.pack.toDetailsStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity
import java.time.Instant

/*
 * Знание очереди о пачке — версии, момент сверки, незакрытые поручения — понимает журнал, а не
 * хранение пачки: колонки лежат в строке коробки, типы очереди из них собираются только здесь
 * (решение владельца 2026-09-24). Пачка их переносит, не толкуя.
 */

/** Знание очереди о пачке из её строки. */
fun PackageStorageEntity.syncState(): PackageSyncState = PackageSyncState(
    packageId = id,
    version = version?.let(::ResourceVersion),
    claimsVersion = claimsVersion?.let(::ResourceVersion),
    syncedAt = syncedAt
)

/** Строка пачки вместе со знанием очереди о ней. */
fun Package.toStorageEntity(sync: PackageSyncState): PackageStorageEntity {
    require(sync.packageId == id) { "знание о сервере принадлежит своей пачке" }
    return toStorageEntity(sync.version?.number, sync.claimsVersion?.number, sync.syncedAt)
}

/** Пачка вместе со знанием очереди о ней — у снимка сервера и у ответа на поручение. */
suspend fun PackageDao.save(pkg: Package, sync: PackageSyncState) =
    save(pkg.record.toStorageEntity(), pkg.toStorageEntity(sync), pkg.toDetailsStorageEntity())

/**
 * Проекции пачек одним чтением на порцию: оценка количества — незакрытые команды поверх
 * подтверждённого остатка по возрастанию номера (команда, которую нечем прочитать после
 * обновления приложения, в число не входит — PLAN E1, F4); выделение и держащий курс — из
 * назначения активному курсу; последний мой приём — по приёмам из коробки (PLAN D4). Спрашивать
 * очередь, выделения и приёмы про каждую пачку значило бы двести запросов там, где хватает
 * одного; порядок по `sequence` внутри пачки группировка сохраняет.
 *
 * Зовётся внутри уже открытой транзакции того, кто читает: списка пачек и обеспечения курса.
 */
suspend fun PackageDao.projectionsOf(
    packages: List<Package>,
    queue: SyncOperationDao,
    intakes: IntakeDao,
    words: Vocabulary
): List<PackageProjection> {
    val ids = packages.map { it.id }
    val allocations = ids.chunkedForQuery().flatMap { allocationsOf(it) }.associateBy { it.packageId }
    val unclosed = ids.chunkedForQuery()
        .flatMap { queue.unclosedOfPackages(it) }
        .groupBy { requireNotNull(it.operation.packageId) { "операция пачки называет свою пачку" } }
    val lastUsed = ids.chunkedForQuery().flatMap { intakes.lastTakenFrom(it) }.associate { it.packageId to it.lastUsedAt }
    return packages.map { pkg ->
        val state = PackageQueueState(pkg, commandsOf(unclosed[pkg.id].orEmpty(), words))
        val allocation = allocations[pkg.id]
        val availability = PackageAvailability(
            pkg = pkg,
            effective = state.amount,
            myAllocation = allocation?.allocated(words, pkg.quantity.unit) ?: Quantity.zero(pkg.quantity.unit)
        )
        pkg.projection(
            availability = availability,
            hasUnconfirmedChanges = state.hasUnconfirmedChanges,
            holdingCourseId = allocation?.courseId,
            lastUsedAt = lastUsed[pkg.id]
        )
    }
}

/** Команды пачки из строк очереди; нечитаемую после обновления приложения пропускаем (PLAN F4). */
private fun commandsOf(rows: List<SyncOperationStorageRow>, words: Vocabulary): List<PackageSyncCommand> =
    rows.mapNotNull {
        (it.toDomain(words) as? StoredSyncOperation.Readable)?.operation?.command as? PackageSyncCommand
    }

/**
 * Снимок пачки, разрешённый в домен, — в базу. Единственная дверь: половины расходятся только
 * тут, и только по своим версиям, поэтому версия картины броней всегда описывает ту картину,
 * что лежит рядом (PLAN B3, E1). Серверное число ложится только через неё — полным снимком,
 * чтением перед отправкой или ответом на команду; чужое изменение просто становится нашим
 * числом, истории у коробки нет (D7).
 *
 * Зовётся внутри уже открытой транзакции того, кто снимок кладёт.
 */
suspend fun PackageDao.applySnapshot(snapshot: PackageSnapshot, observedAt: Instant): SnapshotApplied =
    applySnapshot(
        snapshot.pack.toStorageEntity(snapshot.sync),
        snapshot.pack.claims?.toStorageEntity(snapshot.pack.id),
        observedAt
    )

