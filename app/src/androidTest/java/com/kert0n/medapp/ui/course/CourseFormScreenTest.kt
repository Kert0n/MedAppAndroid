package com.kert0n.medapp.ui.course

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.presentation.course.CourseFormError
import com.kert0n.medapp.presentation.course.CourseFormPresentationDTO
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Редактор лечения (PLAN H3 №15): что человек видит и что может нажать. Что при этом
 * записывается, проверяет `CourseFormViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class CourseFormScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved = 0
    private var asked = 0
    private var confirmed = 0
    private var left = 0
    private var kept = 0

    private fun show(state: CourseFormUiState) {
        compose.setContent {
            MedAppTheme {
                CourseFormScreen(
                    state = state,
                    onEdit = { edited = it },
                    onSave = { saved++ },
                    onAskToDiscard = { asked++ },
                    onConfirmDiscard = { confirmed++ },
                    onDismissDiscard = {},
                    onBack = { left++ },
                    onKeep = { kept++ }
                )
            }
        }
    }

    private var edited: CourseFormPresentationDTO? = null

    private fun editing(
        mode: CourseFormUiState.Mode = CourseFormUiState.Mode.NEW_DRAFT,
        error: CourseFormError? = null,
        asksToDiscard: Boolean = false,
        asksToLeave: Boolean = false,
        form: CourseFormPresentationDTO = CourseFormPresentationDTO(title = "Нурофен"),
        expectedEnd: LocalDate? = null,
        stored: CourseDraftProjection? = null
    ) = CourseFormUiState.Editing(
        form,
        mode,
        stored = stored,
        error = error,
        asksToDiscard = asksToDiscard,
        asksToLeave = asksToLeave,
        expectedEnd = expectedEnd
    )

    /**
     * Кнопка не гаснет: нажатие с пустым названием — названная причина, а не молчание. Отказ и
     * кнопка стоят подвалом, и долистывать до них больше не приходится (H3 «Дизайн»).
     */
    @Test
    fun theSaveButtonStaysAliveAndTheRefusalNamesItsField() {
        show(editing(error = CourseFormError.Input.TITLE_EMPTY))

        // На 360 dp и при крупном шрифте отказ лежит ниже сгиба: до него долистывают, как и до кнопки.
        compose.onNodeWithText("Название нужно: без него лечение не отличить от других.").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(1, saved)
    }

    /** Пустому названию сказано, что оно обязательно, — до всякого нажатия и отказа. */
    @Test
    fun anEmptyTitleIsToldThatItIsRequired() {
        show(editing(form = CourseFormPresentationDTO(title = "")))

        compose.onNodeWithText("обязательно").performScrollTo().assertIsDisplayed()
    }

    /**
     * У набранного названия подписи нет: она читалась бы как требование поправить готовое поле, а
     * поверх собственного отказа «слишком длинное» — как неверная причина. Поле говорит о том, что
     * в нём сейчас.
     */
    @Test
    fun aFilledTitleIsNotAskedToBeFilled() {
        show(editing(form = CourseFormPresentationDTO(title = "Нурофен"), error = CourseFormError.Input.TITLE_TOO_LONG))

        compose.onNodeWithText("обязательно").assertDoesNotExist()
    }

    /** Дни — плашки по одной на день; нажатие отдаёт форму с этим днём, повторное — без него. */
    @Test
    fun daysOfTheWeekAreChipsThatToggle() {
        show(editing(form = CourseFormPresentationDTO(title = "Нурофен", days = setOf(DayOfWeek.MONDAY))))

        compose.onNodeWithText("вт").performScrollTo().performClick()
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), edited?.days)

        compose.onNodeWithText("пн").performClick()
        assertEquals(emptySet<DayOfWeek>(), edited?.days)
    }

    /** Время снимается крестиком на плашке, а новое приходит из часов — строкой его не печатают. */
    @Test
    fun timesAreChipsAndANewOneComesFromTheClock() {
        show(editing(form = CourseFormPresentationDTO(title = "Нурофен", times = listOf(LocalTime.of(9, 0)))))

        compose.onNodeWithText("09:00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Убрать 09:00").performClick()
        assertEquals(emptyList<LocalTime>(), edited?.times)

        compose.onNodeWithText("Добавить время").performClick()
        compose.onNodeWithText("Выбрать").performClick()
        assertEquals(listOf(LocalTime.of(9, 0)), edited?.times)
    }

    /** «Дата конца» читается под числом приёмов, а не вводится (C1 «Курс резиновый»). */
    @Test
    fun theExpectedEndIsReadUnderTheCount() {
        show(editing(expectedEnd = LocalDate.of(2027, 3, 4)))

        compose.onNodeWithText("Последний приём — 04.03.2027").performScrollTo().assertIsDisplayed()
    }

    /** Отказ сценария — словами у поля: чего не хватает, чтобы начать. */
    @Test
    fun aScenarioRefusalIsSaidInWords() {
        show(editing(error = CourseFormError.Rejected(CourseRejected.Reason.SCHEDULE_MISSING)))

        compose.onNodeWithText("Чтобы начать, укажите расписание.").assertIsDisplayed()
    }

    /**
     * Пропавший черновик назван словами, а не показан пустой формой: заполнив её, человек нажал бы
     * «Сохранить» и не понял, куда делась правка (наследство разбора #16).
     */
    @Test
    fun aDraftThatIsGoneIsToldAboutInsteadOfAnEmptyForm() {
        show(CourseFormUiState.Gone)

        compose.onNodeWithText("Этого черновика больше нет.").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertDoesNotExist()
    }

    /** Новому черновику удалять нечего: меню появляется у записанного. */
    @Test
    fun onlyAStoredDraftHasSomethingToDiscard() {
        show(editing(mode = CourseFormUiState.Mode.NEW_DRAFT))

        compose.onNodeWithText("Новое лечение").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ещё").assertDoesNotExist()
    }

    /**
     * Удаление спрашивается до сценария: пункт меню только открывает вопрос, и лишь ответ в нём
     * зовёт удаление.
     *
     * Красная проверка: звать удаление из меню — черновик пропадёт без вопроса.
     */
    @Test
    fun discardingIsAskedBeforeItHappens() {
        show(editing(mode = CourseFormUiState.Mode.DRAFT))

        compose.onNodeWithText("Черновик лечения").assertIsDisplayed()
        compose.onNodeWithContentDescription("Удалить черновик").performClick()

        assertEquals(1, asked)
        assertEquals(0, confirmed)
    }

    /**
     * Удаление черновика — **значок в панели**, а не меню: за тремя точками пряталось одно
     * действие, и оно стоило человеку двух нажатий вместо одного (просьба владельца 2026-09-16).
     * Так же устроена карточка коробки: одно действие — один значок со своим именем.
     */
    @Test
    fun discardingADraftIsOneTapInThePanel() {
        show(editing(mode = CourseFormUiState.Mode.DRAFT))

        compose.onNodeWithContentDescription("Ещё").assertDoesNotExist()
        compose.onNodeWithContentDescription("Удалить черновик").performClick()

        assertEquals(1, asked)
    }

    /**
     * Вопрос об удалении называет, что пропадёт: «удалить?» без последствий человек читает как
     * «закрыть», и записанное врачом назначение уходит молча (H3, список подтверждений).
     */
    @Test
    fun theQuestionNamesWhatWillBeLost() {
        show(editing(mode = CourseFormUiState.Mode.DRAFT, asksToDiscard = true))

        compose.onNodeWithText("Удалить черновик?").assertIsDisplayed()
        compose.onNodeWithText("Записанное назначение и заметка пропадут. Препаратов он не занимал.").assertIsDisplayed()
        compose.onNodeWithText("Удалить черновик").performClick()

        assertEquals(1, confirmed)
    }

    /**
     * Черновик записан ради источников — уход спрашивает, и оба ответа названы словом, а не
     * «да/нет»: человек выбирает судьбу записи, а не подтверждает действие.
     */
    @Test
    fun leavingADraftWrittenForItsSourcesOffersToKeepOrDelete() {
        show(editing(mode = CourseFormUiState.Mode.UNASKED_DRAFT, asksToLeave = true))

        compose.onNodeWithText("Оставить черновик?").assertIsDisplayed()
        compose.onNodeWithText("Удалить").performClick()

        assertEquals(1, confirmed)
    }

    /** «Оставить» уносит с формы, ничего не удаляя. */
    @Test
    fun keepingTheDraftLeavesTheForm() {
        show(editing(mode = CourseFormUiState.Mode.UNASKED_DRAFT, asksToLeave = true))

        compose.onNodeWithText("Оставить").performClick()

        assertEquals(1, kept)
        assertEquals(0, confirmed)
    }

    /** «Отмена» — такой же выход, как стрелка: спрашивает о нём форма, а не оболочка. */
    @Test
    fun cancelLeavesThroughTheForm() {
        show(editing(mode = CourseFormUiState.Mode.UNASKED_DRAFT))

        compose.onNodeWithText("Отмена").performClick()

        assertEquals(1, left)
    }
}
