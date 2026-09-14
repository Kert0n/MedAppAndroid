package com.kert0n.medapp.storage.intake

import com.kert0n.medapp.fixture.confirmed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** История коробки — факты приёма из неё, и она переживает саму коробку (PLAN D6, экран 19). */
@RunWith(AndroidJUnit4::class)
class PackageHistoryReadingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(pack(id = PACK, name = "Парацетамол", quantity = tablets("20"), form = TABLET_FORM))
        database.packageRepository().add(pack(id = OTHER_PACK, name = "Парацетамол", quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun historyOf(id: kotlin.uuid.Uuid): List<IntakeProjection> =
        database.intakeRepository().observeOfPackage(id).first()

    /** Курсовой приём, разовый до него и разовый из одноимённой коробки: два своих, по времени. */
    @Test
    fun courseAndOneOffIntakesFromTheBoxComeTogetherInTimeOrder() = runTest {
        val created = scenarios.courseDrafting.create("Ибупрофен")
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
        val first = database.intakeRepository().ofCourse(draft.id).filterIsInstance<CourseIntake>().minBy { it.plannedAt }
        scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now).confirmed()
        scenarios.unplannedIntakeRecording.record(PACK, dose("1"), now.minusSeconds(3_600))
        scenarios.unplannedIntakeRecording.record(OTHER_PACK, dose("1"), now.minusSeconds(60))

        val history = historyOf(PACK)

        assertEquals(listOf(now.minusSeconds(3_600), now), history.map { it.taken?.at })
        assertTrue(history[0] is IntakeProjection.Unplanned)
        assertTrue(history[1] is IntakeProjection.Scheduled)
        assertTrue(history.all { it.taken?.pkg?.id == PACK })
        // Плановые пункты коробке не принадлежат: только факты.
        assertNull(history.firstOrNull { it.taken == null })
        assertEquals(1, historyOf(OTHER_PACK).size)
    }

    /** Коробка кончилась — история читается по-прежнему, с именем из записи. */
    @Test
    fun historySurvivesTheEndOfTheBox() = runTest {
        scenarios.unplannedIntakeRecording.record(PACK, dose("1"), now)
        scenarios.packageRemoval.remove(PACK)
        assertNull(database.packageRepository().find(PACK))

        val history = historyOf(PACK)

        assertEquals(1, history.size)
        assertEquals("Парацетамол", history.single().taken?.pkg?.name)
    }
}
