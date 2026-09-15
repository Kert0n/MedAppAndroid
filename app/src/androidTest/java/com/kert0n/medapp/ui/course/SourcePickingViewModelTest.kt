package com.kert0n.medapp.ui.course

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.kert0n.medapp.presentation.course.SourcePickingViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.uuid.Uuid
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
    fun tearDown() = database.close()

    private fun model(courseId: Uuid) = SourcePickingViewModel(
        drafting = scenarios.courseDrafting,
        sources = scenarios.sourceEditing,
        courses = database.courseRepository(),
        packages = database.packageRepository(),
        medKits = database.medKitRepository(),
        today = Today(clock, QuietClock),
        courseId = courseId
    )

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
     * Форму и единицу сверяет назначение: коробка в капсулах под лечение в таблетках не идёт.
     * Порядок — тот, что отдаёт чтение: по названию («Ибупрофен» раньше «Нурофена»).
     */
    @Test
    fun aBoxOfAnotherFormIsNotOffered() = runBlocking {
        val model = model(prescribed())

        val state = watching(model.state) { it.awaiting { state -> state.packages.isNotEmpty() } }

        assertEquals(listOf(OTHER_PACK, PACK), state.packages.map { it.packageId })
    }

    /** Пока доза и форма не названы, сверять не с чем — подключать нечего. */
    @Test
    fun aDraftWithoutADoseOffersNothing() = runBlocking {
        val model = model(draft())

        val state = watching(model.state) { it.awaiting { state -> !state.isLoading } }

        assertTrue(state.packages.isEmpty())
    }

    /** Уже подключённая коробка второй раз не предлагается. */
    @Test
    fun anAttachedBoxIsNotOfferedAgain() = runBlocking {
        val model = model(prescribed(CourseDrafting.Edit.Attach(PACK, Doses(0))))

        val state = watching(model.state) { it.awaiting { state -> state.packages.isNotEmpty() } }

        assertEquals(listOf(OTHER_PACK), state.packages.map { it.packageId })
    }

    /** Коробку держит другое идущее лечение: одна пачка — один активный курс (PLAN D5). */
    @Test
    fun aBoxHeldByAnotherCourseIsNotOffered() = runBlocking {
        val held = prescribed(CourseDrafting.Edit.Attach(PACK, Doses(1)))
        scenarios.courseActivation.activate(held, requireNotNull(database.courseRepository().findDraft(held)).revision)
        val model = model(prescribed())

        val state = watching(model.state) { it.awaiting { state -> state.packages.isNotEmpty() } }

        assertEquals(listOf(OTHER_PACK), state.packages.map { it.packageId })
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
