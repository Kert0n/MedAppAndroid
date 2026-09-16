package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Выделение у черновика не переживает уменьшение числа приёмов (PLAN D5): лишнее сверх потребности
 * снимается, иначе человек видит «выделено 20» у лечения, которому нужно 10, — и это число уезжает
 * в бронь при начале лечения.
 */
@RunWith(AndroidJUnit4::class)
class DraftAllocationTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2)
                .toMedKitStorageEntity()
        )
        database.packageRepository().add(
            pack(
                id = PACK,
                medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref,
                quantity = tablets("40"),
                form = TABLET_FORM
            )
        )
    }

    @After
    fun tearDown() = database.close()

    /** Черновик: доза 2, двадцать приёмов, коробка на сорок таблеток и двадцать приёмов из неё. */
    private suspend fun draftWithTwentyAllocated(): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(20)),
                CourseDrafting.Edit.Attach(PACK, Doses(20))
            )
        ) as CourseDrafting.Outcome.Saved
        return saved.draft.id
    }

    /**
     * Приёмов стало вдвое меньше — выделение идёт следом. Без этого правила черновик держит
     * «выделено 20» при потребности в 10: экран показывает число, которого лечению не нужно, а
     * начало лечения просит по нему бронь.
     */
    @Test
    fun loweringTheNumberOfDosesLowersTheAllocation() = runTest {
        val id = draftWithTwentyAllocated()
        val courses = database.courseRepository()

        val before = requireNotNull(courses.findDraft(id))
        scenarios.courseDrafting.edit(id, before.revision, listOf(CourseDrafting.Edit.SetTotalDoses(Doses(10))))

        val after = requireNotNull(courses.findDraft(id))
        assertEquals(Doses(10), after.totalDoses)
        assertEquals(Doses(10), after.medicine.sources.single().allocatedDoses)
    }

    /**
     * Обратный вход в то же расхождение: приёмов десять, а человек просит выделить двадцать.
     * Запись отвергает просьбу и называет предел — молча подрезать её нельзя, это был бы ответ не
     * о том, о чём просили. Без правила черновик снова держит «выделено 20» при потребности в 10,
     * и держит его до самого начала лечения (найдено разбором 2026-09-16).
     */
    @Test
    fun allocatingMoreThanTheTreatmentNeedsIsRefused() = runTest {
        val id = draftWithTwentyAllocated()
        val courses = database.courseRepository()
        val lowered = requireNotNull(courses.findDraft(id))
        scenarios.courseDrafting.edit(id, lowered.revision, listOf(CourseDrafting.Edit.SetTotalDoses(Doses(10))))

        val draft = requireNotNull(courses.findDraft(id))
        val outcome = scenarios.courseDrafting.edit(
            id, draft.revision, listOf(CourseDrafting.Edit.Allocate(PACK, Doses(20)))
        )

        assertEquals(CourseDrafting.Outcome.BeyondLimit(PACK, Doses(10)), outcome)
        val after = requireNotNull(courses.findDraft(id))
        assertEquals(Doses(10), after.medicine.sources.single().allocatedDoses)
    }

    /**
     * Что бы ни лежало в черновике, бронь просит по потребности: начало лечения зажимает выделение
     * и объявляет **десять** доз, а не двадцать.
     */
    @Test
    fun theClaimAtTheStartAsksForWhatTheTreatmentNeeds() = runTest {
        val id = draftWithTwentyAllocated()
        val courses = database.courseRepository()
        val before = requireNotNull(courses.findDraft(id))
        scenarios.courseDrafting.edit(id, before.revision, listOf(CourseDrafting.Edit.SetTotalDoses(Doses(10))))

        val draft = requireNotNull(courses.findDraft(id))
        scenarios.courseActivation.activate(id, draft.revision)

        val plan = requireNotNull(courses.findPlan(id))
        assertEquals(Doses(10), plan.medicine.sources.single().allocatedDoses)
        val commands = database.syncOperations().all()
            .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }
        assertEquals(listOf(PackageSyncCommand.SetClaim(PACK, tablets("20"))), commands)
    }

    /**
     * У идущего лечения то же уменьшение идёт до конца: выделение зажато, и бронь переобъявлена
     * меньшим числом — иначе на общей полке соседям оставалось бы занятым то, что нам уже не нужно.
     */
    @Test
    fun loweringTheNumberOfDosesOfARunningTreatmentLowersTheClaim() = runTest {
        val id = draftWithTwentyAllocated()
        val courses = database.courseRepository()
        val draft = requireNotNull(courses.findDraft(id))
        scenarios.courseActivation.activate(id, draft.revision)

        val plan = requireNotNull(courses.findPlan(id))
        scenarios.courseAmendment.amend(
            id, plan.revision, listOf(CourseAmendment.Change.SetTotalDoses(Doses(10)))
        )

        val after = requireNotNull(courses.findPlan(id))
        assertEquals(Doses(10), after.medicine.sources.single().allocatedDoses)
        val commands = database.syncOperations().all()
            .map { (it.toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation.command }
        assertEquals(PackageSyncCommand.SetClaim(PACK, tablets("20")), commands.last())
    }
}
