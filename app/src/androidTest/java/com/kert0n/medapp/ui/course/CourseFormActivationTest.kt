package com.kert0n.medapp.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.course.CourseFormError
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.presentation.course.CourseFormViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Начало лечения из редактора (PLAN H3 №15, D5): набранное записывается, и лечение начинается по
 * свежей редакции. Полноту назначения требует сценарий — экран второй проверки не держит.
 */
@RunWith(AndroidJUnit4::class)
class CourseFormActivationTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val start: LocalDate = LocalDate.of(2027, 3, 10)

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        val packages = database.packageRepository()
        packages.add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
        packages.add(pack(id = OTHER_PACK, name = "Ибупрофен", quantity = tablets("12"), form = TABLET_FORM))
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun model(courseId: Uuid?) = CourseFormViewModel(
        drafting = scenarios.courseDrafting,
        activation = scenarios.courseActivation,
        courses = database.courseRepository(),
        vocabulary = FakeVocabulary(),
        courseId = courseId
    ).also { opened += it }

    private suspend fun draft(vararg edits: CourseDrafting.Edit): Uuid {
        val created = scenarios.courseDrafting.create("Нурофен")
        return (scenarios.courseDrafting.edit(created.id, created.revision, edits.toList())
            as CourseDrafting.Outcome.Saved).draft.id
    }

    private suspend fun prescribed(vararg extra: CourseDrafting.Edit): Uuid = draft(
        CourseDrafting.Edit.SetDose(dose("2")),
        CourseDrafting.Edit.SetForm(TABLET_FORM),
        CourseDrafting.Edit.SetSchedule(schedule(start = start)),
        CourseDrafting.Edit.SetTotalDoses(Doses(4)),
        *extra
    )

    /**
     * «Начать» берёт то, что человек видит на экране: правка записывается, и лечение начинается
     * уже с ней — а не с тем, что лежало в базе до нажатия.
     */
    @Test
    fun startingWritesWhatIsOnScreenAndOnlyThenBegins() = runBlocking {
        val id = prescribed(CourseDrafting.Edit.Attach(PACK, Doses(4)))
        val model = model(id)

        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            model.edit(open.form.copy(totalDoses = "6"))
            model.start()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.startedId != null }
        }

        val plan = requireNotNull(database.courseRepository().findPlan(id))
        assertEquals(Doses(6), plan.totalDoses)
        assertNotNull(database.courseRepository().findRecord(id))
    }

    /** Чего не хватает для начала, называет сценарий — и экран ставит это у своего поля. */
    @Test
    fun anIncompletePrescriptionIsRefusedAtItsOwnField() = runBlocking {
        val id = draft()
        val model = model(id)

        val state = watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing }
            model.start()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.error != null }
        } as CourseFormUiState.Editing

        val error = state.error as CourseFormError.Rejected
        assertEquals(CourseRejected.Reason.SCHEDULE_MISSING, error.reason)
        assertEquals(CourseFormError.Field.START, error.field)
        // Лечение не началось: записи эпизода нет, черновик цел.
        assertNull(database.courseRepository().findRecord(id))
        assertNotNull(database.courseRepository().findDraft(id))
    }

    /** Коробка занята другим лечением — отказ называет её так, как человек её знает (D5). */
    @Test
    fun aBoxHeldByAnotherCourseIsNamedInTheRefusal() = runBlocking {
        val held = prescribed(CourseDrafting.Edit.Attach(PACK, Doses(1)))
        scenarios.courseActivation.activate(held, requireNotNull(database.courseRepository().findDraft(held)).revision)
        val id = prescribed(CourseDrafting.Edit.Attach(PACK, Doses(1)))
        val model = model(id)

        val state = watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing }
            model.start()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.error != null }
        } as CourseFormUiState.Editing

        assertEquals(CourseFormError.PackageTaken("Нурофен"), state.error)
        assertNull(database.courseRepository().findRecord(id))
    }

    /** Лечение начинается и без лекарства на руках: пачка подключается, когда её купят (D5). */
    @Test
    fun treatmentStartsEvenWithNoBoxAtHand() = runBlocking {
        val id = prescribed()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing }
            model.start()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.startedId != null }
        }

        assertNotNull(database.courseRepository().findPlan(id))
        // Обеспечено ноль из четырёх: лечение идёт, а лекарства ещё нет.
        val coverage = requireNotNull(database.courseRepository().observeCoverage(id).first())
        assertEquals(Doses(0), coverage.coveredDoses)
        assertEquals(Doses(4), coverage.requiredDoses)
    }

    /** Второе нажатие, пока идёт первое или пока лечение уже началось, ничего не начинает. */
    @Test
    fun pressingStartTwiceBeginsOneTreatment() = runBlocking {
        val id = prescribed(CourseDrafting.Edit.Attach(PACK, Doses(4)))
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing }
            model.start()
            model.start()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.startedId != null }
        }

        val record = requireNotNull(database.courseRepository().findRecord(id))
        assertEquals(id, record.id)
        // Черновика больше нет — он стал лечением, и второе нажатие второго эпизода не завело.
        assertNull(database.courseRepository().findDraft(id))
    }

    private companion object {
        val PATIENTLY: Duration = 15.seconds
    }
}
