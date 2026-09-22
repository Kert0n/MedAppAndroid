package com.kert0n.medapp.ui.intake

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.intake.IntakeCardPresentationDTO
import com.kert0n.medapp.presentation.intake.IntakeCardUiState
import com.kert0n.medapp.presentation.intake.IntakeSourcePresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Карточка пункта (PLAN H3 №18): что человек видит и что нажимает. Что при этом записывается,
 * проверяет `IntakeAnsweringTest`.
 */
@RunWith(AndroidJUnit4::class)
class IntakeCardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val NUROFEN = Uuid.parse("00000000-0000-4000-8000-0000000000b1")
    private val IBUPROFEN = Uuid.parse("00000000-0000-4000-8000-0000000000b2")

    private var confirmed = 0
    private var declined = 0

    private fun show(state: IntakeCardUiState) {
        compose.setContent {
            MedAppTheme {
                IntakeCardScreen(
                    state = state,
                    onEdit = {},
                    onConfirm = { confirmed++ },
                    onDecline = { declined++ },
                    onBack = {}
                )
            }
        }
    }

    private fun waiting(
        answer: IntakeCardUiState.Answer? = null,
        answeredAt: LocalTime? = null
    ) = IntakeCardUiState(
        title = "Нурофен",
        plannedOn = LocalDate.of(2027, 3, 10),
        plannedAt = LocalTime.of(9, 0),
        packageId = NUROFEN,
        packageName = "Нурофен",
        sources = listOf(
            IntakeSourcePresentationDTO(NUROFEN, "Нурофен"),
            IntakeSourcePresentationDTO(IBUPROFEN, "Ибупрофен")
        ),
        plannedAmount = QuantityPresentationDTO("2", TABLETS.toPresentationDTO()),
        unit = TABLETS.toPresentationDTO(),
        form = IntakeCardPresentationDTO("2", LocalDate.of(2027, 3, 10), LocalTime.of(9, 12)),
        answer = answer,
        answeredAt = answeredAt
    )

    /** Назначенное видно: по нему человек и узнаёт пункт, к которому пришёл. */
    @Test
    fun theCardNamesWhatWasPrescribed() {
        show(waiting())

        compose.onNodeWithText("Назначено на 10.03.2027 в 09:00: 2 таблетка").assertIsDisplayed()
        // Коробка выбирается из источников лечения, и плановая стоит выбранной. Имя «Нурофен»
        // здесь и в заголовке, и в поле, поэтому спрашивается само поле, а не текст вообще.
        compose.onNodeWithText("Откуда принять").assertIsDisplayed()
        compose.onAllNodesWithText("Нурофен").assertCountEquals(2)
        compose.onNodeWithText("Принял").performClick()
        assertEquals(1, confirmed)
    }

    /** Отказ — такое же решение, как приём, и стоит он рядом (PLAN D6). */
    @Test
    fun theRefusalStandsNextToTheConfirmation() {
        show(waiting())

        compose.onNodeWithText("Пропустил").performClick()

        assertEquals(1, declined)
        assertEquals(0, confirmed)
    }

    /** Отвеченный пункт карточка показывает, а не спрашивает: кнопки ответа у него нет. */
    @Test
    fun anAnsweredItemHasNoAnswerButton() {
        show(waiting(answer = IntakeCardUiState.Answer.TAKEN, answeredAt = LocalTime.of(9, 12)))

        compose.onNodeWithText("принят в 09:12").assertIsDisplayed()
        compose.onNodeWithText("Принял").assertDoesNotExist()
        compose.onNodeWithText("Пропустил").assertDoesNotExist()
        // Выбирать отвеченному нечего: он говорит, откуда взяли на самом деле.
        compose.onNodeWithText("Из: Нурофен").assertIsDisplayed()
    }

    /** Пункта больше нет — сказано словами: пустая карточка читается как поломка. */
    @Test
    fun aVanishedItemSaysSo() {
        show(IntakeCardUiState(isGone = true))

        compose.onNodeWithText("Этого приёма больше нет").assertIsDisplayed()
    }
}
