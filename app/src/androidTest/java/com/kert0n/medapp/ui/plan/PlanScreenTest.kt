package com.kert0n.medapp.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.course.CourseListPresentationDTO
import com.kert0n.medapp.presentation.course.CoursePresentationDTO
import com.kert0n.medapp.presentation.course.SchedulePresentationDTO
import com.kert0n.medapp.presentation.plan.DayPagePresentationDTO
import com.kert0n.medapp.presentation.plan.DayPermissionsPresentationDTO
import com.kert0n.medapp.presentation.course.ShortagePresentationDTO
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
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
 * Место «План» (PLAN H3 «Набор курсов»): два состояния одного места, и список курсов в одном из
 * них — с полками, нехваткой словами и пустотой, которая предлагает записать первое лечение.
 */
@RunWith(AndroidJUnit4::class)
class PlanScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var opened: CoursePresentationDTO? = null
    private var added = 0

    private val emptyDay = DayPagePresentationDTO(
        date = LocalDate.of(2027, 3, 10),
        daysAhead = 0,
        items = emptyList(),
        alsoOnThisDay = emptyList()
    )

    private fun course(
        title: String,
        kind: CoursePresentationDTO.Kind,
        shortage: ShortagePresentationDTO? = null,
        note: String? = null,
        closedOn: LocalDate? = null
    ) = CoursePresentationDTO(
        id = Uuid.random(),
        title = title,
        note = note,
        kind = kind,
        dose = QuantityPresentationDTO("2", UnitPresentationDTO(Uuid.random(), "таблетка")),
        form = FormPresentationDTO(Uuid.random(), "таблетки"),
        schedule = SchedulePresentationDTO(
            start = LocalDate.of(2027, 3, 1),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
            times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)),
            zone = ZoneId.of("Europe/Moscow")
        ),
        totalDoses = 14,
        shortage = shortage,
        closedOn = closedOn
    )

    private fun show(courses: ScreenState<CourseListPresentationDTO>, mode: PlanMode = PlanMode.COURSES) {
        compose.setContent {
            var current by androidx.compose.runtime.remember { mutableStateOf(mode) }
            MedAppTheme {
                PlanScreen(
                    mode = current,
                    onMode = { current = it },
                    courses = courses,
                    // Что на странице дня, проверяет `DayPageScreenTest`; здесь она нужна
                    // настолько, чтобы отличить одно состояние места от другого.
                    dayPage = { ScreenState.Ready(emptyDay) },
                    dayPermissions = DayPermissionsPresentationDTO(),
                    onFixNotifications = {},
                    onFixAlarms = {},
                    onOpenIntake = {},
                    onConfirmIntake = {},
                    onDeclineIntake = {},
                    onAcknowledgeIntake = {},
                    onDismissDayMessage = {},
                    onOpenCourse = { opened = it },
                    onAddCourse = { added++ }
                )
            }
        }
    }

    private fun listed(vararg courses: CoursePresentationDTO) = ScreenState.Ready(
        CourseListPresentationDTO(
            running = courses.filter { it.kind == CoursePresentationDTO.Kind.RUNNING },
            drafts = courses.filter { it.kind == CoursePresentationDTO.Kind.DRAFT },
            finished = courses.filter { it.closedOn != null }
        )
    )

    /** Два состояния — один переключатель: страница дня и курсы меняются местами, шапка та же. */
    @Test
    fun theTwoStatesSwitchUnderOneHeading() {
        show(listed(course("Нурофен", CoursePresentationDTO.Kind.RUNNING)))

        compose.onNodeWithText("Нурофен").assertIsDisplayed()
        compose.onNodeWithText("День").performClick()
        compose.onNodeWithText("На этот день ничего не назначено").assertIsDisplayed()
        compose.onNodeWithText("Нурофен").assertDoesNotExist()
        compose.onNodeWithText("Курсы").performClick()
        compose.onNodeWithText("Нурофен").assertIsDisplayed()
    }

    /** Пусто — не список: сказано, что лечений нет, и предложено записать первое; кнопка одна. */
    @Test
    fun anEmptyListSaysSoAndOffersTheFirstCourseOnce() {
        show(listed())

        compose.onNodeWithText("Лечений пока нет.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Записать лечение").performClick()
        compose.onNodeWithContentDescription("Записать лечение").assertDoesNotExist()

        assertEquals(1, added)
    }

    /**
     * До первого чтения — ожидание, а не «лечений пока нет»: пустота, показанная вместо загрузки,
     * зовёт заводить второе лечение поверх уже записанного (U1).
     */
    @Test
    fun theListWaitsForTheFirstReading() {
        show(ScreenState.Loading)

        compose.onNodeWithContentDescription("Загрузка").assertIsDisplayed()
        compose.onNodeWithText("Лечений пока нет.", substring = true).assertDoesNotExist()
    }

    /** Три полки под своими заголовками; пустая полка не показывается — заголовок без строк ничего не говорит. */
    @Test
    fun coursesLieOnThreeShelvesAndAnEmptyShelfIsNotShown() {
        show(
            listed(
                course("Идёт", CoursePresentationDTO.Kind.RUNNING),
                course("Кончился", CoursePresentationDTO.Kind.COMPLETED, closedOn = LocalDate.of(2027, 3, 9))
            )
        )

        compose.onNodeWithText("Идут").assertIsDisplayed()
        compose.onNodeWithText("Завершены").assertIsDisplayed()
        compose.onNodeWithText("Черновики").assertDoesNotExist()
        compose.onNodeWithText("Завершён 09.03.2027").assertIsDisplayed()
        compose.onNodeWithText("2 таблетка · 2 раза в день · пн–ср").assertIsDisplayed()
    }

    /** Нехватка названа словами и днём, а не одним значком. */
    @Test
    fun aShortageIsSaidInWords() {
        show(listed(course("Нурофен", CoursePresentationDTO.Kind.RUNNING, ShortagePresentationDTO(19, LocalDate.of(2027, 3, 11)))))

        compose.onNodeWithText("Не хватает 19 приёмов с 11.03.2027").assertIsDisplayed()
    }

    /** Черновик узнаётся значком и заметкой; строка нажимается целиком и отдаёт, что открывать. */
    @Test
    fun aDraftIsMarkedAndTheRowOpensIt() {
        val draft = course("Нурофен", CoursePresentationDTO.Kind.DRAFT, note = "по 2 после еды")
        show(listed(draft))

        compose.onNodeWithContentDescription("Черновик").assertIsDisplayed()
        compose.onNodeWithText("по 2 после еды").performClick()

        assertEquals(draft, opened)
    }
}
