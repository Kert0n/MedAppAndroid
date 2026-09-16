package com.kert0n.medapp.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.CAPSULE_FORM
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.course.Attachability
import com.kert0n.medapp.presentation.course.SourcePickingViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Выбор источника (PLAN H3 №17): подключить можно ту коробку, которая годится под назначение и
 * никем не занята. Годность решает домен, а не экран.
 */
@RunWith(AndroidJUnit4::class)
class SourcePickingViewModelTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val start: LocalDate = LocalDate.of(2027, 3, 10)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Коробка того же лекарства, но в капсулах: под назначение в таблетках она не годится. */
    private val capsules: Uuid = Uuid.random()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        val packages = database.packageRepository()
        packages.add(pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM))
        packages.add(pack(id = OTHER_PACK, name = "Ибупрофен", quantity = tablets("12"), form = TABLET_FORM))
        packages.add(pack(id = capsules, name = "Нурофен в капсулах", quantity = tablets("10"), form = CAPSULE_FORM))
    }

    @After
    fun tearDown() {
        // Экран закрывают до базы: его чтения живут, пока жив он, и закрытая из-под них база
        // роняет соседнюю проверку, а не эту.
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    /** Что открыто: закрывается в обратном порядке — сначала экраны, потом база. */
    private val opened = mutableListOf<ViewModel>()

    private fun model(courseId: Uuid) = SourcePickingViewModel(
        drafting = scenarios.courseDrafting,
        sources = scenarios.sourceEditing,
        courses = database.courseRepository(),
        packages = database.packageRepository(),
        medKits = database.medKitRepository(),
        today = Today(clock, QuietClock),
        courseId = courseId
    ).also { opened += it }

    private suspend fun draft(vararg edits: CourseDrafting.Edit): Uuid {
        val created = scenarios.courseDrafting.create("Ибупрофен")
        val saved = scenarios.courseDrafting.edit(created.id, created.revision, edits.toList())
        return (saved as CourseDrafting.Outcome.Saved).draft.id
    }

    private suspend fun prescribed(vararg extra: CourseDrafting.Edit): Uuid = draft(
        CourseDrafting.Edit.SetDose(dose("2")),
        CourseDrafting.Edit.SetForm(TABLET_FORM),
        CourseDrafting.Edit.SetSchedule(schedule(start = start)),
        CourseDrafting.Edit.SetTotalDoses(Doses(10)),
        *extra
    )

    /**
     * Неподходящая коробка не прячется: человек ищет именно её и читает, почему она не идёт.
     * Форму и единицу сверяет назначение — коробка в капсулах под лечение в таблетках не годится.
     */
    @Test
    fun aBoxOfAnotherFormStaysInTheListWithItsReason() = runBlocking {
        val model = model(prescribed())

        val state = watching(model.state) { it.awaiting { s -> s.packages.isNotEmpty() } }

        assertEquals(
            Attachability.Mismatch(CourseSource.Fault.FORM_MISMATCH),
            state.packages.first { it.packageId == capsules }.attachability
        )
        assertEquals(Attachability.Attachable, state.packages.first { it.packageId == PACK }.attachability)
    }

    /** Пока доза и форма не названы, сверять не с чем — и это сказано у каждой строки. */
    @Test
    fun aDraftWithoutADoseSaysWhatToFillFirst() = runBlocking {
        val model = model(draft())

        val state = watching(model.state) { it.awaiting { s -> s.packages.isNotEmpty() } }

        assertTrue(state.packages.all { it.attachability == Attachability.PrescriptionIncomplete })
    }

    /** Уже подключённая коробка второй раз не подключается, но видна с причиной. */
    @Test
    fun anAttachedBoxSaysItIsAlreadyHere() = runBlocking {
        val model = model(prescribed(CourseDrafting.Edit.Attach(PACK, Doses(0))))

        val state = watching(model.state) { it.awaiting { s -> s.packages.isNotEmpty() } }

        assertEquals(Attachability.Attached, state.packages.first { it.packageId == PACK }.attachability)
    }

    /** Коробку держит другое лечение — отказ называет то лечение (PLAN D5). */
    @Test
    fun aBoxHeldByAnotherCourseNamesThatCourse() = runBlocking {
        val held = prescribed(CourseDrafting.Edit.Attach(PACK, Doses(1)))
        scenarios.courseActivation.activate(held, requireNotNull(database.courseRepository().findDraft(held)).revision)
        val model = model(prescribed())

        val state = watching(model.state) { it.awaiting { s -> s.packages.isNotEmpty() } }

        assertEquals(
            Attachability.HeldByCourse("Ибупрофен"),
            state.packages.first { it.packageId == PACK }.attachability
        )
    }

    /** У коробки не заполнена форма — сказать, тот ли это препарат, нечем; сначала форма. */
    @Test
    fun aBoxWithoutAFormAsksForItsForm() = runBlocking {
        val formless = Uuid.random()
        database.packageRepository().add(pack(id = formless, name = "Без формы", quantity = tablets("10")))
        val model = model(prescribed())

        val state = watching(model.state) { it.awaiting { s -> s.packages.any { p -> p.packageId == formless } } }

        assertEquals(Attachability.NeedsForm, state.packages.first { it.packageId == formless }.attachability)
    }

    /** Подключается коробка с нулём приёмов: сколько из неё брать, человек решает в стеке. */
    @Test
    fun attachingTakesNoDosesYet() = runBlocking {
        val id = prescribed()
        val model = model(id)

        watching(model.state) { state ->
            state.awaiting { it.packages.isNotEmpty() }
            model.attach(PACK)
            state.awaiting { it.isAttached }
        }

        val stored = requireNotNull(database.courseRepository().findDraft(id))
        assertEquals(listOf(PACK), stored.sources.map { it.pkg.id })
        assertEquals(Doses(0), stored.sources.single().allocatedDoses)
    }
}
