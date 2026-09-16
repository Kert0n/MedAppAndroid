package com.kert0n.medapp.storage.intake

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Приёмы по номерам (PLAN D8): так их спрашивает тот, у кого на руках обязательства — они несут
 * номер приёма и больше ничего. Без этого чтения строку «о чём не смогли напомнить» нечем показать:
 * лечение, доза и пачка лежат у приёма, а не у обязательства.
 */
@RunWith(AndroidJUnit4::class)
class IntakeReadingByIdsTest {

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

    /** Лечение на четыре приёма: пункты заводит календарь при начале. */
    private suspend fun started(): Uuid {
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
        val created = scenarios.courseDrafting.create("Нурофен")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = LocalDate.of(2027, 3, 10))),
                CourseDrafting.Edit.SetTotalDoses(Doses(4)),
                CourseDrafting.Edit.Attach(PACK, Doses(4))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    /** Спросили о двух — пришли двое, и с ними всё, что нужно строке: лечение, доза и пачка. */
    @Test
    fun onlyTheNamedIntakesComeBackAndTheyCarryTheirPlan() = runTest {
        val courseId = started()
        val all = database.intakeRepository().ofCourse(courseId).filterIsInstance<CourseIntake>().sortedBy { it.slot.at }
        val asked = setOf(all[0].id, all[2].id)

        val read = database.intakeRepository().observeOfIds(asked).first()

        assertEquals(asked, read.map { it.id }.toSet())
        assertEquals(dose("2"), (read.first() as com.kert0n.medapp.domain.intake.IntakeProjection.Scheduled).plannedAmount)
        assertEquals(PACK, (read.first() as com.kert0n.medapp.domain.intake.IntakeProjection.Scheduled).plannedPackage?.id)
    }

    /**
     * Ни о чём не спросили — ничего и не пришло. Пустой `IN ()` вернул бы **все** приёмы, и полка
     * «о чём не смогли напомнить» показала бы весь курс там, где напоминать не о чем.
     */
    @Test
    fun askingAboutNothingAnswersWithNothing() = runTest {
        started()

        assertEquals(emptyList<Any>(), database.intakeRepository().observeOfIds(emptySet()).first())
    }
}
