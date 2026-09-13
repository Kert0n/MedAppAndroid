package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.OTHER_PACK
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
 * Пачки идущего лечения правятся одним решением: состав, порядок, выделения, назначения пачек,
 * будущие пункты и брони — вместе (PLAN D5, F5).
 */
@RunWith(AndroidJUnit4::class)
class SourceEditingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
    }

    @After
    fun tearDown() = database.close()

    private fun shelf(shared: Boolean): MedKitRef =
        if (shared) medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref else medKit().ref

    /** Две пачки по 20 таблеток; лечение по две таблетки, 10 доз, с сегодняшнего дня, пять доз из [PACK]. */
    private suspend fun treated(shared: Boolean = false): Uuid {
        if (shared) {
            database.medKits().upsert(medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())
        }
        database.packageRepository().add(pack(id = PACK, medKit = shelf(shared), quantity = tablets("20"), form = TABLET_FORM))
        database.packageRepository().add(pack(id = OTHER_PACK, medKit = shelf(shared), quantity = tablets("20"), form = TABLET_FORM))
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(5))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private suspend fun revisionOf(id: Uuid): Revision = requireNotNull(database.courseRepository().findPlan(id)).revision

    private suspend fun commands() = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    /** Местная полка: вторая пачка встаёт первой, обе заняты этим курсом, пункты берут из неё — и серверу ничего. */
    @Test
    fun aLocalCompositionChangesWithoutAWordToTheServer() = runTest {
        val id = treated()

        val outcome = scenarios.sourceEditing.save(
            id, revisionOf(id), listOf(SourceEditing.Source(OTHER_PACK, Doses(3)), SourceEditing.Source(PACK, Doses(5)))
        )

        assertTrue(outcome is SourceEditing.Outcome.Saved)
        val plan = requireNotNull(database.courseRepository().findPlan(id))
        assertEquals(listOf(OTHER_PACK, PACK), plan.sources.map { it.pkg.id })
        assertEquals(id, database.courseRepository().courseHolding(OTHER_PACK))
        val first = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }
        assertEquals(OTHER_PACK, first.plannedPackage?.id)
        assertTrue(database.syncOperations().all().isEmpty())
    }

    /** Общая полка: команду получает только пачка, чья бронь изменилась. */
    @Test
    fun onlyAChangedClaimTravels() = runTest {
        val id = treated(shared = true)
        val before = commands().size

        scenarios.sourceEditing.save(
            id, revisionOf(id), listOf(SourceEditing.Source(PACK, Doses(5)), SourceEditing.Source(OTHER_PACK, Doses(2)))
        )

        assertEquals(listOf(PackageSyncCommand.SetClaim(OTHER_PACK, tablets("4"))), commands().drop(before))
    }

    /** Отвязанная пачка свободна, бронь с неё снята, и будущие пункты её не называют. */
    @Test
    fun aDetachedPackageIsFreedAndReleased() = runTest {
        val id = treated(shared = true)

        scenarios.sourceEditing.save(id, revisionOf(id), emptyList())

        assertNull(database.courseRepository().courseHolding(PACK))
        assertEquals(PackageSyncCommand.ReleaseClaim(PACK), commands().last())
        val planned = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().filter { it.status == IntakeStatus.PLANNED }
        assertTrue(planned.isNotEmpty() && planned.all { it.plannedPackage == null })
    }

    /** Больше, чем пачка даёт или чем оставляет потребность, не выделяется: предел назван, ничего не записано. */
    @Test
    fun anAllocationBeyondTheLimitIsNamed() = runTest {
        val id = treated()
        val revision = revisionOf(id)

        val outcome = scenarios.sourceEditing.save(
            id, revision, listOf(SourceEditing.Source(PACK, Doses(5)), SourceEditing.Source(OTHER_PACK, Doses(8)))
        )

        assertEquals(SourceEditing.Outcome.BeyondLimit(OTHER_PACK, Doses(5)), outcome)
        assertEquals(revision, revisionOf(id))
    }

    /** Пачка другого идущего лечения сюда не встаёт: одна пачка — один активный курс. */
    @Test
    fun aPackageOfAnotherTreatmentIsTaken() = runTest {
        val id = treated()
        val other = scenarios.courseDrafting.create("Второе")
        val otherDraft = (scenarios.courseDrafting.edit(
            other.id, other.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(3)),
                CourseDrafting.Edit.Attach(OTHER_PACK, Doses(1))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(otherDraft.id, otherDraft.revision)

        val outcome = scenarios.sourceEditing.save(
            id, revisionOf(id), listOf(SourceEditing.Source(PACK, Doses(5)), SourceEditing.Source(OTHER_PACK, Doses(1)))
        )

        assertEquals(SourceEditing.Outcome.PackageTaken(OTHER_PACK), outcome)
    }
}
