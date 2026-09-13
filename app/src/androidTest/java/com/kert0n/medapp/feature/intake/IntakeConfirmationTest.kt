package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseCalendar
import com.kert0n.medapp.feature.course.CourseClosing
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.FIRST_PLANNED_AT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.OTHER_INTAKE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.closing
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.transactions
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.course.CourseRoomRepository
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeRoomRepository
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.pack.PackageRoomRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.storage.pack.PackageAdjustment
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Подтверждение приёма — одно действие без сети: факт, остаток, прогресс, обеспечение, конец
 * эпизода и команды ложатся вместе, а связь только пробуется (PLAN D5, D6, F5).
 */
class IntakeConfirmationTest {

    private lateinit var database: MedAppDatabase
    private lateinit var courses: CourseRoomRepository
    private lateinit var intakes: IntakeRoomRepository
    private lateinit var packages: PackageRoomRepository
    private lateinit var confirmation: IntakeConfirmation

    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val third: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000063")

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        courses = database.courseRepository()
        intakes = database.intakeRepository()
        packages = database.packageRepository()
        val transactions = database.transactions()
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val service = QueueService(transactions, database.queueStorage())
        confirmation = IntakeConfirmation(intakes, courses, packages, transactions, service, CourseClosing(courses, packages, service), CourseCalendar(intakes, packages), clock)
        packages.add(pack(quantity = tablets("20")))
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /**
     * Полка общая, и коробка на ней серверу известна: версию ей дал ответ на её создание. Без
     * версии коробка на общей полке — та, о которой сервер ещё не слышал, и расход из неё местный
     * (PLAN E6).
     */
    private suspend fun publishHomeKit() {
        database.medKits().upsert(medKit(publication = MedKit.Publication.PUBLISHED).toMedKitStorageEntity())
        database.packages().setVersion(PACK, 4)
    }

