package com.kert0n.medapp.ui.course

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.course.CourseFormError
import com.kert0n.medapp.presentation.course.CourseFormPresentationDTO
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.ui.theme.MedAppTheme
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

    private fun show(state: CourseFormUiState) {
        compose.setContent {
            MedAppTheme {
                CourseFormScreen(
                    state = state,
                    onEdit = {},
                    onSave = { saved++ },
                    onAskToDiscard = { asked++ },
                    onConfirmDiscard = { confirmed++ },
                    onDismissDiscard = {},
                    onBack = {}
                )
            }
        }
    }

    private fun editing(mode: CourseFormUiState.Mode = CourseFormUiState.Mode.NEW_DRAFT, error: CourseFormError? = null, asksToDiscard: Boolean = false) =
        CourseFormUiState.Editing(CourseFormPresentationDTO(title = "Нурофен"), mode, error = error, asksToDiscard = asksToDiscard)

    /** Кнопка не гаснет: нажатие с пустым названием — названная причина, а не молчание. */
    @Test
    fun theSaveButtonStaysAliveAndTheRefusalNamesItsField() {
        show(editing(error = CourseFormError.Input.TITLE_EMPTY))

        compose.onNodeWithText("Название нужно: без него лечение не отличить от других.").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals(1, saved)
    }

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
        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithText("Удалить черновик").performClick()

        assertEquals(1, asked)
        assertEquals(0, confirmed)
    }

    @Test
    fun theQuestionNamesWhatWillBeLost() {
        show(editing(mode = CourseFormUiState.Mode.DRAFT, asksToDiscard = true))

        compose.onNodeWithText("Удалить черновик?").assertIsDisplayed()
        compose.onNodeWithText("Записанное назначение и заметка пропадут. Пачки он не занимал.").assertIsDisplayed()
        compose.onNodeWithText("Удалить черновик").performClick()

        assertEquals(1, confirmed)
    }
}
