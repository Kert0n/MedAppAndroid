package com.kert0n.medapp.ui.intake

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
import java.time.LocalDate
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
        questions: List<IntakeQuestionPresentationDTO> = emptyList()
    ) = UnplannedIntakeUiState(
        packageName = "Нурофен",
        unit = TABLETS.toPresentationDTO(),
        availableToMe = QuantityPresentationDTO("20", TABLETS.toPresentationDTO()),
        form = UnplannedIntakePresentationDTO(amount),
        error = error,
        questions = questions
    )

    /**
     * Вопрос сценария лист задаёт **тем же диалогом**, что и карточка пункта: спрашивает не экран,
     * а сценарий, и человек читает об этом одинаково, куда бы ни пришёл (PLAN D6, H3 №10).
     */
    @Test
    fun theQuestionIsAskedByTheSameDialogAsOnTheCard() {
        show(taking(questions = listOf(IntakeQuestionPresentationDTO.Expired(LocalDate.of(2027, 3, 1)))))

        compose.onNodeWithText("Прежде чем записать").assertIsDisplayed()
        compose.onNodeWithText("• Коробка просрочена: годна до 01.03.2027").assertIsDisplayed()
        compose.onNodeWithText("Всё равно принял").performClick()

        assertEquals(1, acknowledged)
    }

    /** Доза стоит в поле подсказкой, а рядом сказано, сколько в коробке моего: по нему и решают. */
    @Test
    fun theHintAndWhatIsMineAreBothOnTheSheet() {
        show(taking())

        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("Доступно мне: 20 таблетка").assertIsDisplayed()
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

        compose.onNodeWithText("В коробке столько не наберётся.").assertIsDisplayed()
        compose.onNodeWithText("Принять").performClick()

        assertEquals(1, recorded)
    }
}
