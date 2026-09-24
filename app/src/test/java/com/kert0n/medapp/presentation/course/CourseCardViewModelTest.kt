package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.feature.course.CourseCalendar
import com.kert0n.medapp.feature.course.CourseCancellation
import com.kert0n.medapp.feature.course.CourseClosing
import com.kert0n.medapp.feature.course.CourseFollowing
import com.kert0n.medapp.feature.course.CourseOffPlanCounting
import com.kert0n.medapp.feature.intake.IntakeReadings
import com.kert0n.medapp.feature.intake.IntakeRecords
import com.kert0n.medapp.feature.notification.ReminderPromising
import com.kert0n.medapp.feature.notification.ReminderWithdrawal
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourseStorage
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.QuietNotificationSettings
import com.kert0n.medapp.fixture.UnaskedIntakes
import com.kert0n.medapp.fixture.UnaskedReminders
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.queue.QueueService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Карточка лечения отвечает на действие человека (PLAN H3 №14, C1 «Исход сценария доходит при
 * любом состоянии экрана»). Что она читает, проверяет `CourseCardMapperTest`; здесь — что бывает
 * после «Отменить лечение».
 */
class CourseCardViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val courses = FakeCourseStorage()

    private val packages = FakePackages()

    /** Пункты карточке нужны потоком: пусто — это список без пунктов, а не молчащее чтение. */
    private object NoIntakes : IntakeRecords by UnaskedIntakes {
        override fun observeOfCourse(courseId: Uuid): Flow<List<IntakeProjection>> = flowOf(emptyList())
    }

    private fun viewModel(courseId: Uuid = COURSE) = CourseCardViewModel(
        cancellation = cancellation(),
        // Счёт доз мимо плана над подделками не проверяется — о нём судят над Room; сосед здесь
        // такой же, как остальные: позовут — упадёт с именем.
        offPlanCounting = offPlanCounting(),
        courses = courses,
        intakes = NoIntakes,
        courseId = courseId
    )

    /**
     * Отмена над подделками доходит только до своих отказов: отменить по-настоящему — значит
     * перестроить календарь и брони, и об этом судят над Room (`CourseCancellationTest`).
     * Соседи поэтому — подделки, которые падают, если их всё-таки позвать.
     */
    private fun cancellation(): CourseCancellation {
        val calendar = CourseCalendar(
            UnaskedIntakes,
            packages,
            ReminderPromising(UnaskedReminders, QuietNotificationSettings, DirectTransactions),
            ReminderWithdrawal(UnaskedReminders, DirectTransactions)
        )
        val following = CourseFollowing(
            courses,
            packages,
            calendar,
            QueueService(DirectTransactions, FakeQueue()),
            DirectTransactions
        )
        return CourseCancellation(
            courses,
            UnaskedIntakes,
            calendar,
            CourseClosing(courses, following, ReminderWithdrawal(UnaskedReminders, DirectTransactions)),
            DirectTransactions,
            clock
        )
    }

    private fun offPlanCounting(): CourseOffPlanCounting {
        val calendar = CourseCalendar(
            UnaskedIntakes,
            packages,
            ReminderPromising(UnaskedReminders, QuietNotificationSettings, DirectTransactions),
            ReminderWithdrawal(UnaskedReminders, DirectTransactions)
        )
        val following = CourseFollowing(
            courses,
            packages,
            calendar,
            QueueService(DirectTransactions, FakeQueue()),
            DirectTransactions
        )
        return CourseOffPlanCounting(
            courses,
            UnaskedIntakes,
            packages,
            calendar,
            following,
            CourseClosing(courses, following, ReminderWithdrawal(UnaskedReminders, DirectTransactions)),
            DirectTransactions,
            clock
        )
    }

    /**
     * Лечение кончилось само, пока человек шёл сюда: без этого правила диалог закрывается молча,
     * карточка на вид прежняя — и «отменил» неотличимо от «ничего не произошло».
     */
    @Test
    fun cancellingATreatmentThatAlreadyEndedIsAnsweredInWords() {
        courses.holding(courseRecord(outcome = CourseRecord.Outcome.COMPLETED, closedAt = clock.instant()))
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { !it.isLoading }
            model.askToCancel()
            model.cancel()
            state.awaiting { it.message != null }
        }

        assertEquals(CourseCardMessage.AlreadyFinished, model.state.value.message)
        assertFalse(model.state.value.isCancelling)
    }

    /**
     * Записи эпизода уже нет: об этом говорит сама карточка — тем же чтением, из которого о
     * пропаже узнал и сценарий. Своих слов исходу не нужно, но занятость с карточки он снимает:
     * иначе «Отменить» осталось бы нажатым навсегда.
     */
    @Test
    fun cancellingATreatmentThatIsGoneIsAnsweredByTheCardItself() {
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { it.isGone }
            model.askToCancel()
            model.cancel()
            state.awaiting { !it.isCancelling && !it.asksToCancel }
        }

        assertTrue(state.isGone)
        assertNull(state.message)
    }

    /** Ответ человека сообщение уносит: прочитанное не висит на экране до следующего действия. */
    @Test
    fun theAnswerToTheMessageTakesItAway() {
        courses.holding(courseRecord(outcome = CourseRecord.Outcome.COMPLETED, closedAt = clock.instant()))
        val model = viewModel()

        watching(model.state) { state ->
            state.awaiting { !it.isLoading }
            model.askToCancel()
            model.cancel()
            state.awaiting { it.message != null }
            model.dismissMessage()
            state.awaiting { it.message == null }
        }

        assertNull(model.state.value.message)
    }
}
