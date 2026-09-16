package com.kert0n.medapp.ui.course

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.presentation.course.CourseCardUiState
import com.kert0n.medapp.presentation.course.CourseCoveragePresentationDTO
import com.kert0n.medapp.presentation.course.CourseItemPresentationDTO
import com.kert0n.medapp.presentation.course.CoursePresentationDTO
import com.kert0n.medapp.presentation.course.CoverageReductionPresentationDTO
import com.kert0n.medapp.presentation.course.SchedulePresentationDTO
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.ui.theme.MedAppTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Карточка лечения (PLAN H3 №14): первым — обеспечение, потом назначение, сокращения и пункты.
 * Что при этом записывается, проверяет `CourseCardViewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class CourseCardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var asked = 0
    private var askedOffPlan = 0
    private var confirmed = 0
    private var edited = 0

    private fun show(state: CourseCardUiState) {
        compose.setContent {
            MedAppTheme {
                CourseCardScreen(
                    state = state,
                    onEdit = { edited++ },
                    onSources = {},
                    onHistory = {},
                    onAskOffPlan = { askedOffPlan++ },
                    onCountOffPlan = {},
                    onDismissOffPlan = {},
                    onAskToCancel = { asked++ },
                    onConfirmCancel = { confirmed++ },
                    onDismissCancel = {},
                    onDismissMessage = {},
                    onBack = {}
                )
            }
        }
    }

    private fun course(kind: CoursePresentationDTO.Kind = CoursePresentationDTO.Kind.RUNNING) =
        CoursePresentationDTO(
            id = Uuid.random(),
            title = "Нурофен",
            note = "по 2 после еды",
            kind = kind,
            dose = QuantityPresentationDTO("2", TABLETS.toPresentationDTO()),
            form = null,
            schedule = SchedulePresentationDTO(
                start = LocalDate.of(2027, 3, 10),
                days = DayOfWeek.entries.toSet(),
                times = listOf(LocalTime.of(9, 0)),
                zone = ZoneId.of("Europe/Moscow")
            ),
            totalDoses = 28,
            shortage = null,
            closedOn = LocalDate.of(2027, 3, 24)
        )

    private fun item(status: IntakeStatus, at: LocalTime = LocalTime.of(9, 0)) = CourseItemPresentationDTO(
        id = Uuid.random(),
        on = LocalDate.of(2027, 3, 10),
        at = at,
        amount = QuantityPresentationDTO("2", TABLETS.toPresentationDTO()),
        packageName = "Нурофен",
        status = status,
        takenAt = if (status == IntakeStatus.TAKEN) LocalTime.of(9, 12) else null,
        takenAmount = null
    )

    /** Обеспечение — первым: за ним человек и открыл карточку. Нехватка сказана словами. */
    @Test
    fun coverageIsTheFirstThingTheCardSays() {
        show(
            CourseCardUiState(
                course = course(),
                coverage = CourseCoveragePresentationDTO(28, 9, 19, null, LocalDate.of(2027, 3, 11)),
                isRunning = true
            )
        )

        compose.onNodeWithText("нужно 28 приёмов · обеспечено 9").assertIsDisplayed()
        compose.onNodeWithText("Не хватает 19 приёмов с 11.03.2027").assertIsDisplayed()
    }

    /** Обеспечен целиком — сказано, до какого дня хватит. */
    @Test
    fun aFullyCoveredCourseSaysUntilWhen() {
        show(
            CourseCardUiState(
                course = course(),
                coverage = CourseCoveragePresentationDTO(28, 28, 0, LocalDate.of(2027, 3, 24), null),
                isRunning = true
            )
        )

        compose.onNodeWithText("нужно 28 приёмов · обеспечен до 24.03.2027").assertIsDisplayed()
    }

    /** Пункты: время, доза, коробка и состояние словами — «пропущен» не одним цветом. */
    @Test
    fun itemsTellTheirStateInWords() {
        show(
            CourseCardUiState(
                course = course(),
                items = listOf(item(IntakeStatus.TAKEN), item(IntakeStatus.MISSED, LocalTime.of(13, 0))),
                isRunning = true
            )
        )

        compose.onNodeWithText("принят в 09:12").assertIsDisplayed()
        compose.onNodeWithText("пропущен").assertIsDisplayed()
    }

    /** Сокращение обеспечения — событие, и карточка называет коробку и день. */
    @Test
    fun aReductionIsShownAsWhatHappened() {
        show(
            CourseCardUiState(
                course = course(),
                reductions = listOf(
                    CoverageReductionPresentationDTO(Uuid.random(), LocalDate.of(2027, 3, 15), "Нурофен", 12, 7)
                ),
                isRunning = true
            )
        )

        compose.onNodeWithText("Было 12 приёмов, стало 7").assertIsDisplayed()
        compose.onNodeWithText("15.03.2027 · Нурофен").assertIsDisplayed()
    }

    /** Отмена лечения спрашивается: сценарий зовётся после ответа человека (H3). */
    @Test
    fun cancellingAsksFirst() {
        show(CourseCardUiState(course = course(), isRunning = true))

        compose.onNodeWithContentDescription("Ещё").performClick()
        compose.onNodeWithText("Отменить лечение").performClick()

        assertEquals(1, asked)
        assertEquals(0, confirmed)
    }

    /** Вопрос объясняет последствия: что уйдёт, что освободится и что останется. */
    @Test
    fun theQuestionExplainsWhatCancellingDoes() {
        show(CourseCardUiState(course = course(), isRunning = true, asksToCancel = true))

        compose.onNodeWithText("Отменить лечение?").assertIsDisplayed()
        compose.onNodeWithText(
            "Будущие приёмы уйдут, пачки освободятся. Принятое и пропущенное останется в истории."
        ).assertIsDisplayed()
    }

    /** У законченного лечения действий нет: отменять и править уже нечего. */
    @Test
    fun aFinishedCourseHasNoActions() {
        show(CourseCardUiState(course = course(CoursePresentationDTO.Kind.COMPLETED), isRunning = false))

        compose.onNodeWithText("Завершён 24.03.2027").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ещё").assertDoesNotExist()
    }

    /** Эпизода больше нет — это отказ, а не пустая карточка. */
    @Test
    fun aMissingCourseSaysSo() {
        show(CourseCardUiState(isGone = true))

        compose.onNodeWithText("Этого лечения больше нет.").assertIsDisplayed()
    }

    /**
     * Приёмы вне расписания — переход со значком и стрелкой, а не текстовая кнопка: кнопкой он
     * читался как заголовок (замечание владельца 2026-09-16). Счёт назван приёмами: лечение меряет
     * себя ими, и третье слово о том же сбивает.
     */
    @Test
    fun theOffPlanCountIsAskedBeforeItIsWritten() {
        show(CourseCardUiState(course = course(), isRunning = true, offPlanDoses = 1))

        compose.onNodeWithText("1 приём").assertIsDisplayed()
        compose.onNodeWithText("Приёмы вне расписания").performClick()

        assertEquals(1, askedOffPlan)
    }
}
