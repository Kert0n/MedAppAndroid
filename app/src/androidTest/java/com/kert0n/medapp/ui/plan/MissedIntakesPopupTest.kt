package com.kert0n.medapp.ui.plan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.plan.DayItemPresentationDTO
import com.kert0n.medapp.presentation.plan.MissedIntakesUiState
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
 * Попап пропущенного (PLAN C1): что человек видит и чем отвечает. Что в нём стоит и что значит
 * крестик для пунктов, проверяет `MissedIntakesTest`.
 */
@RunWith(AndroidJUnit4::class)
class MissedIntakesPopupTest {

    @get:Rule
    val compose = createComposeRule()

    private val confirmed = mutableListOf<Uuid>()
    private val opened = mutableListOf<DayItemPresentationDTO>()
    private var dismissed = 0

    private fun missed(title: String, on: LocalDate, at: LocalTime) = DayItemPresentationDTO(
        intakeId = Uuid.random(),
        courseId = Uuid.random(),
        title = title,
        on = on,
        at = at,
        dose = QuantityPresentationDTO("1", TABLETS.toPresentationDTO()),
        packageName = "Нурофен",
        state = DayItemPresentationDTO.State.MISSED,
        answeredAt = null,
        hasPlannedPackage = true
    )

    private fun show(state: MissedIntakesUiState) {
        compose.setContent {
            MedAppTheme {
                MissedIntakesPopup(
                    state = state,
                    onOpen = { opened += it },
                    onConfirm = { confirmed += it },
                    onDismiss = { dismissed++ },
                    onDismissMessage = {}
                )
            }
        }
    }

    /**
     * Каждая строка несёт **свой день**: у вчерашнего и позавчерашнего пункта одно и то же время, и
     * без дня человек не отличит, за какой отвечает. «Принял» — у строки.
     */
    @Test
    fun eachRowCarriesItsDayAndTakesAConfirmation() {
        val yesterday = missed("Амоксиклав", LocalDate.of(2027, 3, 9), LocalTime.of(14, 0))
        show(MissedIntakesUiState(listOf(yesterday, missed("Амоксиклав", LocalDate.of(2027, 3, 8), LocalTime.of(14, 0)))))

        compose.onNodeWithText("Без ответа за прошлые дни").assertIsDisplayed()
        compose.onNodeWithText("09.03.2027 · 14:00").assertIsDisplayed()
        compose.onNodeWithText("08.03.2027 · 14:00").assertIsDisplayed()
        compose.onNodeWithText("Отметьте, что приняли. Закроете — останутся пропусками.").assertIsDisplayed()

        compose.onAllNodesWithText("Принял").onFirst().performClick()
        assertEquals(listOf(yesterday.intakeId), confirmed)
    }

    /** Нажатие на строку ведёт на карточку пункта: там выбирают другую коробку или время. */
    @Test
    fun tappingARowOpensItsCard() {
        val yesterday = missed("Амоксиклав", LocalDate.of(2027, 3, 9), LocalTime.of(20, 0))
        show(MissedIntakesUiState(listOf(yesterday)))

        compose.onNodeWithText("09.03.2027 · 20:00").performClick()

        assertEquals(listOf(yesterday), opened)
    }

    /** Крестик — ответ «остальное пропущено»; кнопки «понятно» нет, закрывает только он. */
    @Test
    fun theCrossIsTheOnlyWayToClose() {
        show(MissedIntakesUiState(listOf(missed("Амоксиклав", LocalDate.of(2027, 3, 9), LocalTime.of(14, 0)))))

        compose.onNodeWithContentDescription("Закрыть").performClick()

        assertEquals(1, dismissed)
    }

    /**
     * **Ответ на последнюю строку не пропадает вместе с ней** (CodeRabbit 4030083088). Нина нажала
     * «Принял» за вчерашний приём, а его как раз убрали перестройкой расписания: строка ушла, и с
     * ней ушло окно — «этого приёма больше нет» так и не было сказано, а всплыло бы при следующем
     * пропуске чужим ответом.
     */
    @Test
    fun theAnswerToTheLastRowIsToldAfterTheRowLeaves() {
        show(MissedIntakesUiState(rows = emptyList(), message = com.kert0n.medapp.presentation.plan.DayMessage.Gone))

        compose.onNodeWithText("Этого приёма больше нет").assertIsDisplayed()
        compose.onNodeWithText("Без ответа за прошлые дни").assertDoesNotExist()
    }

    /** Нечего спрашивать — окна нет вовсе. */
    @Test
    fun nothingMissedShowsNothing() {
        show(MissedIntakesUiState())

        compose.onNodeWithText("Без ответа за прошлые дни").assertDoesNotExist()
    }
}
