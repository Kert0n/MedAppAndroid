package com.kert0n.medapp.ui.intake

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.intake.IntakeHistoryRowPresentationDTO
import com.kert0n.medapp.presentation.intake.IntakeHistoryUiState
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** История приёмов (PLAN H3 №19): что человек читает. Что в неё попало, проверяет `IntakeHistoryTest`. */
@RunWith(AndroidJUnit4::class)
class IntakeHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: IntakeHistoryUiState) {
        compose.setContent { MedAppTheme { IntakeHistoryScreen(state = state, onBack = {}) } }
    }

    /** Строка говорит, когда и сколько приняли, откуда и чем кончилось — словом, а не значком. */
    @Test
    fun aRowTellsWhenHowMuchAndHowItEnded() {
        show(
            IntakeHistoryUiState(
                title = "Нурофен",
                rows = listOf(
                    IntakeHistoryRowPresentationDTO(
                        id = Uuid.random(),
                        on = LocalDate.of(2027, 3, 10),
                        at = LocalTime.of(9, 12),
                        amount = QuantityPresentationDTO("2", TABLETS.toPresentationDTO()),
                        subject = "Домашняя аптечка",
                        state = IntakeHistoryRowPresentationDTO.State.TAKEN
                    )
                )
            )
        )

        compose.onNodeWithText("2 таблетка").assertIsDisplayed()
        // Когда и откуда — одной строкой: три строки Material 3 прижимает боковое к верху.
        compose.onNodeWithText("10.03.2027 · 09:12 · Домашняя аптечка").assertIsDisplayed()
        compose.onNodeWithText("принят в 09:12").assertIsDisplayed()
    }

    /** Пусто — не поломка: сказано, что отсюда ещё ничего не принимали. */
    @Test
    fun anEmptyHistorySaysSo() {
        show(IntakeHistoryUiState(title = "Нурофен"))

        compose.onNodeWithText("Отсюда ещё ничего не принимали").assertIsDisplayed()
    }
}
