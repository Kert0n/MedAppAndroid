package com.kert0n.medapp.storage.operation

import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Attempts
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueRepository
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.transactions
import com.kert0n.medapp.fixture.unplannedIntake
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.pack.toDomain
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.network.server.medAppJson
import com.kert0n.medapp.queue.Delivery
import com.kert0n.medapp.queue.PackageState
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.Take
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.queue.settlement
import com.kert0n.medapp.storage.course.ActivePackageAssignmentStorageEntity
import com.kert0n.medapp.storage.course.toSourceStorageEntities
import com.kert0n.medapp.storage.course.toStorageEntity as toCourseStorageEntity
import com.kert0n.medapp.storage.course.toTimeStorageEntities
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.intake.toStorageEntity as toIntakeStorageEntity
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity
import java.math.BigDecimal
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Порт очереди в Room: заморозка запроса с предусловиями пачки, применение исхода со всеми его
 * эффектами — одной транзакцией каждое (PLAN E1, E2, F5). Что исход значит, решает очередь
 * (`Delivery.settlement`); здесь проверяется, что хранение применяет решённое.
 */
class QueueRoomStorageTest {

    private lateinit var database: MedAppDatabase
    private val storage get() = database.queueStorage()

    /** Исход — через решение очереди, как его отдаёт работник. */
    private suspend fun QueueRoomStorage.settle(id: Uuid, outcome: Delivery, at: Instant) {
        val command = (requireNotNull(database.syncOperations().find(id)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command
        settle(id, outcome.settlement(command), at)
    }

    private val operation: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000091")
    private val at: Instant = Instant.parse("2026-09-10T12:00:00Z")

    private val snapshotJson = """
        {"drug":{"id":"$PACK","name":"Парацетамол","quantity":"17.000000","quantityUnitId":"${TABLETS.id}",
         "formTypeId":"${TABLET_FORM.id}","medKitId":"$HOME_KIT","version":4},
         "reservations":{"total":"4.000000","mine":"4.000000","version":2}}
    """

    private val snapshot: PackageSnapshot = resolved(snapshotJson)

    /** Снимок, каким его отдаёт резолвер: собранный в домен, аптечка — домашняя. */
    private fun resolved(json: String): PackageSnapshot =
        medAppJson.decodeFromString(PackageSnapshotNetworkDTO.serializer(), json)
            .toDomain(VOCABULARY, medKit(publication = MedKit.Publication.PUBLISHED).ref, addedAt = at, observedAt = at)

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        // Полка общая: снимок ложится на коробку только там, где у неё есть сервер (PLAN E6).
        database.medKits().upsert(medKit(publication = MedKit.Publication.PUBLISHED).toMedKitStorageEntity())
        val paracetamol = pack(quantity = tablets("20"), form = TABLET_FORM)
        database.packages().save(paracetamol, PackageSyncState(PACK, ResourceVersion(3), ResourceVersion(1), at))
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun takingFreezesTheRequestWithThePackagesPreconditionsAndMarksSending() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)

        val taken = (storage.take(operation, null, at) as Take.Sending).operation

        val request = requireNotNull(taken.prepared)
        assertEquals(SyncOperationStatus.SENDING, taken.status)
        assertEquals(ResourceVersion(3), request.drugVersion)
        assertEquals(tablets("20"), request.quantityBefore)
        assertTrue(request.body!!.contains("\"drugVersion\":3"))
        assertEquals(listOf(taken.id), storage.ready(at.plusSeconds(600)).map { it.id })
    }

