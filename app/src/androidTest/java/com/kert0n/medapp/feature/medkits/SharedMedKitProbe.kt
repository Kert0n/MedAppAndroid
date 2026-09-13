package com.kert0n.medapp.feature.medkits

import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.stock.StockMovement
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.feature.course.CourseClosing
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.FIRST_PLANNED_AT
import com.kert0n.medapp.fixture.FIRST_SCHEDULED_ON
import com.kert0n.medapp.fixture.ProbeAccounts
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.queueService
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.transactions
import com.kert0n.medapp.network.medkit.MedKitPublication
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.toQuantityUnit
import com.kert0n.medapp.queue.PackageSnapshotResolver
import com.kert0n.medapp.queue.QueueHttpTransport
import com.kert0n.medapp.queue.QueueWorker
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.medkit.MedKitPublishing
import com.kert0n.medapp.storage.intake.toStorageEntity as toIntakeStorageEntity
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Двое делят аптечку — по-настоящему, против боевого сервера (AGENTS «Связь с сервером»). Анна и
 * Борис — два устройства в одном процессе: у каждого своя база, свой клиент со своей пробной
 * учёткой и своя очередь, собранные из тех же частей, что у приложения. Так условия задаются
 * точно — кто что успел доставить и в каком порядке, — а не гонкой двух эмуляторов.
 *
 * Общая завязка у трёх случаев одна: Анна публикует полку с коробкой, Борис вступает, лечится из
 * этой коробки, и один его приём уже на сервере. Потом Анна убирает полку — к себе в местную, к
 * себе в общую или в общую на двоих, — и проверяется, что сделали обе очереди и оба лечения.
 *
 * Вступление Бориса кладёт полку и её коробки в его базу **здесь, в пробе**: в приложении пока
 * нет сценария, который читает общие полки с сервера. Всё остальное идёт путями приложения.
 *
 * Включается только `-Pprobe`, как и [com.kert0n.medapp.network.server.ContractProbe]. Синтетические
 * полки проба удаляет за собой.
 */
class SharedMedKitProbe {

    companion object {
        private fun <T> success(result: ApiResult<T>): T = when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> throw AssertionError("ожидался успех: $result")
        }

