package com.kert0n.medapp.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLETS_ID
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.TABLET_FORM_ID
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.course.CourseFormPresentationDTO
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.presentation.course.CourseFormViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Уход из редактора лечения (PLAN H3 №15). Чтобы подключить источники, новый черновик приходится
 * записать — номер нужен коробкам. Записан он **не по просьбе человека**: он нажимал «Источники»,
 * а не «Сохранить». Поэтому уход с формы спрашивает, оставить ли записанное, а открытый из списка
 * черновик — существовавший и до редактора — уходит молча.
 */
@RunWith(AndroidJUnit4::class)
class CourseFormLeavingTest {

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

    private fun model(courseId: Uuid?) = CourseFormViewModel(
        drafting = scenarios.courseDrafting,
        activation = scenarios.courseActivation,
        amendment = scenarios.courseAmendment,
        renaming = scenarios.courseRenaming,
        courses = database.courseRepository(),
        vocabulary = FakeVocabulary(),
        courseId = courseId
    ).also { opened += it }

    private suspend fun drafts() = database.courseRepository().observeDrafts().first()

    /** Дойти до состояния, в котором черновик записан ради источников, и отдать его номер. */
    private suspend fun CourseFormViewModel.wroteForSources(
        fill: (CourseFormUiState.Editing) -> CourseFormPresentationDTO = { it.form.copy(title = "Нурофен") }
    ): Uuid {
        var id: Uuid? = null
        watching(state) { state ->
            // Ждём и словарь: без единиц и форм заполнять назначение нечем.
            val open = state.awaiting(PATIENTLY) {
                it is CourseFormUiState.Editing && it.units.isNotEmpty() && it.forms.isNotEmpty()
            } as CourseFormUiState.Editing
            edit(fill(open))
            openSources()
            val written = state.awaiting(PATIENTLY) {
                it is CourseFormUiState.Editing && it.sourcesOf != null
            } as CourseFormUiState.Editing
            sourcesOpened()
            id = written.sourcesOf
        }
        return requireNotNull(id)
    }

    /** Человек не просил записывать — уход спрашивает, а не решает за него. */
    @Test
    fun aDraftWrittenForItsSourcesAsksBeforeTheEditorIsLeft() = runBlocking {
        val model = model(null)
        val id = model.wroteForSources()

        val state = watching(model.state) { state ->
            model.leave()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.asksToLeave } as CourseFormUiState.Editing
        }

        assertFalse("вопрос задан — уходить рано", state.isLeft)
        assertNotNull("до ответа черновик цел", database.courseRepository().findDraft(id))
    }

    /** «Удалить» уносит записанное: человек заводил лечение, а не строку в списке. */
    @Test
    fun theQuestionDiscardsTheDraftAndLeaves() = runBlocking {
        val model = model(null)
        val id = model.wroteForSources()

        watching(model.state) { state ->
            model.leave()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.asksToLeave }
            model.discard()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isDiscarded }
        }

        assertNull(database.courseRepository().findDraft(id))
        assertEquals(emptyList<Any>(), drafts())
    }

    /** «Оставить» — черновик остаётся записанным, как всякий другой. */
    @Test
    fun keepingLeavesTheDraftWhereItWillBeFound() = runBlocking {
        val model = model(null)
        val id = model.wroteForSources()

        val state = watching(model.state) { state ->
            model.leave()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.asksToLeave }
            model.keep()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isLeft } as CourseFormUiState.Editing
        }

        assertFalse(state.asksToLeave)
        assertNotNull(database.courseRepository().findDraft(id))
    }

    /** Открытый из списка черновик существовал и до редактора: уход его не трогает и не спрашивает. */
    @Test
    fun anOpenedDraftIsLeftWithoutAQuestion() = runBlocking {
        val created = scenarios.courseDrafting.create("Нурофен")
        val model = model(created.id)

        val state = watching(model.state) { state ->
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing }
            model.leave()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isLeft } as CourseFormUiState.Editing
        }

        assertFalse("черновик записан до редактора — спрашивать не о чем", state.asksToLeave)
        assertNotNull(database.courseRepository().findDraft(created.id))
    }

    /** Записанный ради источников — уже записан: «Сохранить» правит его, а не заводит второй. */
    @Test
    fun savingAfterSourcesKeepsOneDraft() = runBlocking {
        val model = model(null)
        val id = model.wroteForSources()

        watching(model.state) { state ->
            val open = state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing } as CourseFormUiState.Editing
            model.edit(open.form.copy(note = "по 2 после еды"))
            model.save()
            state.awaiting(PATIENTLY) { it is CourseFormUiState.Editing && it.isSaved }
        }

        assertEquals(1, drafts().size)
        assertEquals("по 2 после еды", requireNotNull(database.courseRepository().findDraft(id)).note)
    }

    /** «Начать» начинает **то** лечение, к которому подключены коробки, а не свежую копию. */
    @Test
    fun startingAfterSourcesBeginsTheCourseThatHoldsThem() = runBlocking {
        val model = model(null)
        val id = model.wroteForSources { open ->
            open.form.copy(
                title = "Нурофен",
                doseAmount = "2",
                unit = open.units.first { it.id == TABLETS_ID },
                form = open.forms.first { it.id == TABLET_FORM_ID },
                totalDoses = "4",
                start = start,
                days = DayOfWeek.entries.toSet(),
                times = listOf(LocalTime.of(9, 0))
            )
        }
        // Коробку подключил экран источников — тем же сценарием, что и он.
        val draft = requireNotNull(database.courseRepository().findDraft(id))
        scenarios.courseDrafting.edit(id, draft.revision, listOf(CourseDrafting.Edit.Attach(PACK, Doses(4))))

        val state = watching(model.state) { state ->
            // Редактор узнаёт о коробке сам: её подключили на соседнем экране, к тому же черновику.
            state.awaiting(PATIENTLY) {
                it is CourseFormUiState.Editing && it.stored?.sources?.isNotEmpty() == true
            }
            model.start()
            state.awaiting(PATIENTLY) {
                it is CourseFormUiState.Editing && (it.startedId != null || it.error != null)
            } as CourseFormUiState.Editing
        }

        assertEquals("начато лечение с источниками, отказ: ${state.error}", id, state.startedId)
        assertTrue(drafts().isEmpty())
        val plan = requireNotNull(database.courseRepository().findPlan(id))
        assertEquals(listOf(PACK), plan.sources.map { it.pkg.id })
    }

    private companion object {
        val PATIENTLY: Duration = 15.seconds
    }
}
