package com.kert0n.medapp.ui.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietClock
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
import com.kert0n.medapp.presentation.intake.IntakeHistoryRowPresentationDTO
import com.kert0n.medapp.presentation.intake.IntakeHistoryViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
 * История приёмов (PLAN H3 №19) над настоящей базой: два входа, два разных ответа — и оба
 * переживают вещь, о которой рассказывают.
 */
@RunWith(AndroidJUnit4::class)
class IntakeHistoryTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(
            pack(id = PACK, name = "Нурофен", quantity = tablets("20"), form = TABLET_FORM)
        )
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun model(courseId: Uuid? = null, packageId: Uuid? = null) = IntakeHistoryViewModel(
        today = Today(clock, QuietClock),
        courses = database.courseRepository(),
        intakes = database.intakeRepository(),
        packages = database.packageRepository(),
        courseId = courseId,
        packageId = packageId
    ).also { opened += it }

    private suspend fun startedAndTaken(): Uuid {
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
        val first = database.intakeRepository().ofCourse(saved.draft.id).filterIsInstance<CourseIntake>().minBy { it.slot.at }
        scenarios.intakeConfirmation.confirm(first.id, PACK, dose("2"), now)
        return saved.draft.id
    }

    /**
     * История лечения называет **коробку**: ради чего открыли — в заголовке, а в строке то, чего
     * не спрашивали. Иначе каждая строка повторяет название лечения, и читать её нечем.
     */
    @Test
    fun theHistoryOfACourseNamesTheBoxEachDoseCameFrom() = runBlocking {
        val courseId = startedAndTaken()

        val state = watching(model(courseId = courseId).state) { it.awaiting(PATIENTLY) { s -> s.rows.isNotEmpty() } }

        assertEquals("Нурофен", state.title)
        val taken = state.rows.first { it.state == IntakeHistoryRowPresentationDTO.State.TAKEN }
        assertEquals("Нурофен", taken.subject)
        assertEquals("2", taken.amount?.amount)
    }

    /**
     * История коробки читается **после её конца**: приём переживает вещь, о которой рассказывает,
     * — имя и единица лежат в самом факте (PLAN D3, D6). Без этого выброшенная коробка уносила бы
     * с собой всё, что из неё принимали.
     */
    @Test
    fun theHistoryOfABoxSurvivesTheBox() = runBlocking {
        val courseId = startedAndTaken()
        // Коробка кончилась и выброшена: карточки у неё уже нет, а приёмы из неё остались.
        scenarios.packageRemoval.remove(PACK)

        val state = watching(model(packageId = PACK).state) { it.awaiting(PATIENTLY) { s -> s.rows.isNotEmpty() } }

        val taken = state.rows.first { it.state == IntakeHistoryRowPresentationDTO.State.TAKEN }
        // Строка называет лечение: ради коробки экран и открыли, повторять её незачем.
        assertEquals("Нурофен", taken.subject)
        assertTrue(database.courseRepository().findRecord(courseId) != null)
    }

    private companion object {
        val PATIENTLY: Duration = 15.seconds
    }
}
