package com.kert0n.medapp.storage.course

import com.kert0n.medapp.fixture.confirmed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Обеспечение курса читается из хранения одним снимком: план, его приёмы и доступность его пачек
 * — с очередью и без чужих броней (PLAN D4, D5). Чужая коробка в расчёт не входит.
 */
@RunWith(AndroidJUnit4::class)
class CourseRoomRepositoryTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val start: LocalDate = LocalDate.of(2027, 3, 10)

    /** Чужая коробка на той же общей полке: к лечению не относится. */
    private val stranger: Uuid = Uuid.random()

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.medKits().upsert(medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity())
    }

    @After
    fun tearDown() = database.close()

    private val shelf get() = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref

    /**
     * Общая полка: [PACK] на 20 таблеток с чужой бронью 6, [OTHER_PACK] на 4; лечение по две
     * таблетки, 10 доз, с сегодняшнего дня: 6 доз из первой и 2 из второй.
     */
    private suspend fun treated(): Uuid {
        val packages = database.packageRepository()
        packages.add(pack(id = PACK, medKit = shelf, quantity = tablets("20"), form = TABLET_FORM))
        packages.saveClaims(PACK, Claims(total = BigDecimal("6")))
        packages.add(pack(id = OTHER_PACK, medKit = shelf, quantity = tablets("4"), form = TABLET_FORM))
        packages.add(pack(id = stranger, medKit = shelf, name = "Чужая", quantity = tablets("20"), form = TABLET_FORM))
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val draft = (scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(10)),
                CourseDrafting.Edit.Attach(PACK, Doses(6)),
                CourseDrafting.Edit.Attach(OTHER_PACK, Doses(2))
            )
        ) as CourseDrafting.Outcome.Saved).draft
        scenarios.courseActivation.activate(draft.id, draft.revision)
        return draft.id
    }

    private suspend fun coverageOf(id: Uuid): CourseCoverage =
        requireNotNull(database.courseRepository().observeCoverage(id).first())

    /** Доступно мне 14 → 7 доз, но выделено 6; вторая даёт 2. Граница ползунка — в строке. */
    @Test
    fun coverageCountsMyAvailabilityWithoutOthersClaims() = runTest {
        val id = treated()

        val found = coverageOf(id)

        assertEquals(10.doses, found.requiredDoses)
        assertEquals(8.doses, found.coveredDoses)
        val first = found.perSource[0]
        assertEquals(PACK, first.pkg.id)
        assertEquals(6.doses, first.allocatedDoses)
        assertEquals(6.doses, first.coveredDoses)
        // Даёт 7, а потребность сверх второй оставляет 8: граница — 7.
        assertEquals(7.doses, first.maxDoses)
        // Вторая даёт 2, потребность сверх первой оставляет 4: граница — 2.
        assertEquals(2.doses, found.perSource[1].maxDoses)
        val slots = requireNotNull(database.courseRepository().findPlan(id)).schedule.let { it.next(it.beginning, 10) }
        assertEquals(slots[8].at, found.firstUncoveredAt)
        assertEquals(found, database.courseRepository().observeCoverages().first().getValue(id))
    }

    /** Приём, чужой расход снимком и правка источников меняют обеспечение — каждый по-своему. */
    @Test
    fun coverageFollowsIntakesSnapshotsAndSourceEdits() = runTest {
        val id = treated()
        val before = coverageOf(id)

        val first = database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }
        scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now).confirmed()
        val afterIntake = coverageOf(id)
        assertEquals(9.doses, afterIntake.requiredDoses)
        assertNotEquals(before, afterIntake)

        // Сосед забрал: на сервере осталось 8 при той же чужой брони 6 — мне доступна одна доза.
        database.packageRepository().applySnapshot(
            PackageSnapshot(
                pack(id = PACK, medKit = shelf, quantity = tablets("8"), form = TABLET_FORM, claims = Claims(total = BigDecimal("6"))),
                PackageSyncState(PACK, ResourceVersion(2), ResourceVersion(2), syncedAt = now)
            ),
            now
        )
        val afterSnapshot = coverageOf(id)
        assertEquals(1.doses, afterSnapshot.perSource[0].coveredDoses)
        assertNotEquals(afterIntake, afterSnapshot)

        val plan = requireNotNull(database.courseRepository().findPlan(id))
        scenarios.sourceEditing.save(id, plan.revision, listOf(SourceEditing.Source(OTHER_PACK, Doses(2)), SourceEditing.Source(PACK, Doses(1))))
        val afterEdit = coverageOf(id)
        assertEquals(OTHER_PACK, afterEdit.perSource[0].pkg.id)
        assertNotEquals(afterSnapshot, afterEdit)
    }

    /**
     * Чужая коробка на той же полке в расчёт не входит: её пересчёт обеспечение не меняет
     * (красная проверка: собирать расклад по всем пачкам полки — изменится).
     */
    @Test
    fun aStrangersRecountLeavesCoverageAsItWas() = runTest {
        val id = treated()
        val before = coverageOf(id)

        scenarios.packageAdjusting.adjust(stranger, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("1")))

        assertEquals(before, coverageOf(id))
    }

    /** Черновик обеспечения не имеет; законченное лечение — тоже: плана больше нет. */
    @Test
    fun draftsAndFinishedTreatmentsHaveNoCoverage() = runTest {
        val id = treated()
        val draft = scenarios.courseDrafting.create("Черновик")
        assertNull(database.courseRepository().observeCoverage(draft.id).first())
        assertEquals(setOf(id), database.courseRepository().observeCoverages().first().keys)

        scenarios.courseCancellation.cancel(id)

        assertNull(database.courseRepository().observeCoverage(id).first())
        assertEquals(emptyMap<Uuid, CourseCoverage>(), database.courseRepository().observeCoverages().first())
    }
}
