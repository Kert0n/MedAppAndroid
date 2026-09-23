package com.kert0n.medapp.ui.course

import java.time.Clock
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.feature.time.Today
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.FakeVocabulary
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
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.presentation.course.CourseFormViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.math.BigDecimal
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Изменение идущего лечения — **тот же эпизод** (PLAN C1, D5): прошлые пункты остаются со своей
 * дозой, будущие перестраиваются, а название правится у записи и назначения не касается.
 */
@RunWith(AndroidJUnit4::class)
class CourseAmendmentTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios

    /** Полдень десятого марта: лечение уже идёт со вчерашнего дня, и часть пунктов позади. */
    private val now: Instant = Instant.parse("2027-03-10T09:00:00Z")
    private val yesterday: LocalDate = LocalDate.of(2027, 3, 9)

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository()
            .add(pack(id = PACK, name = "Нурофен", quantity = tablets("40"), form = TABLET_FORM))
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun model(courseId: Uuid) = CourseFormViewModel(
        drafting = scenarios.courseDrafting,
        activation = scenarios.courseActivation,
        amendment = scenarios.courseAmendment,
        renaming = scenarios.courseRenaming,
        courses = database.courseRepository(),
        vocabulary = FakeVocabulary(),
        today = Today(Clock.fixed(scenarios.now, scenarios.zone), QuietClock),
        courseId = courseId
    ).also { opened += it }

    /** Лечение, начатое вчера: два приёма в день, восемь приёмов, коробка на сорок таблеток. */
    private suspend fun started(): Uuid {
        val created = scenarios.courseDrafting.create("Нурофен", "по 2 после еды")
        val saved = scenarios.courseDrafting.edit(
            created.id, created.revision,
            listOf(
                CourseDrafting.Edit.SetDose(dose("2")),
                CourseDrafting.Edit.SetForm(TABLET_FORM),
                CourseDrafting.Edit.SetSchedule(
                    schedule(start = yesterday, times = listOf(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(21, 0)))
                ),
                CourseDrafting.Edit.SetTotalDoses(Doses(8)),
                CourseDrafting.Edit.Attach(PACK, Doses(8))
            )
        ) as CourseDrafting.Outcome.Saved
        scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
        return saved.draft.id
    }

    private suspend fun openedForm(model: CourseFormViewModel) = watching(model.state) {
        it.awaiting(PATIENTLY) { state -> state is CourseFormUiState.Editing }
    } as CourseFormUiState.Editing

    /** Редактор узнаёт идущее лечение сам: у ключа маршрута номер один — эпизод один. */
    @Test
    fun theEditorOpensARunningCourseWithItsPrescription() = runBlocking {
        val id = started()

        val state = openedForm(model(id))

        assertEquals(CourseFormUiState.Mode.RUNNING, state.mode)
        assertEquals("Нурофен", state.form.title)
        assertEquals("по 2 после еды", state.form.note)
        assertEquals("2", state.form.doseAmount)
        assertEquals("8", state.form.totalDoses)
    }

    /**
     * Доза меняется у лечения, которое **уже идёт со вчера**: прошлое началось, и изменение
     * назначения об него не спотыкается. Что экран просит только тронутое, проверяет
     * `CourseChangesTest` — по базе это неотличимо, потому что переходы домена идемпотентны.
     */
    @Test
    fun theDoseOfACourseStartedYesterdayIsChanged() = runBlocking {
        val id = started()
        val model = model(id)

        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            model.edit(open.form.copy(doseAmount = "3"))
            model.save()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && (it.isSaved || it.error != null) }
        }

        val state = model.state.value as CourseFormUiState.Editing
        assertNull(state.error)
        assertTrue(state.isSaved)
        val plan = requireNotNull(database.courseRepository().findPlan(id))
        assertEquals(BigDecimal("3"), plan.dose.quantity.amount)
    }

    /** Прошлые пункты помнят свою дозу, будущие перестраиваются под новую (PLAN D5). */
    @Test
    fun pastItemsKeepTheirDoseAndFutureOnesTakeTheNew() = runBlocking {
        val id = started()
        val model = model(id)

        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            model.edit(open.form.copy(doseAmount = "3"))
            model.save()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isSaved }
        }

        val items = database.intakeRepository().observeOfCourse(id).first()
            .filterIsInstance<IntakeProjection.Scheduled>()
        val past = items.filter { it.slot.at < now }
        val future = items.filter { it.slot.at >= now }
        assertTrue(past.isNotEmpty())
        assertTrue(future.isNotEmpty())
        assertTrue(past.all { it.plannedAmount.quantity.amount == BigDecimal("2") })
        assertTrue(future.all { it.plannedAmount.quantity.amount == BigDecimal("3") })
    }

    /** Название и заметка правятся у записи: назначение и редакция плана этого не замечают. */
    @Test
    fun renamingLeavesThePrescriptionAlone() = runBlocking {
        val id = started()
        val before = requireNotNull(database.courseRepository().findPlan(id)).revision
        val model = model(id)

        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            model.edit(open.form.copy(title = "Нурофен-форте", note = "после еды"))
            model.save()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isSaved }
        }

        val record = requireNotNull(database.courseRepository().findRecord(id))
        assertEquals("Нурофен-форте", record.title)
        assertEquals("после еды", record.note)
        assertEquals(before, requireNotNull(database.courseRepository().findPlan(id)).revision)
    }

    /** Единицу под подключёнными пачками не сменить — сказано словами у своего поля (D5). */
    @Test
    fun changingTheUnitUnderAttachedBoxesIsRefused() = runBlocking {
        val id = started()
        val model = model(id)

        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            val millilitres = open.units.first { it.name == "мл" }
            model.edit(open.form.copy(unit = millilitres))
            model.save()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.error != null }
        }

        val state = model.state.value as CourseFormUiState.Editing
        assertEquals(
            com.kert0n.medapp.domain.course.CourseRejected.Reason.UNIT_MISMATCH,
            (state.error as com.kert0n.medapp.presentation.course.CourseFormError.Rejected).reason
        )
    }

    private companion object {
        val PATIENTLY: Duration = 15.seconds
    }
}
