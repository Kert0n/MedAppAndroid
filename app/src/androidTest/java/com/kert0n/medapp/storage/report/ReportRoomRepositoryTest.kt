package com.kert0n.medapp.storage.report

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.intake.TakenDose
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.intake.UnplannedIntakeRecording
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.confirmed
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.reportRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.operation.toStorageEntity
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Истраченное читается одним снимком базы: мои состоявшиеся приёмы за календарные дни в зоне
 * спрашивающего, строками эпизодов и коробок (PLAN H6). Действия, которые приёмом не являются,
 * отчёт не меняют.
 */
@RunWith(AndroidJUnit4::class)
class ReportRoomRepositoryTest {

    private val queries = mutableListOf<String>()
    private val database: MedAppDatabase = inMemoryDatabase { sql -> synchronized(queries) { queries += sql } }
    private val reports = database.reportRepository()

    /** 10 марта, 15:00 по Москве. */
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val scenarios = Scenarios(database, now)

    private val tenth = SpendingPeriod(LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, 10))

    @After
    fun tearDown() = database.close()

    private suspend fun spent(period: SpendingPeriod = tenth): Spending = reports.observeSpending(period, MOSCOW).first()

    /** Лечение с 10 марта раз в день, 5 доз по две таблетки из [PACK]; вторая коробка — [OTHER_PACK]. */
    private suspend fun treated(): Uuid {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        database.packageRepository().add(pack(id = OTHER_PACK, name = "Ибупрофен", quantity = tablets("2"), form = TABLET_FORM))
        val created = scenarios.courseDrafting.create("Парацетамол")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(5)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        scenarios.courseUpkeep.keepUp()
        return draft.id
    }

    private suspend fun items(id: Uuid): List<CourseIntake> =
        database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().sortedBy { it.plannedAt }

    @Test
    fun courseAndOneOffIntakesAreRowsOfTheirEpisodeAndBox() = runTest {
        val id = treated()
        scenarios.intakeConfirmation.confirm(items(id).first().id, PACK, dose("2"), now).confirmed()
        val oneOff = scenarios.unplannedIntakeRecording.record(OTHER_PACK, dose("1"), now)
        assertTrue("$oneOff", oneOff is UnplannedIntakeRecording.Outcome.Recorded)

        val spending = spent()

        assertEquals(listOf(id), spending.episodes.map { it.record.id })
        assertEquals(tablets("2"), spending.episodes.single().total)
        assertEquals(listOf(Spending.Box(OTHER_PACK, "Ибупрофен", tablets("1"), 1)), spending.packages)
    }

    /**
     * Отказ, пересчёт, отмена лечения и доза мимо плана — не мои приёмы.
     *
     * Красная проверка: условие «не план» вместо `TAKEN` пустит отказанный и отменённые пункты.
     */
    @Test
    fun onlyTakenIntakesAreSpending() = runTest {
        val id = treated()
        val (first, second) = items(id)
        scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now).confirmed()
        scenarios.intakeDeclining.decline(second.id, now)
        val plan = requireNotNull(database.courseRepository().findPlan(id))
        scenarios.courseOffPlanCounting.set(id, plan.revision, Doses(1))
        scenarios.packageAdjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("18"), actual = tablets("9")))
        scenarios.courseCancellation.cancel(id)

        val spending = spent(SpendingPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 4, 1)))

        assertEquals(1, spending.episodes.single().intakes)
        assertEquals(tablets("2"), spending.episodes.single().total)
        assertTrue(spending.packages.isEmpty())
    }

    /**
     * Дни — московские: полночь 10 марта по Москве — это 21:00 9 марта по UTC. Приём ровно в
     * полночь — десятого, секундой раньше — девятого.
     */
    @Test
    fun theDayOfAnIntakeIsTheLocalDay() = runTest {
        treated()
        val midnight = tenth.startsAt(MOSCOW)
        scenarios.unplannedIntakeRecording.record(OTHER_PACK, dose("1"), midnight)
        scenarios.unplannedIntakeRecording.record(OTHER_PACK, dose("1"), midnight.minusSeconds(1))

        val ninth = SpendingPeriod(LocalDate.of(2027, 3, 9), LocalDate.of(2027, 3, 9))
        assertEquals(1, spent().packages.single().intakes)
        assertEquals(1, spent(ninth).packages.single().intakes)
        assertEquals(2, spent(SpendingPeriod(ninth.from, tenth.to)).packages.single().intakes)
    }

    /** Коробка кончилась приёмом — строка читается по записи о ней. */
    @Test
    fun aBoxThatEndedIsStillARow() = runTest {
        treated()
        scenarios.unplannedIntakeRecording.record(OTHER_PACK, dose("2"), now)

        assertEquals(null, database.packageRepository().find(OTHER_PACK))
        assertEquals(listOf(Spending.Box(OTHER_PACK, "Ибупрофен", tablets("2"), 1)), spent().packages)
    }

    /** Сервер отверг расход — таблетка всё равно выпита (H6). */
    @Test
    fun aConsumptionTheServerRefusedIsStillSpent() = runTest {
        treated()
        val recorded = scenarios.unplannedIntakeRecording.record(OTHER_PACK, dose("1"), now) as UnplannedIntakeRecording.Outcome.Recorded
        val operation = Uuid.random()
        database.intakes().upsert(
            requireNotNull(database.intakeRepository().find(recorded.intake.id))
                .toStorageEntity(IntakeSyncState(recorded.intake.id, IntakeAccounting.PENDING, operation))
        )
        database.syncOperations().setIntakeAccounting(operation, IntakeAccounting.REMOTE_REFUSED)

        assertEquals(tablets("1"), spent().packages.single().total)
    }

    @Test
    fun theStreamFollowsNewIntakesAndRenamedEpisodes() = runTest {
        val id = treated()
        assertTrue(spent().isEmpty)

        scenarios.intakeConfirmation.confirm(items(id).first().id, PACK, dose("2"), now).confirmed()
        assertTrue(database.courseRepository().rename(id, "Парацетамол от простуды", null))

        assertEquals("Парацетамол от простуды", spent().episodes.single().record.title)
    }

    private suspend fun future(until: Int): FutureSpending =
        reports.observeFutureSpending(SpendingHorizon(LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, until))).first()

    /**
     * Сегодня 10 марта, 15:00; утренний пункт не отвечен — он ещё состоится. С 10 по 12 марта —
     * три дозы; принятый сегодня пункт уходит из расчёта, и на те же дни остаётся две.
     */
    @Test
    fun futureSpendingCountsTheDosesStillAheadFromTheStartOfToday() = runTest {
        val id = treated()
        assertEquals(FutureSpending.Episode(requireNotNull(database.courseRepository().findRecord(id)).projection(), Doses(3), tablets("6")),
            future(12).episodes.single())

        scenarios.intakeConfirmation.confirm(items(id).first().id, PACK, dose("2"), now).confirmed()

        assertEquals(Doses(2), future(12).episodes.single().doses)
        // Лечение пять доз: одна принята, до конца месяца осталось четыре, а не двадцать один день.
        assertEquals(tablets("8"), future(31).episodes.single().total)
    }

    /** Нехватка расчёт не режет: курс обещает дозы, а не их обеспечение (H6). */
    @Test
    fun futureSpendingIsNotCutByShortage() = runTest {
        treated()
        scenarios.packageAdjusting.adjust(PACK, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("1")))

        assertEquals(tablets("6"), future(12).episodes.single().total)
    }

    @Test
    fun aCancelledCourseHasNoFutureSpending() = runTest {
        val id = treated()
        scenarios.courseCancellation.cancel(id)

        assertTrue(future(31).isEmpty)
    }

    /**
     * Сводка — живые пачки всех полок, включая общую; коробка, кончившаяся приёмом, и коробка, которую
     * решили выбросить, в неё не входят, а новая приходит потоком.
     */
    @Test
    fun theStockSummaryCountsLivingBoxesOfAllShelves() = runTest {
        val summary = reports.observeStockSummary()
        database.packageRepository().add(pack(id = PACK, category = "обезболивающее", price = com.kert0n.medapp.domain.value.Money(java.math.BigDecimal("150"))))
        database.packageRepository().add(pack(id = OTHER_PACK, medKit = medKit(id = SHARED_KIT).ref, quantity = tablets("1")))
        assertEquals(2, summary.first().packages)
        assertEquals(1, summary.first().unpriced)

        scenarios.unplannedIntakeRecording.record(OTHER_PACK, dose("1"), now)
        assertEquals(1, summary.first().packages)

        assertTrue(database.packageRepository().mark(PACK, PackageStatus.REMOVING, by = Uuid.random()))
        assertEquals(0, summary.first().packages)
    }

    private suspend fun dayPlan(date: LocalDate): DayPlan = reports.observeDayPlan(date, MOSCOW).first()

    /**
     * 10 марта: пункт курса ждёт, разовый приём этого дня — рядом; после ответа пункт показан
     * принятым. Последняя, пятая доза — 14 марта, 15-е пусто.
     */
    @Test
    fun theDayPlanShowsWrittenItemsWithStatusAndOneOffs() = runTest {
        val id = treated()
        scenarios.unplannedIntakeRecording.record(OTHER_PACK, dose("1"), now)

        val before = dayPlan(LocalDate.of(2027, 3, 10))
        assertEquals(2, before.items.size)
        val item = before.items.filterIsInstance<DayPlan.Item.Scheduled>().single()
        assertEquals(IntakeStatus.PLANNED, item.intake.status)

        scenarios.intakeConfirmation.confirm(item.intake.id, PACK, dose("2"), now).confirmed()

        assertEquals(IntakeStatus.TAKEN, dayPlan(LocalDate.of(2027, 3, 10)).items.filterIsInstance<DayPlan.Item.Scheduled>().single().intake.status)
        assertEquals(1, dayPlan(LocalDate.of(2027, 3, 14)).items.size)
        assertTrue(dayPlan(LocalDate.of(2027, 3, 15)).isEmpty)
    }

    /** Лечение на сто доз: календарь записан на 60 дней, а 70-й день отвечается ожидаемым пунктом. */
    @Test
    fun aDayBeyondTheWindowIsAnsweredByTheSchedule() = runTest {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        val created = scenarios.courseDrafting.create("Витамин D")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("1")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(100))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        scenarios.courseUpkeep.keepUp()

        val far = dayPlan(LocalDate.of(2027, 3, 10).plusDays(70)).items.single()

        assertTrue("$far", far is DayPlan.Item.Expected)
        assertEquals("Витамин D", (far as DayPlan.Item.Expected).title)
    }

    /** Пустая база — пустой отчёт и одно чтение приёмов, без чтения записей. */
    @Test
    fun anEmptyBaseIsAnEmptyReportWithoutReadingRecords() = runTest {
        synchronized(queries) { queries.clear() }

        assertEquals(Spending.EMPTY, spent())
        val read = synchronized(queries) { queries.toList() }
        assertTrue("$read", read.none { it.contains("FROM course_records") })
    }

    /**
     * Двадцать эпизодов и год ежедневных приёмов: приёмы — одним запросом, записи — одной порцией,
     * а не по запросу на строку.
     *
     * Красная проверка: читать запись эпизода по приёму — семь тысяч запросов.
     */
    @Test
    fun aYearOfDailyIntakesIsReadInAFewQueries() = runTest {
        val box = pack(id = PACK, quantity = tablets("20"))
        database.packageRepository().add(box)
        val start = LocalDate.of(2026, 3, 11)
        database.withTransaction {
            for (episode in 0 until 20) {
                val courseId = Uuid.parse("00000000-0000-4000-8000-%012x".format(0x2000 + episode))
                val plan = activeCourse(id = courseId, totalDoses = 365)
                val record = courseRecord(id = courseId, prescription = plan.prescription)
                database.courseRepository().activate(CourseDraft.Activation(plan, record))
                for (day in 0 until 365) {
                    val date = start.plusDays(day.toLong())
                    val at = date.atTime(9, 0).atZone(MOSCOW).toInstant()
                    val intake = plannedIntake(
                        id = Uuid.random(), courseId = courseId, plannedPackage = box.ref,
                        scheduledOn = date, plannedAt = at
                    ).confirm(TakenDose(box.ref, dose("2"), at))
                    database.intakes().upsert(intake.toStorageEntity())
                }
            }
        }
        synchronized(queries) { queries.clear() }

        val spending = spent(SpendingPeriod(start, start.plusDays(364)))

        assertEquals(20, spending.episodes.size)
        assertTrue(spending.episodes.all { it.intakes == 365 && it.total == tablets("730") })
        val read = synchronized(queries) { queries.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) } }
        // Курсор SQLite перечитывает большую выборку, когда она не помещается в окно, поэтому
        // счёт — «горсть», а не «ровно один»: семь тысяч строк не дают семи тысяч чтений.
        assertTrue("$read", read.count { it.contains("FROM intakes") } <= 2)
        assertTrue("$read", read.count { it.contains("FROM course_records") } <= 2)
        assertTrue("$read", read.size < 20)
    }
}
