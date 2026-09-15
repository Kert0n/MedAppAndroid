package com.kert0n.medapp.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.course.CourseCardViewModel
import com.kert0n.medapp.presentation.course.CoursePresentationDTO
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Карточка лечения над настоящими сценариями и базой (PLAN H3 №14): обеспечение и пункты она
 * читает, а отмена закрывает эпизод — план уничтожается, запись остаётся.
 */
@RunWith(AndroidJUnit4::class)
class CourseCardViewModelTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val start: LocalDate = LocalDate.of(2027, 3, 10)

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository()
            .add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun model(courseId: Uuid) = CourseCardViewModel(
        cancellation = scenarios.courseCancellation,
        courses = database.courseRepository(),
        intakes = database.intakeRepository(),
        courseId = courseId
    ).also { opened += it }

    private suspend fun started(): Uuid {
        val created = scenarios.courseDrafting.create("Нурофен", "по 2 после еды")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(schedule(start = start)),
                CourseDrafting.Edit.SetTotalDoses(Doses(4)),
                CourseDrafting.Edit.Attach(PACK, Doses(4))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    /** Идущее лечение: карточка знает и обеспечение, и пункты, которые построил календарь. */
    @Test
    fun aRunningCourseShowsItsCoverageAndItems() = runBlocking {
        val id = started()
        val model = model(id)

        val state = watching(model.state) { it.awaiting(PATIENTLY) { state -> state.coverage != null } }

        assertEquals("Нурофен", state.course?.title)
        assertEquals("по 2 после еды", state.course?.note)
        assertTrue(state.isRunning)
        assertEquals(4, state.coverage?.requiredDoses)
        // Коробки на двадцать таблеток по две хватает на все четыре приёма.
        assertEquals(4, state.coverage?.coveredDoses)
        assertTrue(state.items.isNotEmpty())
    }

    /** Отмена закрывает эпизод: плана больше нет, запись осталась и говорит, чем он кончился. */
    @Test
    fun cancellingClosesTheEpisodeAndKeepsTheRecord() = runBlocking {
        val id = started()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.coverage != null }
            model.askToCancel()
            state.awaiting(PATIENTLY) { it.asksToCancel }
            model.cancel()
            state.awaiting(PATIENTLY) { it.course?.kind == CoursePresentationDTO.Kind.CANCELLED }
        }

        // План уничтожен, запись осталась и помнит, чем лечение кончилось (PLAN D5).
        assertNull(database.courseRepository().findPlan(id))
        val record = requireNotNull(database.courseRepository().findRecord(id))
        assertEquals(CourseRecord.Outcome.CANCELLED, record.outcome)
    }

    /** Вопрос стоит до сценария: пока человек не ответил, лечение идёт. */
    @Test
    fun theQuestionStandsBeforeTheScenario() = runBlocking {
        val id = started()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it.coverage != null }
            model.askToCancel()
            val asked = state.awaiting(PATIENTLY) { it.asksToCancel }
            assertTrue(asked.isRunning)
        }

        assertNotNull(database.courseRepository().findPlan(id))
    }

    private companion object {
        /** Столько ждём чтения: между действием и состоянием стоят сценарий и потоки Room. */
        val PATIENTLY: Duration = 15.seconds
    }
}
