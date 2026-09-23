package com.kert0n.medapp.presentation.plan

import com.kert0n.medapp.presentation.intake.IntakeQuestionPresentationDTO
import com.kert0n.medapp.presentation.value.ExpiryDatePresentationDTO
import kotlin.uuid.Uuid
import org.junit.Assert.assertFalse
import org.junit.Test

/** Когда попапу пропущенных есть что сказать (PLAN H3 №12). */
class MissedIntakesUiStateTest {

    /**
     * Вопрос о просроченной коробке, на который человек ещё не ответил, — это сказанное. Без
     * правила строка, ушедшая, пока вопрос открыт, делала попап «пустым»: оболочка отдавала место
     * другому попапу, и вопрос оставался висеть невидимым до следующей строки.
     */
    @Test
    fun `открытый вопрос держит попап`() {
        val asked = DayQuestion(
            intakeId = Uuid.random(),
            questions = listOf(IntakeQuestionPresentationDTO.Expired("Ферретаб", ExpiryDatePresentationDTO("09.2027")))
        )

        assertFalse(MissedIntakesUiState(question = asked).isEmpty)
    }
}
