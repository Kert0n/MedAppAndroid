package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.feature.course.CourseActivation
import com.kert0n.medapp.feature.course.CourseCalendar
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.course.CourseFollowing
import com.kert0n.medapp.feature.notification.ReminderPromising
import com.kert0n.medapp.feature.notification.ReminderWithdrawal
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.QuietNotificationSettings
import com.kert0n.medapp.fixture.UnaskedIntakes
import com.kert0n.medapp.fixture.UnaskedReminders
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourseStorage
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeVocabulary
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.value.Doses
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
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
            activation = activation(transactions),
            courses = courses,
            vocabulary = FakeVocabulary(),
            courseId = courseId
        )

    /**
     * Начало лечения редактору черновика доступно, но здесь не проверяется: оно перестраивает
     * календарь и брони, и над подделками об этом судить нельзя — это делает
     * `CourseFormActivationTest` над Room. Соседи сценария поэтому — подделки, которые падают,
     * если их всё-таки позвать.
     */
    private fun activation(transactions: Transactions): CourseActivation {
        val calendar = CourseCalendar(
            UnaskedIntakes,
            packages,
            ReminderPromising(UnaskedReminders, QuietNotificationSettings, transactions),
            ReminderWithdrawal(UnaskedReminders, transactions)
        )
        return CourseActivation(
            courses,
            packages,
            calendar,
            CourseFollowing(courses, packages, calendar, QueueService(transactions, FakeQueue()), transactions),
            transactions,
            clock
        )
    }

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

        val state = watching(model.state) { state ->
            model.askToDiscard()
            state.awaiting { it is CourseFormUiState.Editing }
        }

        assertEquals(false, (state as CourseFormUiState.Editing).asksToDiscard)
    }

    /**
     * Черновик пишется по частям: заполненное разобрано и записано, пустое не отправлено —
     * доза есть, расписания нет, и это не отказ (D5 «Черновик»).
     */
    @Test
    fun aPartlyFilledPrescriptionIsWrittenPartByPart() {
        val model = viewModel()

        watching(model.state) { state ->
            model.edit(CourseFormPresentationDTO(title = "Нурофен", doseAmount = "2", unit = TABLETS.toPresentationDTO(), totalDoses = "7"))
            model.save()
            state.awaiting { it is CourseFormUiState.Editing && it.isSaved }
        }

        val draft = courses.drafts.values.single()
        assertEquals(dose("2"), draft.dose)
        assertEquals(Doses(7), draft.totalDoses)
        assertEquals(null, draft.schedule)
        assertEquals(null, draft.form)
    }

    /** Отказ разбора называет поле: человек не ищет ошибку глазами. */
    @Test
    fun aRefusalNamesItsField() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.edit(CourseFormPresentationDTO(title = "Нурофен", start = LocalDate.of(2027, 3, 1)))
            model.save()
            state.awaiting { it is CourseFormUiState.Editing && it.error != null }
        }

        assertEquals(CourseFormError.Input.DAYS_EMPTY, (state as CourseFormUiState.Editing).error)
        assertEquals(CourseFormError.Field.DAYS, state.error?.field)
        assertTrue(courses.drafts.isEmpty())
    }

    /** Отказ сценария показан по месту: единицу под подключёнными пачками не сменить. */
    @Test
    fun aRejectionFromTheScenarioIsShownAtItsField() {
        packages.lying(pack(id = PACK, form = TABLET_FORM))
        courses.holding(course(id = COURSE, title = "Нурофен", dose = dose("2"), form = TABLET_FORM, sources = listOf(source(pack(id = PACK, form = TABLET_FORM), 3))))
        val model = viewModel(courseId = COURSE)

        val state = watching(model.state) { state ->
            state.awaiting { it is CourseFormUiState.Editing }
            val form = (state.value as CourseFormUiState.Editing).form
            model.edit(form.copy(doseAmount = "5", unit = MILLILITRES.toPresentationDTO()))
            model.save()
            state.awaiting { it is CourseFormUiState.Editing && !it.isSaving }
        }

        assertEquals(CourseFormError.Rejected(CourseRejected.Reason.UNIT_MISMATCH), (state as CourseFormUiState.Editing).error)
        assertEquals(CourseFormError.Field.DOSE, state.error?.field)
        assertEquals(dose("2"), courses.drafts.getValue(COURSE).dose)
    }

    /** Ожидаемый конец считается при вводе: «дата конца» читается, а не вводится. */
    @Test
    fun theExpectedEndFollowsTheTyping() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            model.edit(
                CourseFormPresentationDTO(
                    title = "Нурофен",
                    start = LocalDate.of(2027, 3, 1),
                    days = DayOfWeek.entries.toSet(),
                    times = listOf(LocalTime.of(9, 0)),
                    totalDoses = "3",
                    zone = MOSCOW
                )
            )
            state.awaiting { it is CourseFormUiState.Editing && it.expectedEnd != null }
        }

        assertEquals(LocalDate.of(2027, 3, 3), (state as CourseFormUiState.Editing).expectedEnd)
    }
}