        private fun failure(result: ApiResult<*>): ApiFailure =
            (result as? ApiResult.Failure)?.failure ?: throw AssertionError("ожидался отказ: $result")
    }

    private lateinit var anna: Device
    private lateinit var boris: Device
    private lateinit var unit: QuantityUnit
    private val shelves = mutableListOf<Uuid>()

    @Before
    fun open(): Unit = runBlocking {
        // Клиенты общие на весь прогон: пропуск выдаётся один раз на пользователя (ProbeAccounts).
        val skipReason = ProbeAccounts.skipReason
        assumeTrue(skipReason.orEmpty(), skipReason == null)
        anna = Device(requireNotNull(ProbeAccounts.anna))
        boris = Device(requireNotNull(ProbeAccounts.boris))
        success(anna.vocabulary.refresh())
        success(boris.vocabulary.refresh())
        unit = success(anna.api.quantityUnits()).first().toQuantityUnit()
    }

    @After
    fun close(): Unit = runBlocking {
        if (ProbeAccounts.skipReason != null) return@runBlocking
        val left = shelves.filter { shelf ->
            val result = anna.api.deleteMedKit(shelf)
            result is ApiResult.Failure && result.failure != ApiFailure.NotFound
        }
        anna.database.close()
        boris.database.close()
        if (left.isNotEmpty()) throw AssertionError("синтетические полки остались на боевом сервере: $left")
    }

    private fun pills(amount: String) = Quantity(BigDecimal(amount), unit)

    private fun twoPills() = Dose(pills("2"))

    /** Полка, коробка и лечение Бориса из неё; первый его приём уже доставлен. */
    private class Shared(val shelf: Uuid, val box: Uuid, val intakes: List<Uuid>)

    private suspend fun sharedShelfWithBorisTreated(): Shared {
        val shelf = anna.localShelf("Общая")
        val box = anna.addBox(shelf, "20")
        anna.publish(shelf)
        boris.join(success(anna.api.createInvitation(shelf)).key)
        val intakes = boris.treatFrom(box)

        boris.confirm(intakes[0], box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(intakes[0]))
        assertAmount("18", serverAmount(box))
        assertNotNull("бронь Бориса легла вместе с расходом", success(boris.api.packageSnapshot(box)).claims.mine)
        return Shared(shelf, box, intakes)
    }

    /**
     * Анна забрала полку домой, в свою местную. Сервер коробку забыл и полку удалил у всех; у Анны
     * коробка дома, обычная и с тем, что в ней осталось на самом деле, — приём Бориса, доставленный
     * раньше, учтён. Борис узнаёт об этом своим следующим приёмом: коробки у него больше нет,
     * последний виденный остаток уходит в историю, лечение теряет источник и продолжается.
     */
    @Test
    fun annaTakesTheShelfHomeIntoHerLocalOne(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val home = anna.localShelf("Дом")

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(home)))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        val carried = requireNotNull(anna.packages.find(shared.box))
        assertEquals(home, carried.medKit.id)
        assertEquals(PackageStatus.ACTIVE, carried.status)
        assertAmount("18", carried.quantity.amount)
        assertNull(anna.database.medKits().find(shared.shelf))
        assertEquals(ApiFailure.NotFound, failure(anna.api.packageSnapshot(shared.box)))

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /**
     * Анна переставила полку в свою общую, где Бориса нет. Сервер переставил коробку сам, а бронь
     * Бориса снял: бронь держит тот, кто коробку видит. У Анны коробка на новой полке и обычная. Борис полки не видит: приём
     * кончается утратой доступа, лечение теряет источник.
     */
    @Test
    fun annaMovesTheShelfIntoHerOwnSharedOne(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val own = anna.localShelf("Моя общая")
        anna.publish(own)

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(own)))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        val moved = requireNotNull(anna.packages.find(shared.box))
        assertEquals(own, moved.medKit.id)
        assertEquals(PackageStatus.ACTIVE, moved.status)
        assertNull(anna.database.medKits().find(shared.shelf))
        val onServer = success(anna.api.packageSnapshot(shared.box))
        assertEquals(own, onServer.pack.medKitId)
        // Бронь держит тот, кто коробку видит: Бориса на новой полке нет, и сервер его бронь снял.
        assertEquals("бронь Бориса снята вместе с доступом", 0, BigDecimal(onServer.claims.total).signum())

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /**
     * Анна переставила полку в другую общую, где они оба. Коробка перепрыгнула на полку, которую
     * Борис знает: его приём доставляется, коробка у него на новой полке, лечение её держит, бронь
     * его.
     */
    @Test
    fun annaMovesTheShelfIntoAnotherOneTheyShare(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val dacha = anna.localShelf("Дача")
        anna.publish(dacha)
        boris.join(success(anna.api.createInvitation(dacha)).key)

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(dacha)))
        anna.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        assertEquals(dacha, requireNotNull(anna.packages.find(shared.box)).medKit.id)

        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), boris.statuses().distinct())
        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(shared.intakes[1]))
        val jumped = requireNotNull(boris.packages.find(shared.box))
        assertEquals(dacha, jumped.medKit.id)
        assertAmount("16", jumped.quantity.amount)
        assertEquals(listOf(shared.box), boris.database.courses().sourcePackagesOf(COURSE))
        assertNotNull(success(boris.api.packageSnapshot(shared.box)).claims.mine)
    }

    /**
     * Борис подтвердил приём офлайн **до** того, как Анна переставила полку, а доставил **после**.
     * Приём не подвешивается и не теряется: коробка перепрыгнула на общую полку, которую он знает,
     * расход применён, лечение держит коробку.
     */
    @Test
    fun borisConfirmsOfflineBeforeTheMoveAndDeliversAfterIt(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val dacha = anna.localShelf("Дача")
        anna.publish(dacha)
        boris.join(success(anna.api.createInvitation(dacha)).key)

        boris.confirm(shared.intakes[1], shared.box)
        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(dacha)))
        anna.drain()
        boris.drain()

        assertEquals(listOf(SyncOperationStatus.APPLIED), anna.statuses().distinct())
        assertEquals(listOf(SyncOperationStatus.APPLIED), boris.statuses().distinct())
        assertEquals(IntakeAccounting.REMOTE_APPLIED, boris.accountingOf(shared.intakes[1]))
        val jumped = requireNotNull(boris.packages.find(shared.box))
        assertEquals(dacha, jumped.medKit.id)
        assertAmount("16", jumped.quantity.amount)
        assertAmount("16", serverAmount(shared.box))
        assertEquals(listOf(shared.box), boris.database.courses().sourcePackagesOf(COURSE))
    }

    /**
     * У Анны свой приём из общей коробки ещё не доставлен, когда она уносит полку домой. Полка ждёт
     * его по номеру: сначала расход, потом унос, потом удаление полки. Дома у Анны то, что осталось
     * на самом деле, — минус приём Бориса и её собственный; её лечение держит коробку.
     */
    @Test
    fun annasEarlierIntakeIsDeliveredBeforeSheCarriesTheShelfHome(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val home = anna.localShelf("Дом")
        val annas = anna.treatFrom(shared.box)
        anna.confirm(annas[0], shared.box)

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(home)))
        anna.drain()

        val delivered = anna.operations()
        val words = anna.vocabulary.snapshot()
        val stuck = anna.database.syncOperations().all()
            .map { (it.toDomain(words) as StoredSyncOperation.Readable).operation }
            .filter { it.status != SyncOperationStatus.APPLIED }
        assertTrue(
            "не доставлено: " + stuck.joinToString { "${it.command} ${it.status} «${it.lastError}» до ${it.notBefore}" },
            stuck.isEmpty()
        )
        assertTrue("расход доставлен раньше уноса", delivered.first().first is com.kert0n.medapp.queue.pack.PackageSyncCommand.Consume)
        assertEquals(IntakeAccounting.REMOTE_APPLIED, anna.accountingOf(annas[0]))
        val carried = requireNotNull(anna.packages.find(shared.box))
        assertEquals(home, carried.medKit.id)
        assertEquals(PackageStatus.ACTIVE, carried.status)
        assertAmount("16", carried.quantity.amount)
        assertEquals(listOf(shared.box), anna.database.courses().sourcePackagesOf(COURSE))
    }

    /**
     * Анна переставила полку в общую, куда Борис вступил **только на сервере**: у него на устройстве
     * этой полки нет. Снимок называет незнакомую полку — это не «подождать», а утрата доступа:
     * операция закрыта, а не отложена навсегда; коробки у Бориса нет, лечение без источника (E6).
     * Когда в приложении появится чтение общих полок, этот случай станет прыжком (сценарий 3).
     */
    @Test
    fun theBoxJumpsIntoAShelfBorisDoesNotHaveOnHisDevice(): Unit = runBlocking {
        val shared = sharedShelfWithBorisTreated()
        val dacha = anna.localShelf("Дача")
        anna.publish(dacha)
        success(boris.api.joinMedKit(MembershipPostNetworkDTO(success(anna.api.createInvitation(dacha)).key)))

        assertEquals(MedKitRemoval.Outcome.MARKED, anna.scenarios().medKitRemoval.remove(shared.shelf, MedKitRemoval.Fate.MoveTo(dacha)))
        anna.drain()
        boris.confirm(shared.intakes[1], shared.box)
        boris.drain()

        assertTrue("операции Бориса закрыты, а не ждут", boris.statuses().none { it == SyncOperationStatus.PENDING || it == SyncOperationStatus.ANSWERED })
        assertEquals(IntakeAccounting.REMOTE_REFUSED, boris.accountingOf(shared.intakes[1]))
        assertBorisLostTheBox(shared.box)
    }

    /** Коробки у Бориса нет, остаток ушёл в историю утратой доступа, лечение без источника, но идёт. */
    private suspend fun assertBorisLostTheBox(box: Uuid) {
        assertNull(boris.packages.find(box))
        val words = boris.vocabulary.snapshot()
        assertTrue(boris.database.stockMovements().ofPackage(box).any { it.toDomain(words) is StockMovement.AccessLoss })
        assertEquals(emptyList<Uuid>(), boris.database.courses().sourcePackagesOf(COURSE))
        assertNotNull(boris.database.courses().findRecord(COURSE))
    }

    private suspend fun serverAmount(box: Uuid): BigDecimal = BigDecimal(success(anna.api.packageSnapshot(box)).pack.amount)

    private fun assertAmount(expected: String, actual: BigDecimal) =
        assertEquals("ожидалось $expected, а не $actual", 0, BigDecimal(expected).compareTo(actual))

    /** Устройство одного человека: своя база, свой клиент, своя очередь — те же части, что у приложения. */
    private inner class Device(val api: MedAppApi) {
        val database = inMemoryDatabase()
        private val clock = Clock.systemUTC()
        private val transactions = database.transactions()
        val vocabulary = VocabularyResolver(VocabularyRoomRepository(database.vocabulary()), api)
        private val snapshots = PackageSnapshotResolver(vocabulary, database.queueStorage())
        private val worker = QueueWorker(database.queueStorage(), QueueHttpTransport(api), vocabulary, snapshots, clock)
        val packages = database.packageRepository()
        private val courses = database.courseRepository()
        private val queue = database.queueService()
        private val publishing = MedKitPublishing(database.medKitRepository(), MedKitPublication(api), snapshots, transactions, clock)
        private val confirmation = IntakeConfirmation(
            database.intakeRepository(), courses, packages, transactions, queue, CourseClosing(courses, packages, queue), clock
        )

        fun scenarios() = Scenarios(database, clock.instant())

        suspend fun localShelf(name: String): Uuid {
            val id = Uuid.random()
            database.medKits().upsert(medKit(id = id, name = name).toMedKitStorageEntity())
            shelves += id
            return id
        }

        suspend fun addBox(shelf: Uuid, amount: String): Uuid {
            val id = Uuid.random()
            packages.add(pack(id = id, medKit = medKit(id = shelf).ref, quantity = pills(amount)))
            return id
        }

        suspend fun publish(shelf: Uuid) = assertEquals(MedKitPublishing.Outcome.Published, publishing.publish(shelf))

        /** Вступление и то, что приложение пока не умеет само: положить полку с коробками к себе. */
        suspend fun join(invitation: String) {
            val joined = success(api.joinMedKit(MembershipPostNetworkDTO(invitation)))
            database.medKits().upsert(
                medKit(id = joined.id, name = "Общая", publication = MedKit.Publication.PUBLISHED, participantCount = joined.participantCount)
                    .toMedKitStorageEntity()
            )
            val at = clock.instant()
            for (dto in joined.packages) {
                val resolved = snapshots.resolve(dto, at) as PackageSnapshotResolver.Resolution.Resolved
                packages.applySnapshot(resolved.snapshot, at)
            }
        }

        /** Лечение из коробки: пять доз по две штуки выделено, три плановых приёма по дням. */
        suspend fun treatFrom(box: Uuid): List<Uuid> {
            val ref = requireNotNull(packages.find(box)).ref
            val plan = activeCourse(unit = unit, sources = listOf(source(ref, 5)))
            courses.activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
            val intakes = (0L until 3L).map { day ->
                plannedIntake(
                    id = Uuid.random(),
                    plannedPackage = ref,
                    plannedAmount = twoPills(),
                    scheduledOn = FIRST_SCHEDULED_ON.plusDays(day),
                    plannedAt = FIRST_PLANNED_AT.plus(Duration.ofDays(day))
                )
            }
            for (intake in intakes) database.intakes().upsert(intake.toIntakeStorageEntity())
            return intakes.map { it.id }
        }

        suspend fun confirm(intake: Uuid, box: Uuid) {
            confirmation.confirm(intake, box, twoPills(), clock.instant()).getOrThrow()
        }

        /** Проход очереди при связи; сбой шага — провал пробы, а не тихий повтор. */
        suspend fun drain() {
            val report = worker.drain()
            assertEquals("сбои прохода: ${report.failed}", emptyList<QueueWorker.Report.Failure>(), report.failed)
            assertEquals("нечитаемые строки: ${report.skipped}", 0, report.skipped.size)
        }

        suspend fun statuses(): List<SyncOperationStatus> = operations().map { it.second }

        suspend fun operations(): List<Pair<SyncCommand, SyncOperationStatus>> {
            val words = vocabulary.snapshot()
            return database.syncOperations().all()
                .map { (it.toDomain(words) as StoredSyncOperation.Readable).operation }
                .map { it.command to it.status }
        }

        suspend fun accountingOf(intake: Uuid): IntakeAccounting =
            requireNotNull(database.intakes().findEntity(intake)).accounting
    }
}
