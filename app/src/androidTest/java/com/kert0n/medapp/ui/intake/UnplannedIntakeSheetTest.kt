package com.kert0n.medapp.ui.intake

import com.kert0n.medapp.presentation.value.ExpiryDatePresentationDTO
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.intake.UnplannedIntakeError
import com.kert0n.medapp.presentation.intake.IntakeQuestionPresentationDTO
import com.kert0n.medapp.presentation.intake.UnplannedIntakePresentationDTO
import com.kert0n.medapp.presentation.intake.UnplannedIntakeUiState
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Лист разового приёма (PLAN H3 №10): что человек видит и что нажимает. Что при этом записывается,
 * проверяет `UnplannedIntakeViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class UnplannedIntakeSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private var edited: UnplannedIntakePresentationDTO? = null
    private var recorded = 0
    private var acknowledged = 0
    private var dismissed = 0

    private fun show(state: UnplannedIntakeUiState) {
        compose.setContent {
            MedAppTheme {
                UnplannedIntakeSheet(
                    state = state,
                    onEdit = { edited = it },
                    onRecord = { recorded++ },
                    onAcknowledge = { acknowledged++ },
                    onDismissQuestions = { dismissed++ },
                    onDismiss = {}
                )
            }
        }
    }

    private fun taking(
        amount: String = "2",
        error: UnplannedIntakeError? = null,
        questions: List<IntakeQuestionPresentationDTO> = emptyList(),
        expired: ExpiryDatePresentationDTO? = null
    ) = UnplannedIntakeUiState(
        packageName = "Нурофен",
        unit = TABLETS.toPresentationDTO(),
        free = QuantityPresentationDTO("11", TABLETS.toPresentationDTO()),
        inTheBox = QuantityPresentationDTO("15", TABLETS.toPresentationDTO()),
        form = UnplannedIntakePresentationDTO(amount),
        error = error,
        questions = questions,
        expired = expired
    )

    /**
     * Вопрос сценария лист задаёт диалогом со списком: спрашивает не экран, а сценарий, и
     * «всё равно принял» — тот же вызов с подтверждением (PLAN D6, H3 №10).
     */
    @Test
    fun theQuestionIsAskedByTheSameDialogAsOnTheCard() {
        show(taking(questions = listOf(IntakeQuestionPresentationDTO.TouchesReserved(QuantityPresentationDTO("1", TABLETS.toPresentationDTO())))))

        compose.onNodeWithText("Прежде чем записать").assertIsDisplayed()
        compose.onNodeWithText("• Приём заденет занятое: свободно 1 таблетка").assertIsDisplayed()
        compose.onNodeWithText("Всё равно принял").performClick()

        assertEquals(1, acknowledged)
    }

    /**
     * Просроченную коробку видно **до** нажатия: срок стоит на листе значком и словами, когда
     * вопроса ещё нет, а «Принять» остаётся живым и зовёт сценарий — спросить о сроке его дело
     * (ТЗ 4.1.1.5.5). Без этого человек узнаёт о просрочке, уже решив принять.
     */
    @Test
    fun anExpiredBoxShowsItsTermBeforeAnyQuestion() {
        show(taking(expired = ExpiryDatePresentationDTO("14.08.2026")))

        compose.onNodeWithText("Просрочен 14.08.2026").assertIsDisplayed()
        compose.onNodeWithText("Прежде чем записать").assertDoesNotExist()
        compose.onNodeWithText("Принять").performClick()

        assertEquals(1, recorded)
    }

    /**
     * Вопрос о сроке называет и коробку, и срок, и «всё равно принял» уходит тем же
     * подтверждением. Узкий экран и крупный шрифт не прячут ни вопрос, ни кнопку. Без этого
     * человек соглашается, не зная, о какой коробке его спросили.
     */
    @Test
    fun theQuestionAboutAnExpiredBoxNamesTheBoxAndItsTerm() {
        val term = ExpiryDatePresentationDTO("14.08.2026")
        show(taking(expired = term, questions = listOf(IntakeQuestionPresentationDTO.Expired("Нурофен", term))))

        compose.onNodeWithText("• «Нурофен» просрочен: годен до 14.08.2026").assertIsDisplayed()
        compose.onNodeWithText("Всё равно принял").assertIsDisplayed().performClick()

        assertEquals(1, acknowledged)
    }

    /**
     * Доза стоит в поле подсказкой, а рядом — **то же число, по которому судит сценарий**:
     * свободное из всего, что в коробке. Покажи «доступно мне» — человек прочтёт число, с которого
     * вопрос «заденет занятое» уже начался, и удивится вопросу (замечание владельца 2026-09-16).
     */
    @Test
    fun theHintAndWhatIsFreeOfTheWholeBoxAreBothOnTheSheet() {
        show(taking())

        compose.onNodeWithText("2").assertIsDisplayed()
        // Показано то же число, по которому судит сценарий: «свободно», а не «доступно мне» —
        // своя бронь в «доступно» входила, и человек читал обещание, которого нет.
        compose.onNodeWithText("Свободно 11 из 15 таблетка").assertIsDisplayed()
    }

    /** Набранное уходит в состояние: подсказку человек переписывает, а не обходит. */
    @Test
    fun whatIsTypedLeavesTheField() {
        show(taking())

        compose.onNodeWithText("2").performTextReplacement("1.5")

        assertEquals(UnplannedIntakePresentationDTO("1.5"), edited)
    }

    /** Кнопка не гаснет: нажатие с плохим числом — названная причина, а не молчание. */
    @Test
    fun theButtonStaysAliveAndTheRefusalIsInWords() {
        show(taking(error = UnplannedIntakeError.Rejected(IntakeRejected.Reason.INSUFFICIENT)))

        compose.onNodeWithText("Столько здесь не наберётся.").assertIsDisplayed()
        compose.onNodeWithText("Принять").performClick()

        assertEquals(1, recorded)
    }
}
