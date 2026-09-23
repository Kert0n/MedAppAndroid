package com.kert0n.medapp.ui.plan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.presentation.plan.DayItemPresentationDTO
import com.kert0n.medapp.presentation.plan.MissedIntakesUiState
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
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

    private val confirmed = mutableListOf<Pair<Uuid, MissedIntakesUiState.Planned>>()
    private val opened = mutableListOf<DayItemPresentationDTO>()
    private val dismissed = mutableListOf<Set<NotificationKey>>()

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

    /** Попап так, как его собирает модель: у каждой строки свой ключ и своё плановое. */
    private fun popup(vararg rows: DayItemPresentationDTO) = MissedIntakesUiState(
        rows = rows.toList(),
        told = rows.mapTo(HashSet()) { key(it) },
        planned = rows.mapNotNull { row -> row.intakeId?.let { it to planned(row) } }.toMap()
    )

    private fun key(row: DayItemPresentationDTO) =
        NotificationKey(NotificationKind.INTAKE_MISSED, row.intakeId.toString())

    /** Плановое строки — в момент её пункта: у двух строк оно разное, и подмену видно. */
    private fun planned(row: DayItemPresentationDTO) =
        MissedIntakesUiState.Planned(PACK, dose("1"), row.on!!.atTime(row.at).toInstant(ZoneOffset.UTC))

    private fun show(state: MissedIntakesUiState) {
        compose.setContent {
            MedAppTheme {
                MissedIntakesPopup(
                    state = state,
                    onOpen = { opened += it },
                    onConfirm = { id, planned -> confirmed += id to planned },
                    onDismiss = { dismissed += it },
                    onDismissMessage = {},
                    onAcknowledge = {},
                    onDismissQuestion = {}
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
        show(popup(yesterday, missed("Амоксиклав", LocalDate.of(2027, 3, 8), LocalTime.of(14, 0))))

        compose.onNodeWithText("Без ответа за прошлые дни").assertIsDisplayed()
        compose.onNodeWithText("09.03.2027 · 14:00").assertIsDisplayed()
        compose.onNodeWithText("08.03.2027 · 14:00").assertIsDisplayed()
        compose.onNodeWithText("Отметьте, что приняли. Закроете — останутся пропусками.").assertIsDisplayed()

        compose.onAllNodesWithText("Принял").onFirst().performClick()
        assertEquals(listOf(yesterday.intakeId), confirmed.map { it.first })
    }

    /** Нажатие на строку ведёт на карточку пункта: там выбирают другую коробку или время. */
    @Test
    fun tappingARowOpensItsCard() {
        val yesterday = missed("Амоксиклав", LocalDate.of(2027, 3, 9), LocalTime.of(20, 0))
        show(popup(yesterday))

        compose.onNodeWithText("09.03.2027 · 20:00").performClick()

        assertEquals(listOf(yesterday), opened)
    }

    /** Крестик — ответ «остальное пропущено»; кнопки «понятно» нет, закрывает только он. */
    @Test
    fun theCrossIsTheOnlyWayToClose() {
        show(popup(missed("Амоксиклав", LocalDate.of(2027, 3, 9), LocalTime.of(14, 0))))

        compose.onNodeWithContentDescription("Закрыть").performClick()

        assertEquals(1, dismissed.size)
    }

    /**
     * **«Принял» уносит с собой плановое той строки, которую человек видел** (CodeRabbit 4030390711).
     * Иначе сценарий читал бы его заново в момент нажатия: успей расписание провернуться между
     * взглядом и пальцем — и записалась бы чужая коробка, доза или минута, а пропавшая строка
     * съела бы ответ молча.
     */
    @Test
    fun theConfirmationCarriesThePlannedOfTheRowThatWasDrawn() {
        val yesterday = missed("Амоксиклав", LocalDate.of(2027, 3, 9), LocalTime.of(14, 0))
        val shown = popup(yesterday)
        show(shown)

        compose.onNodeWithText("Принял").performClick()

        assertEquals(listOf(yesterday.intakeId to shown.planned.getValue(yesterday.intakeId!!)), confirmed)
    }

    /**
     * **Крестик уносит с собой ключи того попапа, который человек прочёл** (CodeRabbit 4030390716).
     * Иначе сказанным оказался бы и пропуск, приехавший чтением, пока Нина тянулась к крестику, —
     * а последнего шанса ответить за него больше не будет.
     */
    @Test
    fun theCrossCarriesTheKeysThatWereDrawn() {
        val shown = popup(missed("Амоксиклав", LocalDate.of(2027, 3, 9), LocalTime.of(14, 0)))
        show(shown)

        compose.onNodeWithContentDescription("Закрыть").performClick()

        assertEquals(listOf(shown.told), dismissed)
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
