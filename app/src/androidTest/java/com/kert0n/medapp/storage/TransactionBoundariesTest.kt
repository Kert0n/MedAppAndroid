package com.kert0n.medapp.storage

import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.intake.UnplannedIntake
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.closing
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.prescription
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.transactions
import com.kert0n.medapp.fixture.unplannedIntake
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.storage.course.ActivePackageAssignmentStorageEntity
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.CourseRoomRepository
import com.kert0n.medapp.storage.course.CourseStorageEntity
import com.kert0n.medapp.storage.course.toStorageEntity as toCourseStorageEntity
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeRoomRepository
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.pack.PackageAdjustment
import com.kert0n.medapp.storage.pack.PackageRoomRepository
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Связанные изменения сохраняются атомарно: откат не оставляет ни отдельного расхода, ни
 * факта, ни брони, а план и запись эпизода не бывают в базе поодиночке (PLAN F5).
 */
class TransactionBoundariesTest {

    private lateinit var database: MedAppDatabase
    private lateinit var packages: PackageRoomRepository
    private lateinit var courses: CourseRoomRepository
    private lateinit var intakes: IntakeRoomRepository
    private lateinit var queue: QueueService

    private val operation: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000091")
    private val at: Instant = Instant.parse("2026-09-10T12:00:00Z")

    private val paracetamol = pack(quantity = tablets("20"))

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        packages = database.packageRepository()
        courses = database.courseRepository()
        intakes = database.intakeRepository()
        queue = QueueService(database.transactions(), database.queueStorage())
        database.medKits().upsert(medKit().toMedKitStorageEntity())
        database.medKits().upsert(medKit(id = SHARED_KIT, name = "Дача").toMedKitStorageEntity())
        packages.add(paracetamol)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /** Опубликованная аптечка: команды ей ставятся; местной — нет. */
    private val published = medKit(publication = MedKit.Publication.PUBLISHED)

