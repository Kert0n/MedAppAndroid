package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Изменение лечения — тот же эпизод (PLAN C1, D5): отвеченное не переписывается, прошедшее
 * неотвеченное становится пропуском, будущее перестраивается, запись эпизода и брони идут за планом.
 */
@RunWith(AndroidJUnit4::class)
class CourseAmendmentTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios

    /** 10 марта, 15:00 по Москве. */
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
    }

    @After
    fun tearDown() = database.close()

    /**
     * Лечение с 8 марта раз в день, 10 доз по две таблетки, пять выделено из [PACK] на [shelf]; 8-е
     * и 9-е пропущены, сегодняшняя доза принята.
     */
    private suspend fun treated(shared: Boolean = false): Uuid {
        if (shared) {
            database.medKits().upsert(medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())
        }
        val shelf = if (shared) medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref else medKit().ref
        database.packageRepository().add(pack(id = PACK, medKit = shelf, quantity = tablets("20"), form = TABLET_FORM))
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 8))),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        scenarios.courseUpkeep.keepUp()
        val today = items(draft.id).first { it.slot.localDate == LocalDate.of(2027, 3, 10) }
        scenarios.intakeConfirmation.confirm(today.id, PACK, dose("2"), now).getOrThrow()
        return draft.id
    }

    private suspend fun items(id: Uuid): List<CourseIntake> =
        database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().sortedBy { it.plannedAt }

    private suspend fun revisionOf(id: Uuid): Revision = requireNotNull(database.courseRepository().findPlan(id)).revision

    /**
     * Врач сменил дозу: принятое осталось с прежней дозой, пропущенное — пропуском, будущие пункты —
     * с новой; запись эпизода говорит новое. Бронь местной пачки — не дело сервера: команд нет.
     */
    @Test
    fun aNewDoseRebuildsTheFutureAndKeepsThePast() = runTest {
        val id = treated()

        val outcome = scenarios.courseAmendment.amend(id, revisionOf(id), listOf(CourseAmendment.Change.SetDose(dose("1"))))

        assertTrue(outcome is CourseAmendment.Outcome.Amended)
        val items = items(id)
        assertEquals(dose("2"), items.single { it.status == IntakeStatus.TAKEN }.plannedAmount)
        assertEquals(2, items.count { it.status == IntakeStatus.MISSED })
        val future = items.filter { it.status == IntakeStatus.PLANNED }
        assertTrue(future.isNotEmpty() && future.all { it.plannedAmount == dose("1") })
        assertEquals(dose("1"), database.courseRepository().findRecord(id)?.prescription?.dose)
        assertTrue(database.syncOperations().all().isEmpty())
    }

    /** На общей полке бронь идёт за дозой: `выделено × новая доза` уезжает командой. */
    @Test
    fun aNewDoseOnASharedPackageResendsTheClaim() = runTest {
        val id = treated(shared = true)
        val allocated = requireNotNull(database.courseRepository().findPlan(id)).sources.single().allocatedDoses

        scenarios.courseAmendment.amend(id, revisionOf(id), listOf(CourseAmendment.Change.SetDose(dose("1"))))

        val last = database.syncOperations().all().map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }.last()
        assertEquals(PackageSyncCommand.SetClaim(PACK, tablets(allocated.count.toString())), last)
    }

    /** Новое расписание с завтрашнего дня: будущие пункты — в новое время, прошлое на месте. */
    @Test
    fun aNewScheduleMovesOnlyTheFuture() = runTest {
        val id = treated()
        val evening = schedule(start = LocalDate.of(2027, 3, 11), times = listOf(LocalTime.of(21, 0)))

        scenarios.courseAmendment.amend(id, revisionOf(id), listOf(CourseAmendment.Change.SetSchedule(evening)))

        val items = items(id)
        assertTrue(items.filter { it.status == IntakeStatus.PLANNED }.all { it.slot.localTime == LocalTime.of(21, 0) })
        assertEquals(3, items.count { it.status != IntakeStatus.PLANNED })
        assertEquals(evening, requireNotNull(database.courseRepository().findPlan(id)).schedule)
    }

    /** Отказы домена ничего не пишут; устаревшая редакция — «перечитать». */
    @Test
    fun aRejectedOrStaleChangeWritesNothing() = runTest {
        val id = treated()
        val revision = revisionOf(id)

        val millilitres = scenarios.courseAmendment.amend(id, revision, listOf(CourseAmendment.Change.SetDose(Dose(Quantity(BigDecimal.ONE, MILLILITRES)))))
        val past = scenarios.courseAmendment.amend(id, revision, listOf(CourseAmendment.Change.SetSchedule(schedule(start = LocalDate.of(2027, 3, 1)))))
        val stale = scenarios.courseAmendment.amend(id, Revision(99), listOf(CourseAmendment.Change.SetDose(dose("1"))))

        assertEquals(CourseAmendment.Outcome.Rejected(CourseRejected.Reason.UNIT_MISMATCH), millilitres)
        assertEquals(CourseAmendment.Outcome.Rejected(CourseRejected.Reason.SCHEDULE_IN_PAST), past)
        assertEquals(CourseAmendment.Outcome.Stale, stale)
        assertEquals(revision, revisionOf(id))
    }

    /** Число доз сократили до уже принятого — лечение этим и закончилось. */
    @Test
    fun shorteningToWhatWasTakenFinishesTheTreatment() = runTest {
        val id = treated()

        val outcome = scenarios.courseAmendment.amend(id, revisionOf(id), listOf(CourseAmendment.Change.SetTotalDoses(Doses(1))))

        assertEquals(CourseAmendment.Outcome.Finished, outcome)
        assertNull(database.courseRepository().findPlan(id))
        val record = requireNotNull(database.courseRepository().findRecord(id))
        assertEquals(CourseRecord.Outcome.COMPLETED, record.outcome)
        assertEquals(Doses(1), record.prescription.totalDoses)
    }
}