    /** Курс на [totalDoses] доз по две таблетки из PACK; выделено столько же, но не больше пяти. */
    private suspend fun activate(totalDoses: Int = 7, planned: List<CourseIntake> = listOf(plannedIntake())) {
        val plan = activeCourse(totalDoses = totalDoses, sources = listOf(source(PACK, minOf(5, totalDoses))))
        courses.activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)), planned)
    }

    private fun planned(id: Uuid, slot: ScheduledOccurrence) =
        plannedIntake(id = id, plannedAt = slot.at, scheduledOn = slot.localDate, scheduledTime = slot.localTime)

    private suspend fun miss(intake: CourseIntake) =
        assertTrue(intakes.record(IntakeOutcome(intake.miss(LATER), expected = setOf(IntakeStatus.PLANNED), recordedAt = LATER)))

    private suspend fun commands(): List<SyncCommand> = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    @Test
    fun ownKitSpendsLocallyAndReallocatesThePackage() = runTest {
        activate()

        val confirmed = confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        assertEquals(IntakeAccounting.LOCAL_APPLIED, confirmed.accounting)
        assertEquals(IntakeStatus.TAKEN, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(tablets("18"), requireNotNull(packages.find(PACK)).quantity)
        // Выделено было пять доз (10 таблеток), ушло две таблетки: осталось четыре дозы.
        assertEquals(Doses(4), requireNotNull(courses.findPlan(COURSE)).sources.single().allocatedDoses)
        assertEquals(0, database.syncOperations().all().size)
    }

    /**
     * Приём опустошил местную коробку: коробки больше нет, а кончившаяся коробка источником не
     * бывает — курс теряет её тем же решением, что записало приём (PLAN D3, D5). Факт при этом
     * читается: он держится за запись о коробке.
     */
    @Test
    fun anIntakeThatEmptiesTheLocalPackageEndsItAndDetachesTheSource() = runTest {
        activate()
        // Пачка на две таблетки: одна доза — и она кончилась.
        packages.adjust(PackageAdjustment.Recount(PACK, tablets("2")), at = FIRST_PLANNED_AT)

        val confirmed = confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        assertEquals(IntakeAccounting.LOCAL_APPLIED, confirmed.accounting)
        assertNull(packages.find(PACK))
        val plan = requireNotNull(courses.findPlan(COURSE))
        assertEquals(emptyList<Any>(), plan.sources)
        assertEquals(Revision(2), plan.revision)
        assertEquals(PACK, requireNotNull(intakes.find(INTAKE)).taken?.pkg?.id)
        assertEquals(0, database.syncOperations().all().size)
    }

    /**
     * Ответ бывает задним числом — «выпил вчера вечером», — а редакция курса назад не ходит: её
     * двигает момент записи, а не момент ответа. Иначе приём о прошлом делал бы свежую правку курса
     * старее самой себя (PLAN D5, F5). Сам факт и его место в истории при этом остаются в прошлом:
     * они о том, что случилось, а не о том, когда мы это узнали.
     *
     * Красная проверка: конец коробки, записанный моментом ответа, ставит курсу вчерашний
     * `updated_at`.
     */
    @Test
    fun anAnswerAboutThePastDoesNotMoveTheCourseBackwards() = runTest {
        activate()
        packages.adjust(PackageAdjustment.Recount(PACK, tablets("2")), at = now)

        confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        val plan = requireNotNull(courses.findPlan(COURSE))
        assertEquals(now, plan.updatedAt)
        assertEquals(FIRST_PLANNED_AT, requireNotNull(intakes.find(INTAKE)).taken?.at)
    }

    @Test
    fun sharedKitQueuesTheConsumptionWithTheNewClaim() = runTest {
        publishHomeKit()
        activate()

        val confirmed = confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        assertEquals(IntakeAccounting.PENDING, confirmed.accounting)
        // Локально лежит подтверждённое сервером; незакрытый расход сворачивает очередь.
        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
        val consume = commands().single() as PackageSyncCommand.Consume
        assertEquals(INTAKE, consume.intakeId)
        assertEquals(0, BigDecimal("8").compareTo(requireNotNull(consume.claimAfter).amount))
    }

    /**
     * Выделение считается от того же числа, которое видит человек: незакрытый расход уже вычтен из
     * него, и обещать лечению таблетки, которых в коробке нет, нечем (PLAN D4, решение владельца
     * 2026-09-13).
     *
     * Красная проверка: пока считали от подтверждённого числа, курс после этого приёма оставался с
     * четырьмя дозами при трёх таблетках на экране, и нехватка вскрывалась только ответом сервера.
     */
    @Test
    fun theAllocationAfterTheIntakeFollowsWhatThePersonSees() = runTest {
        publishHomeKit()
        activate(totalDoses = 7)
        // Уже уехавший расход на 15 таблеток: по свёртке очереди в пачке пять.
        database.syncOperations().enqueue(third, PackageSyncCommand.Consume(PACK, dose("15"), third), now)

        confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        // Человек видел пять, принял две — осталось три, и это одна доза по две таблетки.
        assertEquals(Doses(1), requireNotNull(courses.findPlan(COURSE)).sources.single().allocatedDoses)
    }

    @Test
    fun theLastDoseClosesTheEpisodeAndReleasesTheClaim() = runTest {
        publishHomeKit()
        activate(totalDoses = 1)

        val confirmed = confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        assertTrue(confirmed.episodeClosed)
        assertEquals(CourseRecord.Outcome.COMPLETED, requireNotNull(courses.findRecord(COURSE)).outcome)
        assertNull(courses.findPlan(COURSE))
        assertNull(courses.courseHolding(PACK))
        val (consume, release) = commands()
        assertTrue(requireNotNull((consume as PackageSyncCommand.Consume).claimAfter).isZero)
        assertEquals(PackageSyncCommand.ReleaseClaim(PACK), release)
    }

    /**
     * Пропуск утром растянул курс на день, и третий пункт материализовался; поздний ответ по
     * пропущенному сдвигает конец назад, и лишний плановый пункт убирается — он не факт.
     */
    @Test
    fun aLateAnswerRemovesTheSurplusPlannedOccurrence() = runTest {
        val slots = schedule().next(schedule().beginning, 3)
        val first = planned(INTAKE, slots[0])
        activate(totalDoses = 2, planned = listOf(first, planned(OTHER_INTAKE, slots[1]), planned(third, slots[2])))
        miss(first)
        // Отвечают назавтра после пропуска: день второго пункта ещё идёт, и пропуском он не стал.
        val service = QueueService(database.transactions(), database.queueStorage())
        val nextMorning = IntakeConfirmation(
            intakes, courses, packages, database.transactions(), service, CourseClosing(courses, packages, service),
            CourseCalendar(intakes, packages), Clock.fixed(slots[1].at, ZoneOffset.UTC)
        )

        nextMorning.confirm(INTAKE, PACK, dose("2"), slots[0].at).getOrThrow()

        assertEquals(IntakeStatus.TAKEN, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(IntakeStatus.PLANNED, requireNotNull(intakes.find(OTHER_INTAKE)).status)
        assertNull(intakes.find(third))
    }

    /** Закрытый план ответов не принимает: пропущенный остаётся пропущенным, списания нет. */
    @Test
    fun aClosedEpisodeRefusesTheAnswer() = runTest {
        activate()
        miss(plannedIntake())
        courses.close(closing(requireNotNull(courses.findRecord(COURSE)).close(CourseRecord.Outcome.CANCELLED, LATER)))

        val refused = confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).exceptionOrNull()

        assertEquals(IntakeRejected.Reason.EPISODE_CLOSED, (refused as IntakeRejected).reason)
        assertEquals(IntakeStatus.MISSED, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
    }

    /** Когда приняли, называет человек: вчерашний факт по открытому эпизоду записывается как есть. */
    @Test
    fun yesterdaysIntakeIsAcceptedWhileTheEpisodeIsOpen() = runTest {
        activate()
        val yesterday = now.minusSeconds(24 * 60 * 60)

        confirmation.confirm(INTAKE, PACK, dose("2"), yesterday).getOrThrow()

        assertEquals(yesterday, requireNotNull(intakes.find(INTAKE)?.taken).at)
    }

    /**
     * Пачку теперь считают в миллилитрах, а курс — в таблетках: акт по пачке проходит, но пункт
     * измерен другой единицей, и приём отвергается целиком, а не роняет сценарий.
     */
    @Test
    fun aPackageCountedInAnotherUnitIsRefusedAndWritesNothing() = runTest {
        activate()
        packages.add(pack(id = OTHER_PACK, quantity = millilitres("100")))

        val refused = confirmation.confirm(INTAKE, OTHER_PACK, dose(millilitres("2")), FIRST_PLANNED_AT).exceptionOrNull()

        assertEquals(IntakeRejected.Reason.UNIT_MISMATCH, (refused as IntakeRejected).reason)
        assertEquals(IntakeStatus.PLANNED, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(millilitres("100"), requireNotNull(packages.find(OTHER_PACK)).quantity)
    }

    /**
     * Доза и пункт в таблетках, а пачку теперь считают в миллилитрах, и их меньше дозы: числа
     * разных единиц не сравниваются — отказ по единице, а не по нехватке.
     *
     * Красная проверка: сравнить числа до акта по пачке — `INSUFFICIENT`.
     */
    @Test
    fun aSmallPackageInAnotherUnitIsRefusedByUnitNotByShortage() = runTest {
        activate()
        packages.add(pack(id = OTHER_PACK, quantity = millilitres("1")))

        val refused = confirmation.confirm(INTAKE, OTHER_PACK, dose("2"), FIRST_PLANNED_AT).exceptionOrNull()

        assertEquals(IntakeRejected.Reason.UNIT_MISMATCH, (refused as IntakeRejected).reason)
        assertEquals(IntakeStatus.PLANNED, requireNotNull(intakes.find(INTAKE)).status)
    }

    /**
     * Пачка вне источников курса той же единицей: пункт курса принимают из пачки курса, а такой
     * приём — внеплановый факт, и пункт им не закрывается (PLAN D5). Отказ до записи: ни остатка,
     * ни статуса, ни команды.
     *
     * Красная проверка: убрать отказ — пункт становится `TAKEN`, а чужая пачка худеет.
     */
    @Test
    fun aPackageOutsideTheCourseSourcesIsRefusedAndWritesNothing() = runTest {
        activate()
        packages.add(pack(id = OTHER_PACK, quantity = tablets("30")))

        val refused = confirmation.confirm(INTAKE, OTHER_PACK, dose("2"), FIRST_PLANNED_AT).exceptionOrNull()

        assertEquals(IntakeRejected.Reason.PACKAGE_NOT_A_SOURCE, (refused as IntakeRejected).reason)
        assertEquals(IntakeStatus.PLANNED, requireNotNull(intakes.find(INTAKE)).status)
        assertEquals(tablets("30"), requireNotNull(packages.find(OTHER_PACK)).quantity)
        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
        assertEquals(0, database.syncOperations().all().size)
    }

    /** Другая пачка **из источников** курса разрешена: расход и выделение идут по ней (PLAN D5). */
    @Test
    fun anotherPackageOfTheCourseSourcesIsAccepted() = runTest {
        packages.add(pack(id = OTHER_PACK, quantity = tablets("30")))
        val plan = activeCourse(totalDoses = 7, sources = listOf(source(PACK, 5), source(OTHER_PACK, 2)))
        courses.activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)), listOf(plannedIntake()))

        val confirmed = confirmation.confirm(INTAKE, OTHER_PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        assertEquals(IntakeStatus.TAKEN, confirmed.intake.status)
        assertEquals(tablets("28"), requireNotNull(packages.find(OTHER_PACK)).quantity)
        assertEquals(tablets("20"), requireNotNull(packages.find(PACK)).quantity)
    }

    /** Двойное нажатие: второй раз отвечает записанным и второй раз не списывает (PLAN D6). */
    @Test
    fun repeatingTheConfirmationSpendsOnce() = runTest {
        activate()
        confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        val repeated = confirmation.confirm(INTAKE, PACK, dose("2"), FIRST_PLANNED_AT).getOrThrow()

        assertFalse(repeated.episodeClosed)
        assertEquals(IntakeAccounting.LOCAL_APPLIED, repeated.accounting)
        assertEquals(tablets("18"), requireNotNull(packages.find(PACK)).quantity)
    }
}
