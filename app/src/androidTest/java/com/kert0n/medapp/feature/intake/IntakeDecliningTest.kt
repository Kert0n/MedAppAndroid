package com.kert0n.medapp.feature.intake

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.courseRepository
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
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Отказ от приёма — `MISSED` по решению человека (PLAN D6, C1): расхода нет, выделение прежнее,
 * доза уезжает вперёд, и календарь достраивает ещё один пункт; отвеченное не перетирается.
 */
@RunWith(AndroidJUnit4::class)
class IntakeDecliningTest {

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

    /** Лечение с 10 марта раз в день, 5 доз по две таблетки из [PACK]. */
    private suspend fun treated(): Uuid {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
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
        scenarios.courseUpkeep.keepUp()
        return draft.id
    }

    private suspend fun items(id: Uuid): List<CourseIntake> =
        database.intakeRepository().ofCourse(id).filterIsInstance<CourseIntake>().sortedBy { it.plannedAt }

    @Test
    fun decliningMarksTheItemMissedAndMovesTheDoseForward() = runTest {
        val id = treated()
        val today = items(id).first()
        val plannedBefore = items(id).count { it.status == IntakeStatus.PLANNED }

        val outcome = scenarios.intakeDeclining.decline(today.id, now)

        assertEquals(IntakeDeclining.Outcome.DECLINED, outcome)
        val declined = items(id).first { it.id == today.id }
        assertEquals(IntakeStatus.MISSED, declined.status)
        assertEquals(now, declined.answer?.at)
        // Расхода нет, выделение прежнее, а плановых пунктов столько же — доза уехала вперёд.
        assertEquals(tablets("20"), database.packageRepository().find(PACK)?.quantity)
        assertEquals(Doses(5), database.courseRepository().findPlan(id)?.sources?.single()?.allocatedDoses)
        assertEquals(plannedBefore, items(id).count { it.status == IntakeStatus.PLANNED })
        assertEquals(IntakeDeclining.Outcome.ALREADY_ANSWERED, scenarios.intakeDeclining.decline(today.id, now))
    }

    @Test
    fun aTakenItemIsNotDeclinedAndAnUnknownOneIsGone() = runTest {
        val id = treated()
        val today = items(id).first()
        scenarios.intakeConfirmation.confirm(today.id, PACK, dose("2"), now).getOrThrow()

        assertEquals(IntakeDeclining.Outcome.ALREADY_ANSWERED, scenarios.intakeDeclining.decline(today.id, now))
        assertEquals(IntakeStatus.TAKEN, items(id).first { it.id == today.id }.status)
        assertEquals(IntakeDeclining.Outcome.GONE, scenarios.intakeDeclining.decline(Uuid.random(), now))
    }
}