    private fun draft(): CourseDraft.Activation {
        val plan = activeCourse(sources = listOf(source(PACK, 5)))
        return CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription))
    }

    @Test
    fun activationWritesPlanAndRecordTogether() = runTest {
        val activation = draft()
        courses.activate(activation, planned = listOf(plannedIntake()))

        assertNotNull(courses.findPlan(COURSE))
        assertNotNull(courses.findRecord(COURSE))
        assertEquals(COURSE, courses.courseHolding(PACK))
        assertEquals(IntakeStatus.PLANNED, requireNotNull(intakes.find(INTAKE)).status)
    }

    /**
     * Занятая пачка отвергается назначением, и тогда не остаётся ни плана, ни записи: их
     * поодиночке в базе не бывает (PLAN F5).
     */
    @Test
    fun activationOnAnOccupiedPackageLeavesNothingBehind() = runTest {
        val other: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000052")
        database.courses().upsertCourse(activeCourse(id = other).toCourseStorageEntity())
        database.courses().assignPackage(ActivePackageAssignmentStorageEntity(PACK, other))

        val failure = runCatching { courses.activate(draft()) }.exceptionOrNull()

        assertNotNull(failure)
        assertNull(courses.findPlan(COURSE))
        assertNull(courses.findRecord(COURSE))
        assertEquals(other, courses.courseHolding(PACK))
    }

    /**
     * Источники и назначения — два представления одного отношения, и пишутся вместе:
     * привязанная пачка занята курсом, отвязанная свободна (PLAN F1, F2).
     */
    @Test
    fun changingTheSourcesReassignsThePackagesInTheSameTransaction() = runTest {
        val activation = draft()
        courses.activate(activation, planned = listOf(plannedIntake()))
        val ibuprofen = pack(id = OTHER_PACK, quantity = tablets("10"), form = TABLET_FORM)
        packages.add(ibuprofen)

        val extended = activation.course.attach(ibuprofen, 3.doses, LATER).getOrThrow()
        assertTrue(courses.updateSources(extended, expected = activation.course.revision))
        assertEquals(COURSE, courses.courseHolding(PACK))
        assertEquals(COURSE, courses.courseHolding(OTHER_PACK))

        val shrunk = extended.detach(paracetamol.ref, LATER)
        assertTrue(courses.updateSources(shrunk, expected = extended.revision))
        assertNull(courses.courseHolding(PACK))
        assertEquals(COURSE, courses.courseHolding(OTHER_PACK))
        assertEquals(listOf(OTHER_PACK), requireNotNull(courses.findPlan(COURSE)).sources.map { it.pkg.id })
    }

    /**
     * Курс не воскрешает удалённое: состав, прочитанный до того, как коробку выбросили, называет
     * коробку, которой нет, — и «писать некуда» вместо исключения ключа (PLAN D3, F5). Так же
     * отвечает состав из прежней редакции — курс её уже потерял и редакцию поднял.
     */
    @Test
    fun sourcesReadBeforeThePackageWasThrownOutAreNotWrittenBack() = runTest {
        val activation = draft()
        courses.activate(activation, planned = listOf(plannedIntake()))
        val ibuprofen = pack(id = OTHER_PACK, quantity = tablets("10"), form = TABLET_FORM)
        packages.add(ibuprofen)
        val extended = activation.course.attach(ibuprofen, 3.doses, LATER).getOrThrow()

        assertTrue(packages.end(ibuprofen.ended(), LATER))
        assertFalse(courses.updateSources(extended, expected = activation.course.revision))
        assertEquals(listOf(PACK), requireNotNull(courses.findPlan(COURSE)).sources.map { it.pkg.id })

        // Коробку выбросили, и курс потерял её доменным переходом: редакция ушла вперёд.
        val current = requireNotNull(courses.findPlan(COURSE))
        val stale = current.detach(paracetamol.ref, LATER)
        assertFalse(courses.updateSources(stale, expected = Revision(current.revision.number - 1)))
        assertEquals(current.revision, requireNotNull(courses.findPlan(COURSE)).revision)
    }

    /**
     * Черновик теряет коробку так же, как начатое лечение, — доменным переходом. Пачку он не
     * занимает, назначения у него нет, и по назначениям его не найти; каскад вырезал бы источник
     * молча, и человек, вернувшись к недоделанному курсу, не нашёл бы пачки и не узнал бы, куда
     * она делась (PLAN D5, F2).
     */
    @Test
    fun aDraftLosesTheBoxItHeldWhenTheBoxEnds() = runTest {
        val ibuprofen = pack(id = OTHER_PACK, quantity = tablets("10"), form = TABLET_FORM)
        packages.add(ibuprofen)
        assertTrue(courses.saveDraft(course(dose = dose("2"), form = TABLET_FORM, sources = listOf(source(ibuprofen, 3))), expected = null))
        assertEquals(listOf(OTHER_PACK), database.courses().sourcePackagesOf(COURSE))

        assertTrue(packages.end(ibuprofen.ended(), LATER))

        assertEquals(emptyList<Uuid>(), database.courses().sourcePackagesOf(COURSE))
        assertNotNull(courses.findDraft(COURSE))
    }

    /** Пересчёт обеспечения состав не меняет: иначе назначения пачек разошлись бы с источниками. */
    @Test
    fun reallocationWithAnotherCompositionIsRefused() = runTest {
        val activation = draft()
        courses.activate(activation, planned = listOf(plannedIntake()))
        val ibuprofen = pack(id = OTHER_PACK, quantity = tablets("10"), form = TABLET_FORM)
        packages.add(ibuprofen)
        val extended = activation.course.attach(ibuprofen, 3.doses, LATER).getOrThrow()

        val failure = runCatching {
            courses.reallocate(CourseReallocation(extended, activation.course.revision))
        }.exceptionOrNull()

        assertEquals(IllegalStateException::class, failure!!::class)
        assertNull(courses.courseHolding(OTHER_PACK))
    }

    @Test
    fun closingRemovesThePlanAndKeepsTheRecord() = runTest {
        val activation = draft()
        courses.activate(activation, planned = listOf(plannedIntake()))

        val closed = activation.record.close(CourseRecord.Outcome.CANCELLED, LATER)
        courses.close(closing(record = closed, cancelled = listOf(plannedIntake().cancel(LATER))))

        assertNull(courses.findPlan(COURSE))
        val record = requireNotNull(courses.findRecord(COURSE))
        assertFalse(record.isOpen)
        assertEquals(CourseRecord.Outcome.CANCELLED, record.outcome)
        assertNull(courses.courseHolding(PACK))
        assertEquals(IntakeStatus.CANCELLED, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(activation.record.prescription, record.prescription)
    }

    @Test
    fun confirmingAnIntakeWritesFactStockAndMovementTogether() = runTest {
        courses.activate(draft(), planned = listOf(plannedIntake()))

        val applied = intakes.record(
            IntakeOutcome(
                intake = plannedIntake().confirm(paracetamol.take(dose("2"), LATER).getOrThrow()),
                expected = setOf(IntakeStatus.PLANNED, IntakeStatus.MISSED),
                sync = IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED),
                recordedAt = LATER
            )
        )

        assertTrue(applied)
        assertEquals(IntakeStatus.TAKEN, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(tablets("18"), requireNotNull(packages.find(PACK)).quantity)
        assertEquals(
            IntakeAccounting.LOCAL_APPLIED,
            requireNotNull(intakes.syncStateOf(INTAKE)).accounting
        )
    }

    /** Повтор уже совершённого подтверждения ничего не списывает второй раз (PLAN D6). */
    @Test
    fun repeatingAConfirmationChangesNothing() = runTest {
        courses.activate(draft(), planned = listOf(plannedIntake()))
        val outcome = {
            IntakeOutcome(
                intake = plannedIntake().confirm(paracetamol.take(dose("2"), LATER).getOrThrow()),
                expected = setOf(IntakeStatus.PLANNED),
                sync = IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED),
                recordedAt = LATER
            )
        }
        assertTrue(intakes.record(outcome()))

        assertFalse(intakes.record(outcome()))
        assertEquals(tablets("18"), requireNotNull(packages.find(PACK)).quantity)
    }

    /**
     * Откат не оставляет отдельного расхода, факта или брони: команда очереди, которую база
     * отвергла, роняет транзакцию службы вместе с записью приёма (PLAN F5).
     */
    @Test
    fun rollbackLeavesNeitherFactNorStockNorQueuedCommand() = runTest {
        courses.activate(draft(), planned = listOf(plannedIntake()))
        val clash = QueuedCommand(operation, PackageSyncCommand.Consume(PACK, dose("2"), INTAKE))
        database.syncOperations().enqueue(operation, PackageSyncCommand.Delete(PACK), at)

        val failure = runCatching {
            queue.change(published.ref, listOf(clash), at) {
                intakes.record(
                    IntakeOutcome(
                        intake = plannedIntake().confirm(paracetamol.take(dose("2"), LATER).getOrThrow()),
                        expected = setOf(IntakeStatus.PLANNED),
                        sync = IntakeSyncState(INTAKE, IntakeAccounting.PENDING, operationId = operation),
                        recordedAt = LATER
                    )
                )
            }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(IntakeStatus.PLANNED, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
        assertEquals(1, database.syncOperations().all().size)
    }

    /** Внеплановому приёму строки заранее нет: он заводится вставкой вместе с расходом (F5). */
    @Test
    fun unplannedIntakeIsWrittenTogetherWithTheStock() = runTest {
        assertTrue(intakes.record(unplannedOutcome()))

        assertTrue(intakes.find(INTAKE) is UnplannedIntake)
        assertEquals(tablets("18"), requireNotNull(packages.find(PACK)).quantity)
    }

    /** Повтор внепланового приёма узнаётся по тождеству и второй раз не списывает (D6). */
    @Test
    fun repeatingAnUnplannedIntakeChangesNothing() = runTest {
        assertTrue(intakes.record(unplannedOutcome()))

        assertFalse(intakes.record(unplannedOutcome()))
        assertEquals(tablets("18"), requireNotNull(packages.find(PACK)).quantity)
    }

    /**
     * Приём, подтверждённый между чтением и концом лечения, отменой не затирается: списание уже
     * случилось, и история должна это помнить (PLAN D6, F2).
     */
    @Test
    fun closingDoesNotCancelAnIntakeAnsweredInTheMeantime() = runTest {
        val activation = draft()
        courses.activate(activation, planned = listOf(plannedIntake()))
        assertTrue(intakes.record(confirmedOutcome()))

        courses.close(closing(record = activation.record.close(CourseRecord.Outcome.CANCELLED, LATER), cancelled = listOf(plannedIntake().cancel(LATER))))

        assertEquals(IntakeStatus.TAKEN, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(
            IntakeAccounting.LOCAL_APPLIED,
            requireNotNull(intakes.syncStateOf(INTAKE)).accounting
        )
    }

    /** Расход не трогает обвязку доставки: версия предусловия у пачки остаётся прежней (E3). */
    @Test
    fun spendingKeepsThePackagePreconditions() = runTest {
        val sync = PackageSyncState(
            PACK,
            version = ResourceVersion(5),
            claimsVersion = ResourceVersion(2),
            syncedAt = at
        )
        packages.add(paracetamol, sync)
        courses.activate(draft(), planned = listOf(plannedIntake()))

        assertTrue(intakes.record(confirmedOutcome()))

        assertEquals(tablets("18"), requireNotNull(packages.find(PACK)).quantity)
        assertEquals(sync, requireNotNull(database.packages().find(PACK)).pack.syncState())
    }

    /**
     * Расход, уехавший командой, локального остатка не трогает: там лежит подтверждённое
     * сервером, а незакрытую команду сворачивает очередь (PLAN E1).
     */
    @Test
    fun spendingThatLeavesByCommandDoesNotTouchTheLocalAmount() = runTest {
        courses.activate(draft(), planned = listOf(plannedIntake()))
        val consume = QueuedCommand(operation, PackageSyncCommand.Consume(PACK, dose("2"), INTAKE))

        assertTrue(
            queue.change(published.ref, listOf(consume), at) {
                intakes.record(
                    IntakeOutcome(
                        intake = plannedIntake().confirm(paracetamol.take(dose("2"), LATER).getOrThrow()),
                        expected = setOf(IntakeStatus.PLANNED),
                        sync = IntakeSyncState(INTAKE, IntakeAccounting.PENDING, operationId = operation),
                        recordedAt = LATER
                    )
                )
            }
        )

        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
        assertEquals(1, database.syncOperations().all().size)
    }

    /** Местной аптечке команд не ставится: на сервере её нет, и везти туда нечего (PLAN E1). */
    @Test
    fun localKitGetsNoCommandsEvenWhenTheChangeGoesThrough() = runTest {
        val local = medKit(publication = MedKit.Publication.LOCAL)
        val recount = QueuedCommand(operation, PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("17")))

        assertTrue(
            queue.change(local.ref, listOf(recount), at) {
                packages.adjust(PackageAdjustment.Recount(PACK, tablets("17")), at = LATER)
            }
        )

        assertEquals(tablets("17"), requireNotNull(packages.find(PACK)).quantity)
        assertEquals(0, database.syncOperations().all().size)
    }

    /** Изменение, которому некуда лечь, команды не порождает: серверу не везут то, чего не записали. */
    @Test
    fun aChangeWithNowhereToLandQueuesNothing() = runTest {
        val gone = Uuid.parse("00000000-0000-4000-8000-0000000000ee")
        val recount = QueuedCommand(operation, PackageSyncCommand.CorrectStock(gone, tablets("20"), tablets("17")))

        assertFalse(
            queue.change(published.ref, listOf(recount), at) {
                packages.adjust(PackageAdjustment.Recount(gone, tablets("17")), at = LATER)
            }
        )
        assertEquals(0, database.syncOperations().all().size)
    }

    /** Пачки, из которой принято, уже нет — тогда и факт не записывается: половины расхода не бывает. */
    @Test
    fun anIntakeFromAPackageThatIsGoneIsNotRecorded() = runTest {
        val outcome = IntakeOutcome(
            intake = unplannedIntake(taken = pack(id = OTHER_PACK), takenAmount = dose("2")),
            expected = emptySet(),
            sync = IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED),
            recordedAt = LATER
        )

        assertFalse(intakes.record(outcome))
        assertNull(intakes.find(INTAKE))
    }

    /** Закрытый план не возвращается пересчётом, прочитавшим курс до закрытия (PLAN D5, F5). */
    @Test
    fun adjustmentDoesNotResurrectAClosedPlan() = runTest {
        val activation = draft()
        courses.activate(activation)
        courses.close(closing(activation.record.close(CourseRecord.Outcome.CANCELLED, LATER)))

        assertTrue(
            packages.adjust(
                PackageAdjustment.Recount(PACK, tablets("17")),
                reallocation = CourseReallocation(activation.course, activation.course.revision),
                at = LATER
            )
        )

        assertNull(database.courses().findPlan(COURSE))
        assertEquals(tablets("17"), requireNotNull(packages.find(PACK)).quantity)
    }

    /**
     * Активация уничтожает черновик, а не прячет его: экран, оставшийся открытым, не возвращает
     * начатое лечение в состояние черновика.
     */
    @Test
    fun aDraftIsNotWrittenOverAStartedTreatment() = runTest {
        val activation = draft()
        val stale = course(title = "Старый черновик")
        courses.activate(activation)

        assertFalse(courses.saveDraft(stale, expected = stale.revision))
        assertNull(courses.findDraft(COURSE))
        assertNotNull(courses.findPlan(COURSE))
    }

    /**
     * Конец лечения уничтожает план, но запись эпизода остаётся навсегда: «плана нет» само по
     * себе не значит «черновик ещё можно сохранить».
     */
    @Test
    fun aDraftDoesNotResurrectAFinishedEpisode() = runTest {
        val activation = draft()
        courses.activate(activation)
        courses.close(closing(activation.record.close(CourseRecord.Outcome.COMPLETED, LATER)))

        assertFalse(courses.saveDraft(course(title = "Старый черновик"), expected = null))
        assertNull(courses.findDraft(COURSE))
        assertEquals(
            CourseRecord.Outcome.COMPLETED,
            requireNotNull(courses.findRecord(COURSE)).outcome
        )
    }

    /**
     * Пересчёт выделений из устаревшего состава не проходит молча: вокруг него в той же
     * транзакции уже записан расход, обеспечение которого он и считал.
     */
    @Test
    fun aStaleReallocationAbortsTheWholeTransaction() = runTest {
        val activation = draft()
        courses.activate(activation, planned = listOf(plannedIntake()))
        val stale = CourseReallocation(activation.course, activation.course.revision)
        assertTrue(
            database.courses().updateAllocations(
                activation.course.toCourseStorageEntity().let {
                    CourseStorageEntity(
                        id = it.id, doseAmount = it.doseAmount, unitId = it.unitId, formId = it.formId,
                        totalDoses = it.totalDoses, start = it.start, daysOfWeek = it.daysOfWeek,
                        zone = it.zone, revision = it.revision + 1, createdAt = it.createdAt,
                        updatedAt = LATER
                    )
                },
                emptyList(),
                activation.course.revision
            )
        )

        val failure = runCatching {
            packages.adjust(
                PackageAdjustment.Recount(PACK, tablets("17")),
                reallocation = stale,
                at = LATER
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
    }

    /** В минус пачка не уходит: выбросили больше, чем было, — коробка кончилась. */
    @Test
    fun disposingMoreThanThereIsEndsThePack() = runTest {
        assertTrue(packages.adjust(PackageAdjustment.Disposal(PACK, tablets("50")), at = LATER))

        assertNull(packages.find(PACK))
    }

    /**
     * Правится название эпизода и только оно: экран, загрузивший открытую запись, не возвращает
     * законченное лечение в открытое состояние (PLAN D5).
     */
    @Test
    fun renamingDoesNotReopenAClosedRecord() = runTest {
        val activation = draft()
        courses.activate(activation)
        courses.close(closing(activation.record.close(CourseRecord.Outcome.COMPLETED, LATER)))

        assertTrue(courses.rename(COURSE, "Другое название", note = null))

        val record = requireNotNull(courses.findRecord(COURSE))
        assertEquals("Другое название", record.title)
        assertEquals(CourseRecord.Outcome.COMPLETED, record.outcome)
        assertEquals(activation.record.prescription, record.prescription)
    }

    private fun confirmedOutcome() = IntakeOutcome(
        intake = plannedIntake().confirm(paracetamol.take(dose("2"), LATER).getOrThrow()),
        expected = setOf(IntakeStatus.PLANNED),
        sync = IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED),
        recordedAt = LATER
    )

    private fun unplannedOutcome() = IntakeOutcome(
        intake = unplannedIntake(takenAmount = dose("2")),
        expected = emptySet(),
        sync = IntakeSyncState(INTAKE, IntakeAccounting.LOCAL_APPLIED),
        recordedAt = LATER
    )

    @Test
    fun recountReplacesTheStock() = runTest {
        assertTrue(packages.adjust(PackageAdjustment.Recount(PACK, tablets("17")), at = LATER))

        assertEquals(tablets("17"), requireNotNull(packages.find(PACK)).quantity)
    }

    /**
     * Переход берёт «было» из базы, а не из снимка, с которым пришёл вызывающий: экран мог
     * прочитать пачку до чужой записи, и тогда утилизация списала бы от неверного числа.
     */
    @Test
    fun disposalAppliesToTheStockThatIsActuallyThere() = runTest {
        assertTrue(packages.adjust(PackageAdjustment.Recount(PACK, tablets("18")), at = LATER))

        assertTrue(packages.adjust(PackageAdjustment.Disposal(PACK, tablets("3")), at = LATER))

        assertEquals(tablets("15"), requireNotNull(packages.find(PACK)).quantity)
    }

    /** Кончившаяся коробка строки не оставляет (D3). */
    @Test
    fun disposalToZeroEndsThePack() = runTest {
        assertTrue(packages.adjust(PackageAdjustment.Disposal(PACK, tablets("20")), at = LATER))

        assertNull(packages.find(PACK))
    }

    /** Перенос меняет место и только его: остаток он не трогает. */
    @Test
    fun transferMovesThePackageOnly() = runTest {
        assertTrue(
            packages.adjust(
                PackageAdjustment.Transfer(PACK, medKit(id = SHARED_KIT, name = "Дача").ref),
                at = LATER
            )
        )

        assertEquals(SHARED_KIT, requireNotNull(packages.find(PACK)).medKit.id)
        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
    }

    /** Упавшая команда очереди откатывает остаток: половины пересчёта не бывает. */
    @Test
    fun failedAdjustmentLeavesTheStock() = runTest {
        database.syncOperations().enqueue(operation, PackageSyncCommand.Delete(PACK), at)
        val recount = QueuedCommand(operation, PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("4")))

        val failure = runCatching {
            queue.change(published.ref, listOf(recount), at) {
                packages.adjust(PackageAdjustment.Recount(PACK, tablets("4")), at = LATER)
            }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
    }
}