    /** Свежее состояние ложится первым, и запрос везёт его версию и остаток, а не те, что лежали в строке. */
    @Test
    fun takingWithAFreshSnapshotAppliesItAndFreezesItsPreconditions() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)

        val taken = (storage.take(operation, snapshot, at) as Take.Sending).operation

        val request = requireNotNull(taken.prepared)
        assertEquals(ResourceVersion(4), request.drugVersion)
        assertEquals(ResourceVersion(2), request.claimsVersion)
        assertEquals(tablets("17"), request.quantityBefore)
        assertEquals(tablets("4"), request.mineBefore)
        assertEquals(tablets("17"), requireNotNull(database.packageRepository().find(PACK)).quantity)
    }

    @Test
    fun takingAgainDoesNotRebuildTheRequest() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        val first = (storage.take(operation, null, at) as Take.Sending).operation.prepared
        // Версия пачки ушла вперёд — а замороженный запрос остался с прежней (PLAN E2).
        val moved = pack(quantity = tablets("20"), form = TABLET_FORM)
        database.packages().applySnapshot(
            moved.toStorageEntity(PackageSyncState(PACK, ResourceVersion(9), ResourceVersion(1), at)),
            claims = null,
            observedAt = at
        )

        val second = (storage.take(operation, null, at.plusSeconds(60)) as Take.Sending).operation.prepared

        assertEquals(first, second)
        assertEquals(ResourceVersion(3), second!!.drugVersion)
    }

    @Test
    fun settlingDoneAppliesTheSnapshotClosesTheOperationAndAccountsTheIntake() = runTest {
        database.intakes().upsert(
            unplannedIntake(takenAmount = dose("3")).toIntakeStorageEntity(
                IntakeSyncState(INTAKE, IntakeAccounting.PENDING, operationId = operation)
            )
        )
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        storage.take(operation, null, at)

        storage.settle(operation, Delivery.Applied(PackageState.Present(snapshot)), at.plusSeconds(1))

        val row = requireNotNull(database.packages().find(PACK))
        val pkg = row.toDomain(VOCABULARY)
        assertEquals(tablets("17"), pkg.quantity)
        assertEquals(ResourceVersion(4), row.pack.syncState().version)
        assertEquals(ResourceVersion(2), row.pack.syncState().claimsVersion)
        assertNotNull(pkg.claims)
        val stored = requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable
        assertEquals(SyncOperationStatus.APPLIED, stored.operation.status)
        // Счёт попыток — вход задержки повтора, и только он: закрытой операции повторяться
        // незачем, поэтому закрытие его не двигает (PLAN E2, E3).
        assertEquals(Attempts(0), stored.operation.attempts)
        assertEquals(IntakeAccounting.REMOTE_APPLIED, requireNotNull(database.intakes().findEntity(INTAKE)).accounting)
        assertEquals(IntakeStatus.TAKEN, requireNotNull(database.intakes().findEntity(INTAKE)).status)
        assertTrue(storage.ready(at.plusSeconds(600)).isEmpty())
    }

    @Test
    fun settlingRetryKeepsTheOperationPendingWithTheError() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        storage.take(operation, null, at)

        storage.settle(operation, Delivery.Retry("обрыв"), at.plusSeconds(1))

        val stored = requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable
        assertEquals(SyncOperationStatus.PENDING, stored.operation.status)
        assertEquals("обрыв", stored.operation.lastError)
        assertEquals(tablets("20"), requireNotNull(database.packages().find(PACK)).toDomain(VOCABULARY).quantity)
    }

    /** На сервере пачки нет по нашей же причине: коробки нет, курс без источника (D3). */
    @Test
    fun packageGoneFromTheServerIsGoneLocally() = runTest {
        holdByACourse()
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("20"), INTAKE), at)
        storage.take(operation, null, at)

        storage.settle(operation, Delivery.Applied(PackageState.Gone), at.plusSeconds(1))

        assertNull(database.packages().find(PACK))
        assertEquals(emptyList<Uuid>(), database.courses().sourcePackagesOf(COURSE))
        assertEquals(Revision(2), requireNotNull(database.courses().findPlan(COURSE)).toPlan(VOCABULARY).revision)
    }

    /** Доступ утрачен: коробки и источника нет. */
    @Test
    fun accessLostRemovesThePackage() = runTest {
        holdByACourse()
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        storage.take(operation, null, at)

        storage.settle(operation, Delivery.AccessLost, at.plusSeconds(1))

        assertNull(database.packages().find(PACK))
        assertEquals(emptyList<Uuid>(), database.courses().sourcePackagesOf(COURSE))
        assertTrue(storage.ready(at.plusSeconds(600)).isEmpty())
    }

    /**
     * Пометку снимает только та команда, которая её поставила. Решение полки — выбросить её вместе
     * с лекарствами — держит пометку коробки до **своего** ответа, и старая бронь, доехавшая
     * позже, коробку в оборот не возвращает (PLAN E1).
     *
     * Красная проверка: пока пометку снимало закрытие последней незакрытой команды коробки, между
     * ответом на бронь и ответом на уборку полки коробка становилась обычной — из неё можно было
     * принять и назначить её курсом, а через секунду она исчезала вместе с полкой.
     */
    @Test
    fun anOlderCommandOfTheBoxDoesNotReleaseTheShelfsDecision() = runTest {
        val claim = PackageSyncCommand.ReleaseClaim(PACK)
        val older = storage.enqueue(QueuedCommand(Uuid.random(), claim), HOME_KIT, at)
        val removal = storage.enqueue(QueuedCommand(Uuid.random(), MedKitSyncCommand.Delete(HOME_KIT)), HOME_KIT, at.plusSeconds(1))
        assertTrue(database.packageRepository().mark(PACK, PackageStatus.REMOVING, by = removal.id))
        assertTrue(database.medKitRepository().mark(HOME_KIT, MedKitStatus.REMOVING))

        storage.settle(older.id, Delivery.Applied(PackageState.None), at.plusSeconds(2))

        val pkg = requireNotNull(database.packageRepository().find(PACK))
        assertEquals(PackageStatus.REMOVING, pkg.status)
        assertEquals(removal.id, pkg.decidedBy)
        assertFalse(pkg.take(dose("1"), at).isSuccess)
    }

    /** Отказ по команде полки возвращает её коробки в оборот: решение доведено, и ждать нечего. */
    @Test
    fun theRefusalOfTheShelfsCommandReleasesTheBoxesItMarked() = runTest {
        val removal = storage.enqueue(QueuedCommand(Uuid.random(), MedKitSyncCommand.Delete(HOME_KIT)), HOME_KIT, at)
        assertTrue(database.packageRepository().mark(PACK, PackageStatus.REMOVING, by = removal.id))
        assertTrue(database.medKitRepository().mark(HOME_KIT, MedKitStatus.REMOVING))

        storage.settle(removal.id, Delivery.Refused(RefusalReason.CONFLICT, PackageState.None), at.plusSeconds(1))

        val pkg = requireNotNull(database.packageRepository().find(PACK))
        assertEquals(PackageStatus.ACTIVE, pkg.status)
        assertNull(pkg.decidedBy)
        assertEquals(MedKitStatus.ACTIVE, database.medKitRepository().find(HOME_KIT)?.status)
    }

    /**
     * Снимок, пришедший, пока решение ждёт, пометку не снимает: статус — наше решение, а не
     * сведения сервера, и снимает его только закрытие команды (PLAN E1).
     */
    @Test
    fun aSnapshotDoesNotReleaseTheMark() = runTest {
        val decision = Uuid.random()
        assertTrue(database.packageRepository().mark(PACK, PackageStatus.REMOVING, by = decision))

        database.packageRepository().applySnapshot(snapshot, at)

        val pkg = requireNotNull(database.packageRepository().find(PACK))
        assertEquals(tablets("17"), pkg.quantity)
        assertEquals(PackageStatus.REMOVING, pkg.status)
        // Снимок переписывает серверную часть целиком, и владелец решения переживает её вместе с
        // пометкой: иначе снять пометку стало бы некому (PLAN E1).
        assertEquals(decision, pkg.decidedBy)
    }

    /**
     * Коробку на местной полке снимок не переставляет и не пересчитывает, но обе её версии ведёт:
     * команды, поставленные до уноса домой, ещё доставляются по ним (PLAN E3, E6).
     *
     * Красная проверка: без версии броней расход с бронью переподготавливается без конца — так проба
     * на сервере и поймала этот случай.
     */
    @Test
    fun aSnapshotOfABoxOnALocalShelfBringsOnlyItsVersions() = runTest {
        database.medKits().upsert(medKit().toMedKitStorageEntity())

        database.packageRepository().applySnapshot(snapshot, at)

        val row = requireNotNull(database.packages().find(PACK))
        assertEquals(tablets("20"), row.toDomain(VOCABULARY).quantity)
        assertEquals(HOME_KIT, row.pack.medKitId)
        assertEquals(ResourceVersion(4), row.pack.syncState().version)
        assertEquals(ResourceVersion(2), row.pack.syncState().claimsVersion)
    }

    /**
     * Унесённую домой коробку человек уже выбросил у себя, а сервер о ней ещё знает: она всё равно
     * снимается — по версии свежего снимка, — и снимок её обратно не заводит (PLAN E6).
     */
    @Test
    fun aBoxThrownAwayAtHomeIsStillTakenOffTheServer() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Withdraw(PACK, HOME_KIT, tablets("20")), at)
        database.packages().delete(PACK)

        val taken = (storage.take(operation, snapshot, at) as Take.Sending).operation

        assertEquals("DELETE", taken.prepared?.method)
        assertEquals(ResourceVersion(4), taken.prepared?.drugVersion)
        assertNull(database.packages().find(PACK))
    }

    /**
     * Унесли при 20, дома выпили одну, а полка к снятию подтвердила 17. Когда сервер коробку забыл,
     * у нас она местная и с 16: чужой расход и свой домашний учтены оба (PLAN E6).
     */
    @Test
    fun carryingHomeCountsWhatTheShelfConfirmedAndWhatWasTakenAtHome() = runTest {
        database.medKits().upsert(medKit().toMedKitStorageEntity())
        database.packages().save(pack(quantity = tablets("19"), form = TABLET_FORM), PackageSyncState(PACK, ResourceVersion(3), null, at))
        database.syncOperations().enqueue(operation, PackageSyncCommand.Withdraw(PACK, com.kert0n.medapp.fixture.SHARED_KIT, tablets("20")), at)

        storage.take(operation, snapshot, at)
        storage.settle(operation, Delivery.Applied(PackageState.Gone), at.plusSeconds(1))

        val row = requireNotNull(database.packages().find(PACK))
        assertEquals(tablets("16"), row.toDomain(VOCABULARY).quantity)
        assertNull(row.pack.syncState().version)
    }

    /** Курс, держащий пачку: назначение и источник, как их пишет активация. */
    private suspend fun holdByACourse() {
        val plan = activeCourse(sources = listOf(source(PACK, 5)))
        database.courses().saveCourse(
            plan.toCourseStorageEntity(),
            plan.schedule.toTimeStorageEntities(COURSE),
            plan.medicine.toSourceStorageEntities(COURSE)
        )
        database.courses().assignPackage(ActivePackageAssignmentStorageEntity(PACK, COURSE))
        // Событие сокращения держится за запись эпизода: у идущего лечения она есть всегда.
        database.courses().upsertRecord(courseRecord(prescription = plan.prescription).toCourseStorageEntity())
    }

    @Test
    fun dependentOperationWaitsForItsDependency() = runTest {
        val release = Uuid.parse("00000000-0000-4000-8000-000000000092")
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE, claimAfter = tablets("0")), at)
        database.syncOperations().enqueue(release, PackageSyncCommand.ReleaseClaim(PACK), at, dependsOn = setOf(operation))

        assertEquals(listOf(operation), storage.ready(at.plusSeconds(600)).map { it.id })
        storage.take(operation, null, at)
        storage.settle(operation, Delivery.Applied(PackageState.Present(snapshot)), at)
        assertEquals(listOf(release), storage.ready(at.plusSeconds(600)).map { it.id })
    }

    /** «Устарело» — не закрытие: снимок ложится, запрос сбрасывается, операция снова ждёт под тем же номером. */
    @Test
    fun staleAppliesTheSnapshotDropsTheRequestAndLeavesTheOperationPending() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        val frozen = (storage.take(operation, null, at) as Take.Sending).operation.prepared
        // Исход неизвестен — факт принадлежит этому запросу, а не операции.
        storage.settle(operation, Delivery.Retry("ответ потерян", outcomeUnknown = true), at)
        val taken = (storage.take(operation, null, at.plusSeconds(1)) as Take.Sending).operation
        assertTrue(taken.outcomeUnknown)

        storage.settle(operation, Delivery.Stale(snapshot), at.plusSeconds(1))

        val stored = requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable
        assertEquals(SyncOperationStatus.PENDING, stored.operation.status)
        assertNull(stored.operation.prepared)
        // Запрос сброшен — сброшен и факт о нём; счёт попыток остаётся у операции как вход задержки.
        assertFalse(stored.operation.outcomeUnknown)
        assertEquals(Attempts(1), stored.operation.attempts)
        assertEquals(tablets("17"), requireNotNull(database.packages().find(PACK)).toDomain(VOCABULARY).quantity)
        // Заново — уже по свежему состоянию, а не по прежнему запросу.
        val again = (storage.take(operation, null, at.plusSeconds(2)) as Take.Sending).operation.prepared
        assertEquals(ResourceVersion(4), again!!.drugVersion)
        assertEquals(ResourceVersion(3), frozen!!.drugVersion)
    }

    /** Отказ родителя отказывает зависимых: им нужен был эффект, которого не будет; расход виден как непринятый. */
    @Test
    fun refusalCascadesToDependentsAndMarksTheIntakeRefused() = runTest {
        val release = Uuid.parse("00000000-0000-4000-8000-000000000092")
        database.intakes().upsert(
            unplannedIntake(takenAmount = dose("3")).toIntakeStorageEntity(
                IntakeSyncState(INTAKE, IntakeAccounting.PENDING, operationId = operation)
            )
        )
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE, claimAfter = tablets("0")), at)
        database.syncOperations().enqueue(release, PackageSyncCommand.ReleaseClaim(PACK), at, dependsOn = setOf(operation))
        storage.take(operation, null, at)

        storage.settle(operation, Delivery.Refused(RefusalReason.INSUFFICIENT, PackageState.Present(snapshot)), at.plusSeconds(1))

        val refused = requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable
        val dependent = requireNotNull(database.syncOperations().find(release)).toDomain(VOCABULARY) as StoredSyncOperation.Readable
        assertEquals(SyncOperationStatus.REFUSED, refused.operation.status)
        assertEquals(RefusalReason.INSUFFICIENT, refused.operation.refusalReason)
        assertEquals("INSUFFICIENT", refused.operation.lastError)
        assertEquals(SyncOperationStatus.REFUSED, dependent.operation.status)
        assertEquals(RefusalReason.SUPERSEDED, dependent.operation.refusalReason)
        assertEquals(IntakeAccounting.REMOTE_REFUSED, requireNotNull(database.intakes().findEntity(INTAKE)).accounting)
        assertEquals(tablets("17"), requireNotNull(database.packages().find(PACK)).toDomain(VOCABULARY).quantity)
        assertTrue(storage.ready(at.plusSeconds(600)).isEmpty())
    }

    /** Единицу пачки сменили на сервере: расход закрывается отказом при взятии, не тревожа сервер. */
    @Test
    fun takingClosesTheOperationWhenThePreparationRefusesIt() = runTest {
        database.intakes().upsert(
            unplannedIntake(takenAmount = dose("3")).toIntakeStorageEntity(
                IntakeSyncState(INTAKE, IntakeAccounting.PENDING, operationId = operation)
            )
        )
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        val inMillilitres = resolved(snapshotJson.replace(TABLETS.id.toString(), com.kert0n.medapp.fixture.MILLILITRES.id.toString()))

        val take = storage.take(operation, inMillilitres, at)

        assertEquals(Take.Closed(Delivery.Refused(RefusalReason.UNIT_CHANGED, PackageState.None)), take)
        val stored = requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable
        assertEquals(SyncOperationStatus.REFUSED, stored.operation.status)
        assertEquals(IntakeAccounting.REMOTE_REFUSED, requireNotNull(database.intakes().findEntity(INTAKE)).accounting)
        assertEquals(com.kert0n.medapp.fixture.millilitres("17"), requireNotNull(database.packages().find(PACK)).toDomain(VOCABULARY).quantity)
        // Отправки не было вовсе: подготовка закрыла операцию сама, и попытке взяться неоткуда.
        assertEquals(Attempts(0), stored.operation.attempts)
    }

    /**
     * `attempts` растёт только там, где от него зависит задержка следующего захода — `Retry` и
     * `defer`. Закрытие не повторяется, и счёт ему не принадлежит (PLAN E2, E3).
     *
     * Красная проверка: вернуть закрытию `attempted = 1` — оба случая краснеют.
     */
    @Test
    fun closingDoesNotCountAnAttemptWhileRetryDoes() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        storage.take(operation, null, at)

        storage.settle(operation, Delivery.Retry("обрыв", notBefore = at.plusSeconds(30)), at)
        val retried = (requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation
        assertEquals(Attempts(1), retried.attempts)

        storage.take(operation, null, at.plusSeconds(31))
        storage.settle(operation, Delivery.Applied(PackageState.Present(snapshot)), at.plusSeconds(32))

        val closed = (requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation
        assertEquals(SyncOperationStatus.APPLIED, closed.status)
        assertEquals(Attempts(1), closed.attempts)
    }

    /** Полученный ответ записан до применения: он в базе, операция готова к закрытию без сети. */
    @Test
    fun anAnswerIsKeptWithTheOperationUntilItIsSettled() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        storage.take(operation, null, at)

        storage.answered(operation, RawResponse(200, snapshotJson), at.plusSeconds(1))
        storage.defer(operation, "словарь не знает единицу", at.plusSeconds(2), notBefore = at.plusSeconds(4))

        val stored = (requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation
        assertEquals(SyncOperationStatus.ANSWERED, stored.status)
        assertEquals(RawResponse(200, snapshotJson), stored.answer)
        assertEquals(Attempts(1), stored.attempts)
        assertEquals(listOf(operation), storage.ready(at.plusSeconds(600)).map { it.id })
        assertNull(storage.take(operation, null, at.plusSeconds(3)))

        storage.settle(operation, Delivery.Applied(PackageState.Present(snapshot)), at.plusSeconds(4))
        val settled = (requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation
        assertEquals(SyncOperationStatus.APPLIED, settled.status)
        assertNull(settled.answer)
    }

    /** Готовность — одно определение в запросе: срок, зависимости и порядок по пачке. */
    @Test
    fun readinessIsTheTermTheDependenciesAndTheOrderWithinThePackage() = runTest {
        val second = Uuid.parse("00000000-0000-4000-8000-000000000093")
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        database.syncOperations().enqueue(second, PackageSyncCommand.Consume(PACK, dose("1"), second), at)
        storage.take(operation, null, at)

        storage.settle(operation, Delivery.Retry("обрыв", notBefore = at.plusSeconds(30)), at)

        // Первая ждёт срока, вторая ждёт первую: до срока готовых нет, после — только первая.
        assertTrue(storage.ready(at.plusSeconds(10)).isEmpty())
        assertEquals(listOf(operation), storage.ready(at.plusSeconds(31)).map { it.id })
    }

    /**
     * Запоздалый снимок свежий не перекрывает: меньшая версия большую не откатывает (PLAN E1).
     * Половина, которая не запоздала, при этом ложится: версии независимы, и картина броней с
     * версией 2 поверх известной 1 — новость, даже когда состояние пачки старее (PLAN B3).
     */
    @Test
    fun anOlderSnapshotDoesNotOverwriteANewerOne() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        storage.take(operation, null, at)
        val older = resolved(snapshotJson.replace("\"version\":4", "\"version\":2").replace("17.000000", "19.000000"))

        storage.settle(operation, Delivery.Applied(PackageState.Present(older)), at.plusSeconds(1))

        val row = requireNotNull(database.packages().find(PACK))
        assertEquals(tablets("20"), row.toDomain(VOCABULARY).quantity)
        assertEquals(ResourceVersion(3), row.pack.syncState().version)
        assertEquals(BigDecimal("4.000000"), requireNotNull(row.toDomain(VOCABULARY).claims).total)
        assertEquals(ResourceVersion(2), row.pack.syncState().claimsVersion)
        assertEquals(SyncOperationStatus.APPLIED, (requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.status)
    }

    /** Запоздалая картина броней не откатывает свежую, даже когда состояние пачки ложится (B3, E1). */
    @Test
    fun anOlderClaimsHalfDoesNotOverwriteANewerOne() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        storage.take(operation, snapshot, at)
        val staleClaims = resolved(
            snapshotJson.replace("\"version\":4", "\"version\":5")
                .replace("\"total\":\"4.000000\",\"mine\":\"4.000000\",\"version\":2", "\"total\":\"9.000000\",\"version\":1")
        )

        storage.settle(operation, Delivery.Applied(PackageState.Present(staleClaims)), at.plusSeconds(1))

        val row = requireNotNull(database.packages().find(PACK))
        assertEquals(ResourceVersion(5), row.pack.syncState().version)
        assertEquals(BigDecimal("4.000000"), requireNotNull(row.toDomain(VOCABULARY).claims).total)
        assertEquals(ResourceVersion(2), row.pack.syncState().claimsVersion)
    }

    /**
     * Сигнал таблицы приходит **после** коммита внешней транзакции: тот, кто его услышал, видит
     * операцию в `ready`. Это и есть outbox — команду забирает не тот, кто положил (PLAN E4, F5).
     * Красная проверка: подделка, зовущая сигнал внутри транзакции, увидела бы пустую очередь.
     */
    @Test
    fun theChangeSignalArrivesAfterTheOuterTransactionCommits() = runBlocking {
        val seen = CompletableDeferred<List<Uuid>>()
        val subscribed = CompletableDeferred<Unit>()
        val watcher = launch(Dispatchers.IO) {
            // Первое значение — «наблюдатель встал», второе — изменение (OutboxLoop).
            storage.changes().collectIndexed { index, _ ->
                if (index == 0) subscribed.complete(Unit) else if (!seen.isCompleted) seen.complete(storage.ready(at.plusSeconds(1)).map { it.id })
            }
        }
        withTimeout(5_000) { subscribed.await() }

        database.transactions().run {
            storage.enqueue(QueuedCommand(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE)), HOME_KIT, at)
            delay(300) // транзакция ещё открыта: сигнала быть не должно
            assertFalse(seen.isCompleted)
        }

        assertEquals(listOf(operation), withTimeout(5_000) { seen.await() })
        watcher.cancel()
    }

    /**
     * **Зависимая закрывается тем же переходом, что и своя.** Утрата доступа причины не имеет — ни
     * у родителя, ни у зависимой: каскад не сочиняет закрытие сам, а несёт `Transition.Close`, и
     * `last_error` у зависимой такой же, каким его пишет собственное закрытие.
     */
    @Test
    fun accessLostCascadesWithoutARefusalReason() = runTest {
        val release = Uuid.parse("00000000-0000-4000-8000-000000000092")
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE, claimAfter = tablets("0")), at)
        database.syncOperations().enqueue(release, PackageSyncCommand.ReleaseClaim(PACK), at, dependsOn = setOf(operation))
        storage.take(operation, null, at)

        storage.settle(operation, Delivery.AccessLost, at.plusSeconds(1))

        val dependent = (requireNotNull(database.syncOperations().find(release)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation
        assertEquals(SyncOperationStatus.ACCESS_LOST, dependent.status)
        assertNull("утрата доступа без причины", dependent.refusalReason)
        assertNull("зависимая закрыта не тем переходом, что своя: last_error", dependent.lastError)
    }

    /**
     * **Нечитаемая строка закрывается тем же переходом, что и читаемая.** Состояние отправки
     * читается без словаря — оно в колонках, — и учётка, которую заменили, закрывает обе одинаково:
     * `ACCESS_LOST`, без причины и без строки журнала. Пока у нечитаемой была своя SQL-дверь, она
     * писала «учётка заменена» туда, где читаемая не пишет ничего.
     */
    @Test
    fun anUnreadableRowIsClosedLikeAReadableOne() = runTest {
        val readable = operation
        val unreadable = Uuid.parse("00000000-0000-4000-8000-000000000093")
        database.syncOperations().enqueue(readable, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at, medKitId = HOME_KIT)
        val stored = database.syncOperations().enqueue(unreadable, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), at, medKitId = HOME_KIT).toStorageEntity(HOME_KIT)
        database.syncOperations().update(
            SyncOperationStorageEntity(
                id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = 99,
                sequence = stored.sequence, status = stored.status, attempts = stored.attempts,
                createdAt = stored.createdAt, packageId = stored.packageId, medKitId = stored.medKitId
            )
        )
        assertTrue(database.queueRepository().stored(unreadable) is StoredSyncOperation.Unreadable)

        assertEquals(1, database.medKitRepository().abandonServer(at.plusSeconds(1)))

        val closed = listOf(readable, unreadable).map { requireNotNull(database.syncOperations().find(it)).operation }
        for (row in closed) {
            assertEquals(SyncOperationStatus.ACCESS_LOST, row.status)
            assertNull("утрата доступа без причины: ${row.id}", row.refusalReason)
            assertNull("закрыта не тем переходом, что читаемая: last_error у ${row.id}", row.lastError)
            assertEquals("момент закрытия — у обеих", at.plusSeconds(1), row.lastTriedAt)
        }
    }

    /**
     * **Строка, противоречащая себе, читается как нечитаемая, а не роняет чтение очереди.**
     * Колонки состояния могут разойтись между собой — записанный ответ у ждущей, причина отказа у
     * применённой, — и строгий тип такое состояние не выражает. Нечитаемая строка всё равно должна
     * читаться, закрываться и уходить с экрана: одна порченая строка не останавливает очередь (F4).
     */
    @Test
    fun aRowThatContradictsItselfIsUnreadableAndStillClosable() = runTest {
        val broken = Uuid.parse("00000000-0000-4000-8000-000000000094")
        val stored = database.syncOperations().enqueue(broken, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), at, medKitId = HOME_KIT).toStorageEntity(HOME_KIT)
        database.syncOperations().update(
            SyncOperationStorageEntity(
                id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = stored.payloadVersion,
                sequence = stored.sequence, status = SyncOperationStatus.PENDING, attempts = stored.attempts,
                createdAt = stored.createdAt, packageId = stored.packageId, medKitId = stored.medKitId,
                // Ответ и причина отказа у ждущей строки: так не бывает — строка порчена.
                answerStatus = 200, answerBody = "{}", refusalReason = RefusalReason.INVALID
            )
        )

        val read = database.queueRepository().stored(broken)

        assertTrue("порченая строка не прочиталась как нечитаемая: $read", read is StoredSyncOperation.Unreadable)
        assertEquals(1, database.medKitRepository().abandonServer(at.plusSeconds(1)))
        assertEquals(SyncOperationStatus.ACCESS_LOST, requireNotNull(database.syncOperations().find(broken)).operation.status)
    }

    /**
     * **Статус без своей колонки бесспорным не считается.** Противоречие бывает и нехваткой:
     * `ANSWERED` без записанного ответа, `REFUSED` без причины. Такая строка тоже читается —
     * нечитаемой, — и очередь читается целиком: не отвеченная на деле строка остаётся ждущей и
     * закрывается, а закрытая без вида отказа остаётся закрытой.
     */
    @Test
    fun aStatusWithoutItsColumnIsNotTakenOnTrust() = runTest {
        val answeredWithoutAnswer = Uuid.parse("00000000-0000-4000-8000-000000000095")
        val refusedWithoutReason = Uuid.parse("00000000-0000-4000-8000-000000000096")
        for ((id, status) in listOf(answeredWithoutAnswer to SyncOperationStatus.ANSWERED, refusedWithoutReason to SyncOperationStatus.REFUSED)) {
            val stored = database.syncOperations().enqueue(id, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), at, medKitId = HOME_KIT).toStorageEntity(HOME_KIT)
            database.syncOperations().update(
                SyncOperationStorageEntity(
                    id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = stored.payloadVersion,
                    sequence = stored.sequence, status = status, attempts = stored.attempts,
                    createdAt = stored.createdAt, packageId = stored.packageId, medKitId = stored.medKitId
                )
            )
        }

        val read = listOf(answeredWithoutAnswer, refusedWithoutReason).map { database.queueRepository().stored(it) }

        assertTrue("строка со статусом без своей колонки не прочиталась: $read", read.all { it is StoredSyncOperation.Unreadable })
        assertEquals("чтение очереди целиком спотыкается о порченую строку", 2, database.queueRepository().unreadable().size)
        assertEquals(1, database.medKitRepository().abandonServer(at.plusSeconds(1)))
        assertEquals(SyncOperationStatus.ACCESS_LOST, requireNotNull(database.syncOperations().find(answeredWithoutAnswer)).operation.status)
        assertEquals("закрытая остаётся закрытой", SyncOperationStatus.REFUSED, requireNotNull(database.syncOperations().find(refusedWithoutReason)).operation.status)
    }

    /** Закрытие одно: закрытую операцию второй исход не переписывает и следствий не оставляет. */
    @Test
    fun aClosedOperationIsNotClosedAgain() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Consume(PACK, dose("3"), INTAKE), at)
        storage.take(operation, null, at)
        storage.settle(operation, Delivery.Applied(PackageState.Present(snapshot)), at.plusSeconds(1))

        storage.settle(operation, Delivery.AccessLost, at.plusSeconds(2))

        val stored = (requireNotNull(database.syncOperations().find(operation)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation
        assertEquals(SyncOperationStatus.APPLIED, stored.status)
        assertNotNull(database.packages().find(PACK))
        assertNull(storage.take(operation, null, at.plusSeconds(3)))
    }
}
