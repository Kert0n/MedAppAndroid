package com.kert0n.medapp.ui.plan

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.plan.DayItemPresentationDTO
import com.kert0n.medapp.presentation.plan.DayPagePresentationDTO
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
 * Страница дня (PLAN H3 №12): что человек видит. Что в этот день попало, проверяют чтение и
 * `DayPlanPresentationMapperTest`.
 */
@RunWith(AndroidJUnit4::class)
class DayPageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val today: LocalDate = LocalDate.of(2027, 3, 10)

    private val opened = mutableListOf<DayItemPresentationDTO>()
    private val confirmed = mutableListOf<Uuid>()

    private fun row(
        title: String,
        at: LocalTime = LocalTime.of(9, 0),
        state: DayItemPresentationDTO.State = DayItemPresentationDTO.State.PLANNED,
        packageName: String? = "Нурофен",
        answeredAt: LocalTime? = null
    ) = DayItemPresentationDTO(
        intakeId = Uuid.random(),
        courseId = Uuid.random(),
        title = title,
        at = at,
        dose = QuantityPresentationDTO("2", TABLETS.toPresentationDTO()),
        packageName = packageName,
        state = state,
        answeredAt = answeredAt
    )

    private fun show(vararg pages: DayPagePresentationDTO) {
        compose.setContent {
            MedAppTheme {
                DayPages(
                    page = { daysAhead ->
                        pages.getOrNull(daysAhead)?.let { ScreenState.Ready(it) } ?: ScreenState.Loading
                    },
                    onOpen = { opened += it },
                    onConfirm = { confirmed += it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun page(
        daysAhead: Int = 0,
        items: List<DayItemPresentationDTO> = emptyList(),
        alsoOnThisDay: List<DayItemPresentationDTO> = emptyList()
    ) = DayPagePresentationDTO(
        date = today.plusDays(daysAhead.toLong()),
        daysAhead = daysAhead,
        items = items,
        alsoOnThisDay = alsoOnThisDay
    )

    /**
     * Состояние отвеченного пункта названо **словом**, а не одним значком и цветом: человек,
     * который не различает цвета или смотрит через экранного чтеца, иначе не узнает, принят приём
     * или пропущен (PLAN H3 «Цвет не единственный носитель»).
     */
    @Test
    fun everyAnsweredStateIsToldInWords() {
        show(
            page(
                items = listOf(
                    row("Принят", state = DayItemPresentationDTO.State.TAKEN, answeredAt = LocalTime.of(9, 12)),
                    row("Отменён", state = DayItemPresentationDTO.State.CANCELLED)
                ),
                alsoOnThisDay = listOf(
                    row("Разовый", state = DayItemPresentationDTO.State.ONE_OFF, answeredAt = LocalTime.of(14, 20))
                )
            )
        )

        // У принятого рядом со словом стоит время: за ним человек день и открывал.
        compose.onNodeWithText("принят в 09:12").assertIsDisplayed()
        compose.onNodeWithText("отменён").assertIsDisplayed()
        compose.onNodeWithText("разово в 14:20").assertIsDisplayed()
    }

    /**
     * На неотвеченный пункт отвечают **прямо в строке**: в день приёмов несколько, и просить два
     * нажатия на каждый — просить лишнего (H3 №12). Пропущенному отвечают тоже: доза уехала
     * вперёд, и подтвердить её позже законно.
     */
    @Test
    fun anUnansweredRowIsAnsweredRightThere() {
        val waiting = row("Ждёт")
        val missed = row("Пропущен", state = DayItemPresentationDTO.State.MISSED)
        show(page(items = listOf(waiting, missed)))

        compose.onAllNodesWithText("Принял").assertCountEquals(2)
        compose.onAllNodesWithText("Принял").onFirst().performClick()

        assertEquals(listOf(waiting.intakeId), confirmed)
    }

    /**
     * Пока идёт запись, кнопка на месте, но погашена: исчезнувшая читалась бы как «уже ответил»,
     * а нажатая второй раз списала бы коробку дважды.
     */
    @Test
    fun aRowBeingWrittenKeepsItsButtonButDoesNotTakeAPress() {
        show(page(items = listOf(row("Ждёт").copy(isAnswering = true))))

        compose.onNodeWithText("Принял").assertIsNotEnabled()
        compose.onNodeWithText("Принял").performClick()

        assertEquals(emptyList<Uuid>(), confirmed)
    }

    /** Нажатие на саму строку ведёт на карточку пункта — туда, где приём меняют. */
    @Test
    fun tappingTheRowItselfOpensTheCard() {
        val item = row("Нурофен")
        show(page(items = listOf(item)))

        compose.onNodeWithText("Нурофен").performClick()

        assertEquals(listOf(item), opened)
    }

    /** Заголовок называет и число, и слово: «сегодня» человек читает быстрее, чем сравнивает даты. */
    @Test
    fun theHeaderNamesTheDateAndTheNearDayInWords() {
        show(page(items = listOf(row("Нурофен"))))

        compose.onNodeWithText("10.03.2027 · сегодня").assertIsDisplayed()
    }

    /**
     * На этот день ничего не назначено — так и сказано. Пустой экран человек читает как поломку, а
     * заголовок остаётся: без него непонятно, о каком дне речь.
     */
    @Test
    fun anEmptyDaySaysSoAndKeepsItsHeader() {
        show(page())

        compose.onNodeWithText("На этот день ничего не назначено").assertIsDisplayed()
        compose.onNodeWithText("10.03.2027 · сегодня").assertIsDisplayed()
    }

    /** То, на что не отвечают, стоит ниже и под своим заголовком: это уже случилось или ещё не записано. */
    @Test
    fun whatIsNotAnsweredStandsUnderItsOwnHeading() {
        show(
            page(
                items = listOf(row("Нурофен")),
                alsoOnThisDay = listOf(
                    row("Цетрин", at = LocalTime.of(21, 0), state = DayItemPresentationDTO.State.EXPECTED, packageName = null)
                )
            )
        )

        compose.onNodeWithText("Ещё в этот день").assertIsDisplayed()
        compose.onNodeWithText("ожидается").assertIsDisplayed()
    }

    /** Листают вперёд: следующая страница — следующий день, и она своя, а не та же самая. */
    @Test
    fun swipingForwardOpensTheNextDay() {
        show(
            page(daysAhead = 0, items = listOf(row("Нурофен"))),
            page(daysAhead = 1, items = listOf(row("Цетрин")))
        )

        compose.onNodeWithText("10.03.2027 · сегодня").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText("11.03.2027 · завтра").assertIsDisplayed()
        compose.onNodeWithText("Цетрин").assertIsDisplayed()
    }
}
