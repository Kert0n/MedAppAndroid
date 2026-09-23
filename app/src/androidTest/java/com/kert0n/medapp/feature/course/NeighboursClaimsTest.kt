package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Общая коробка на 100 таблеток, из которых 50 занял сосед своим лечением: запланировать из неё
 * можно только свободное. Чужая бронь — обещание другому человеку, и план, который на неё
 * рассчитывает, обещает те же таблетки дважды (PLAN D4, D5). Разовый приём сверх свободного
 * спрашивает — это держит `UnplannedIntakeRecordingTest`, а путь человека целиком — история
 * Марины и Сергея «Одна коробка на два лечения» (`NeighboursClaimsStoryTest`).
 */
@RunWith(AndroidJUnit4::class)
class NeighboursClaimsTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.medKits().upsert(
            medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED, participantCount = 2).toMedKitStorageEntity()
        )
        database.packageRepository().add(
            pack(id = PACK, medKit = medKit(id = SHARED_KIT, publication = MedKit.Publication.PUBLISHED).ref, quantity = tablets("100"), form = TABLET_FORM),
            PackageSyncState(PACK, version = ResourceVersion(4), claimsVersion = ResourceVersion(2))
        )
        // Сосед заявил половину: 50 таблеток его, моей брони нет.
        database.packageRepository().saveClaims(PACK, Claims(total = BigDecimal("50"), mine = null))
    }

    @After
    fun tearDown() = database.close()

    /** Черновик: по одной таблетке, 60 приёмов, с сегодняшнего дня, коробка подключена с [allocated] дозами. */
    private suspend fun draft(allocated: Int): Pair<Uuid, Revision> {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("1")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(60)),
                CourseDrafting.Edit.Attach(PACK, Doses(allocated))
            )
        ) as CourseDrafting.Outcome.Saved
        return saved.draft.id to saved.draft.revision
    }

    private suspend fun allocated(id: Uuid): Doses? =
        database.courseRepository().findPlan(id)?.sources?.single { it.pkg.id == PACK }?.allocatedDoses

    /**
     * Черновик писали, не читая коробок, и выделили 60 доз, — а свободно 50. Начало лечения зажимает
     * выделение под свободное: 51-й приём из чужой брони не обещается. Без этого лечение начиналось
     * бы «обеспеченным» таблетками соседа, и нехватка открылась бы у обоих посреди курса.
     */
    @Test
    fun theStartPlansNoMoreThanWhatTheNeighbourLeftFree() = runTest {
        val (id, revision) = draft(allocated = 60)

        val started = scenarios.courseActivation.activate(id, revision)

        assertTrue("лечение не началось: $started", started is CourseActivation.Outcome.Started)
        assertEquals(Doses(50), allocated(id))
    }

    /**
     * У идущего лечения 51-я доза из этой коробки не выделяется: сценарий называет предел — 50 — и
     * не пишет ничего; ровно 50 записываются. Без этого ползунок, обойдённый любым путём мимо экрана,
     * отдавал бы лечению таблетки соседа.
     */
    @Test
    fun theFiftyFirstDoseIsNotPlannedFromTheNeighboursClaim() = runTest {
        val (id, revision) = draft(allocated = 10)
        scenarios.courseActivation.activate(id, revision)
        val before = requireNotNull(database.courseRepository().findPlan(id)).revision

        val beyond = scenarios.sourceEditing.save(id, before, listOf(SourceEditing.Source(PACK, Doses(51))))

        assertEquals(SourceEditing.Outcome.BeyondLimit(PACK, Doses(50)), beyond)
        assertEquals(Doses(10), allocated(id))

        val within = scenarios.sourceEditing.save(id, before, listOf(SourceEditing.Source(PACK, Doses(50))))

        assertTrue("50 доз не записались: $within", within is SourceEditing.Outcome.Saved)
        assertEquals(Doses(50), allocated(id))
    }
}
