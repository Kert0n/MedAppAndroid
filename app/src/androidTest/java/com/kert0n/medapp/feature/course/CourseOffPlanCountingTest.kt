package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.value.Doses
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
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.time.Instant
import java.time.LocalDate
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
 * Счёт доз мимо плана (PLAN D5, C1 «Воздушные дозы»): потребность и бронь уменьшаются по порядку
 * расходования, остаток пачки не трогается, лишние плановые пункты уходят; доз впереди не
 * осталось — лечение закончено.
 */
@RunWith(AndroidJUnit4::class)
class CourseOffPlanCountingTest {

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

    /** Лечение с 8 марта раз в день, 6 доз по две таблетки, все шесть выделены из [PACK]. */
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
                CourseDrafting.Edit.SetTotalDoses(Doses(6)),
                CourseDrafting.Edit.Attach(PACK, Doses(6))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        scenarios.courseUpkeep.keepUp()
        return draft.id
    }

    private suspend fun items(id: Uuid): List<CourseIntake> =
        database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().sortedBy { it.plannedAt }

    private suspend fun revisionOf(id: Uuid): Revision = requireNotNull(database.courseRepository().findPlan(id)).revision

    private suspend fun allocated(id: Uuid): Doses =
        requireNotNull(database.courseRepository().findPlan(id)).sources.single().allocatedDoses

    /** Две дозы из кармана: выделение — четыре, остаток пачки прежний, плановых пунктов меньше на два. */
    @Test
    fun dosesOffPlanReduceTheAllocationAndTheFutureButNotTheStock() = runTest {
        val id = treated()
        val plannedBefore = items(id).count { it.status == IntakeStatus.PLANNED }

        val outcome = scenarios.courseOffPlanCounting.set(id, revisionOf(id), Doses(2))

        assertTrue(outcome is CourseOffPlanCounting.Outcome.Set)
        assertEquals(Doses(4), allocated(id))
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(plannedBefore - 2, items(id).count { it.status == IntakeStatus.PLANNED })
        // Прошлое до счёта отмечено: 8-е и 9-е — пропуски.
        assertEquals(2, items(id).count { it.status == IntakeStatus.MISSED })
        assertTrue(database.syncOperations().all().isEmpty())
    }

    /** На общей полке бронь идёт за выделением: уезжает разницей. */
    @Test
    fun dosesOffPlanOnASharedPackageResendTheClaim() = runTest {
        val id = treated(shared = true)

        scenarios.courseOffPlanCounting.set(id, revisionOf(id), Doses(2))

        val last = database.syncOperations().all().map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }.last()
        assertEquals(PackageSyncCommand.SetClaim(PACK, tablets("8")), last)
    }

    /** Все шесть доз мимо плана — лечение закончено: запись закрыта, план ушёл, плановые отменены. */
    @Test
    fun allDosesOffPlanFinishTheTreatment() = runTest {
        val id = treated()

        val outcome = scenarios.courseOffPlanCounting.set(id, revisionOf(id), Doses(6))

        assertEquals(CourseOffPlanCounting.Outcome.Finished, outcome)
        assertNull(database.courseRepository().findPlan(id))
        assertEquals(CourseRecord.Outcome.COMPLETED, database.courseRepository().findRecord(id)?.outcome)
        assertTrue(items(id).none { it.status == IntakeStatus.PLANNED })
    }

    /** Уменьшение счёта бронь обратно не растит, а устаревшая редакция ничего не пишет. */
    /**
     * **Календарь — отражение потребности в обе стороны** (C1 «Потребность перестраивает будущее
     * одной дверью»). Человек ошибся счётом и вернул его: доз впереди снова столько же, и плановые
     * пункты возвращаются — пока правка счёта звала только `prune`, вернувшаяся потребность
     * оставалась без пунктов и без напоминаний до следующей достройки календаря.
     */
    @Test
    fun loweringTheCountBackRestoresTheCalendar() = runTest {
        val id = treated()
        val plannedBefore = items(id).count { it.status == IntakeStatus.PLANNED }
        scenarios.courseOffPlanCounting.set(id, revisionOf(id), Doses(2))
        assertEquals(plannedBefore - 2, items(id).count { it.status == IntakeStatus.PLANNED })

        scenarios.courseOffPlanCounting.set(id, revisionOf(id), Doses(0))

        assertEquals("потребность вернулась, а пункты — нет", plannedBefore, items(id).count { it.status == IntakeStatus.PLANNED })
    }

    @Test
    fun loweringTheCountKeepsTheAllocationAndAStaleRevisionWritesNothing() = runTest {
        val id = treated()
        scenarios.courseOffPlanCounting.set(id, revisionOf(id), Doses(2))
        val revision = revisionOf(id)

        assertTrue(scenarios.courseOffPlanCounting.set(id, revision, Doses(1)) is CourseOffPlanCounting.Outcome.Set)
        assertEquals(Doses(4), allocated(id))
        assertEquals(CourseOffPlanCounting.Outcome.Stale, scenarios.courseOffPlanCounting.set(id, revision, Doses(3)))
        assertEquals(CourseOffPlanCounting.Outcome.Gone, scenarios.courseOffPlanCounting.set(Uuid.random(), revision, Doses(3)))
    }
}
