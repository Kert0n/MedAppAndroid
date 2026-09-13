package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.course.Revision
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Начало лечения: план и запись эпизода вместе, пачки заняты, выделения зажаты под доступное и
 * стали бронями, окно пунктов построено — одной транзакцией (PLAN D5, F5).
 */
@RunWith(AndroidJUnit4::class)
class CourseActivationTest {

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

    /** Черновик раз в день с сегодняшнего дня, [doses] доз выделено из [packageId]. */
    private suspend fun prepared(packageId: Uuid? = PACK, doses: Int = 5, title: String = "Ибупрофен"): Pair<Uuid, Revision> {
        val drafting = scenarios.courseDrafting
        val created = drafting.create(title)
        val edits = mutableListOf<CourseDrafting.Edit>(
            CourseDrafting.Edit.SetDose(dose("2")),
            CourseDrafting.Edit.SetForm(TABLET_FORM),
            CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
            CourseDrafting.Edit.SetTotalDoses(Doses(10))
        )
        packageId?.let { edits += CourseDrafting.Edit.Attach(it, Doses(doses)) }
        val saved = (drafting.edit(created.id, created.revision, edits) as CourseDrafting.Outcome.Saved).draft
        return saved.id to saved.revision
    }

    private suspend fun commands() = database.syncOperations().all()
        .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }

    /** Обычный путь в местной аптечке: всё записано, пачка занята, пункты построены — и команд серверу нет. */
    @Test
    fun aLocalTreatmentStartsWithoutAWordToTheServer() = runTest {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        val (id, revision) = prepared()

        val outcome = scenarios.courseActivation.activate(id, revision)

        assertTrue(outcome is CourseActivation.Outcome.Started)
        val courses = database.courseRepository()
        assertNotNull(courses.findPlan(id))
        assertNotNull(courses.findRecord(id))
        assertEquals(id, courses.courseHolding(PACK))
        assertTrue(database.intakeRepository().ofCourse(id).isNotEmpty())
        assertTrue(database.syncOperations().all().isEmpty())
    }

    /** На общей полке выделение становится бронью: одна команда на пачку, `выделено × доза`. */
    @Test
    fun aSharedPackageIsClaimedByItsAllocation() = runTest {
        database.medKits().upsert(medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())
        database.packageRepository().add(
            pack(id = PACK, medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, quantity = tablets("20"), form = TABLET_FORM)
        )
        val (id, revision) = prepared(doses = 5)

        scenarios.courseActivation.activate(id, revision)

        assertEquals(listOf(PackageSyncCommand.SetClaim(PACK, tablets("10"))), commands())
    }

    /** Выделили больше, чем пачка даёт: лечение начинается с тем, что есть, и бронь не просит лишнего. */
    @Test
    fun anAllocationBeyondThePackageIsClampedAtTheStart() = runTest {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("6"), form = TABLET_FORM))
        val (id, revision) = prepared(doses = 5)

        scenarios.courseActivation.activate(id, revision)

        assertEquals(tablets("6"), database.courseRepository().findPlan(id)?.allocatedOf(pack(id = PACK).ref))
    }

    /** Одна пачка — один активный курс: второе лечение с той же пачкой не начинается и называет её. */
    @Test
    fun aPackageHeldByOneTreatmentDoesNotStartAnother() = runTest {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        val (first, firstRevision) = prepared(title = "Первое")
        val (second, secondRevision) = prepared(title = "Второе")

        scenarios.courseActivation.activate(first, firstRevision)
        val outcome = scenarios.courseActivation.activate(second, secondRevision)

        assertEquals(CourseActivation.Outcome.PackageTaken(PACK), outcome)
        assertNotNull(database.courseRepository().findDraft(second))
    }

    /** Лечение начинается и без пачки; повтор отвечает «уже идёт»; неполное назначение — причина. */
    @Test
    fun aTreatmentStartsWithoutAPackageOnceAndOnlyWhenPrescribed() = runTest {
        val (id, revision) = prepared(packageId = null)
        val incomplete = scenarios.courseDrafting.create("Без назначения")

        assertTrue(scenarios.courseActivation.activate(id, revision) is CourseActivation.Outcome.Started)
        assertEquals(CourseActivation.Outcome.AlreadyStarted, scenarios.courseActivation.activate(id, revision))
        assertEquals(
            CourseActivation.Outcome.Rejected(CourseRejected.Reason.SCHEDULE_MISSING),
            scenarios.courseActivation.activate(incomplete.id, incomplete.revision)
        )
        assertEquals(CourseActivation.Outcome.Stale, scenarios.courseActivation.activate(incomplete.id, Revision(99)))
    }
}
