package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourseStorage
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.HeldTransactions
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Редактор лечения в режиме черновика (PLAN H3 №15, U3): что записывается и что человек видит
 * в ответ. Как это нарисовано, проверяет `CourseFormScreenTest`.
 */
class CourseFormViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val courses = FakeCourseStorage()

    private val packages = FakePackages()

    private fun viewModel(courseId: Uuid? = null, transactions: Transactions = DirectTransactions) =
        CourseFormViewModel(
            drafting = CourseDrafting(courses, packages, transactions, clock),
            courses = courses,
            courseId = courseId
        )

    /** Черновик с одним названием и заметкой — законная запись: «записал у врача, куплю завтра» (D5). */
    @Test
    fun aDraftIsWrittenWithATitleAndANoteAlone() {
        val model = viewModel()

        watching(model.state) { state ->
            model.edit(CourseFormPresentationDTO(title = "Нурофен", note = "по 2 после еды"))
            model.save()
            state.awaiting { it is CourseFormUiState.Editing && it.isSaved }
        }

        val draft = courses.drafts.values.single()
        assertEquals("Нурофен", draft.title)
        assertEquals("по 2 после еды", draft.note)
        assertEquals(null, draft.schedule)
        assertTrue(draft.sources.isEmpty())
    }

    @Test
    fun aDraftOpensWithWhatIsWrittenAndIsRenamedInPlace() {
        courses.holding(course(id = COURSE, title = "Нурофен"))
        val model = viewModel(courseId = COURSE)

        watching(model.state) { state ->
            state.awaiting { it is CourseFormUiState.Editing }
            assertEquals("Нурофен", (state.value as CourseFormUiState.Editing).form.title)
            model.edit(CourseFormPresentationDTO(title = "Нурофен, неделя", note = "утром"))
            model.save()
            state.awaiting { it is CourseFormUiState.Editing && it.isSaved }
        }

        assertEquals("Нурофен, неделя", courses.drafts.getValue(COURSE).title)
        assertEquals("утром", courses.drafts.getValue(COURSE).note)
        assertEquals(1, courses.drafts.size)
    }

    /** Черновика нет — отказ, а не пустая форма (наследство разбора #16). */
    @Test
    fun aDraftThatIsNotThereIsRefusedRatherThanShownEmpty() {
        val model = viewModel(courseId = Uuid.random())

        val state = watching(model.state) { it.awaiting { s -> s !is CourseFormUiState.Loading } }

        assertEquals(CourseFormUiState.Gone, state)
    }

    @Test
    fun anEmptyTitleIsRefusedAndTypingClearsTheRefusal() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.save()
            state.awaiting { it is CourseFormUiState.Editing && it.error == CourseFormError.Input.TITLE_EMPTY }
            model.edit(CourseFormPresentationDTO(title = "Н"))
            state.awaiting { it is CourseFormUiState.Editing && it.error == null }
        }

        assertEquals(null, (state as CourseFormUiState.Editing).error)
        assertTrue(courses.drafts.isEmpty())
    }

    /**
     * Второе нажатие «Сохранить», пока идёт первое, не заводит второго черновика.
     *
     * Красная проверка: снять замок — черновиков два.
     */
    @Test
    fun aSecondTapDuringTheFirstWritesNothingExtra() {
        val transactions = HeldTransactions()
        val model = viewModel(transactions = transactions)

        watching(model.state) { state ->
            model.edit(CourseFormPresentationDTO(title = "Нурофен"))
            model.save()
            model.save()
            transactions.door.release()
            state.awaiting { it is CourseFormUiState.Editing && it.isSaved }
        }

        assertEquals(1, courses.drafts.size)
    }

    /** Черновик, правленный с другого экрана, не затирается: сказано перечитать (PLAN F5). */
    @Test
    fun aDraftChangedElsewhereIsNotOverwritten() {
        courses.holding(course(id = COURSE, title = "Нурофен"))
        val model = viewModel(courseId = COURSE)

        val state = watching(model.state) { state ->
            state.awaiting { it is CourseFormUiState.Editing }
            courses.holding(course(id = COURSE, title = "Нурофен детский", revision = 3))
            model.edit(CourseFormPresentationDTO(title = "Нурофен, неделя"))
            model.save()
            state.awaiting { it is CourseFormUiState.Editing && !it.isSaving }
        }

        assertEquals(CourseFormError.Stale, (state as CourseFormUiState.Editing).error)
        assertEquals("Нурофен детский", courses.drafts.getValue(COURSE).title)
        assertEquals(Revision(3), courses.drafts.getValue(COURSE).revision)
    }

    /**
     * Удаление спрашивается **до** сценария: «удалить» только открывает вопрос, и лишь ответ
     * в нём убирает черновик.
     *
     * Красная проверка: звать сценарий с кнопки — черновик исчезнет без вопроса.
     */
    @Test
    fun discardingIsAskedBeforeTheScenario() {
        courses.holding(course(id = COURSE, title = "Нурофен"))
        val model = viewModel(courseId = COURSE)

        watching(model.state) { state ->
            state.awaiting { it is CourseFormUiState.Editing }
            model.askToDiscard()
            state.awaiting { it is CourseFormUiState.Editing && it.asksToDiscard }
            assertEquals(1, courses.drafts.size)
            model.discard()
            state.awaiting { it is CourseFormUiState.Editing && it.isDiscarded }
        }

        assertTrue(courses.drafts.isEmpty())
    }

    @Test
    fun aNewDraftCannotBeDiscardedBeforeItExists() {
        val model = viewModel()

        model.askToDiscard()

        assertEquals(false, (model.state.value as CourseFormUiState.Editing).asksToDiscard)
    }
}
