package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.feature.course.CourseCalendar
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.feature.course.CourseFollowing
import com.kert0n.medapp.feature.course.SourceEditing
import com.kert0n.medapp.feature.notification.ReminderPromising
import com.kert0n.medapp.feature.notification.ReminderWithdrawal
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeCourseStorage
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.FakePackages
import com.kert0n.medapp.fixture.FakeQueue
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.QuietNotificationSettings
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.UnaskedIntakes
import com.kert0n.medapp.fixture.UnaskedReminders
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.prescribedDraft
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.queue.QueueService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Чем кончилось подключение коробки (PLAN H3 №17, C1 «Исход сценария доходит при любом состоянии
 * экрана»). Годность коробки проверяет `SourcePickingViewModelTest` над Room; здесь — что человек
 * узнаёт об отказе, а не уходит с экрана как после удачи.
 */
class SourcePickingOutcomesTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private object Quiet : ClockShifts {
        override val signals = MutableSharedFlow<Unit>()
    }

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private val courses = FakeCourseStorage()

    private val packages = FakePackages(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))

    /**
     * Подключение черновика до записи не доходит, а у идущего лечения его проверяет Room: соседи
     * сценария — подделки, которые падают, если их всё-таки позвать.
     */
    private val calendar = CourseCalendar(
        UnaskedIntakes,
        packages,
        ReminderPromising(UnaskedReminders, QuietNotificationSettings, DirectTransactions),
        ReminderWithdrawal(UnaskedReminders, DirectTransactions)
    )

    private val following = CourseFollowing(
        courses,
        packages,
        calendar,
        QueueService(DirectTransactions, FakeQueue()),
        DirectTransactions
    )

    private fun viewModel() = SourcePickingViewModel(
        drafting = CourseDrafting(courses, packages, DirectTransactions, clock),
        sources = SourceEditing(
            courses,
            UnaskedIntakes,
            packages,
            calendar,
            following,
            DirectTransactions,
            clock
        ),
        courses = courses,
        packages = packages,
        medKits = FakeMedKits(),
        today = Today(clock, Quiet),
        courseId = COURSE
    )

    /**
     * Состав правили с соседнего экрана, пока человек выбирал: запись не легла. Без этого правила
     * экран уходит назад как после удачи — коробки в стеке нет, и человек не знает почему.
     */
    @Test
    fun aWriteThatDidNotLandDoesNotLookLikeAnAttachment() {
        courses.holding(prescribedDraft())
        val model = viewModel()

        val state = watching(model.state) { state ->
            state.awaiting { !it.isLoading }
            // Правка соседнего экрана, о которой этот ещё не знает: редакция ушла вперёд молча.
            courses.drafts[COURSE] = course(id = COURSE, dose = dose("2"), form = TABLET_FORM, revision = 5)
            model.attach(PACK)
            state.awaiting { !it.isAttaching }
        }

        assertEquals(CourseSourcesMessage.Stale, state.message)
        assertFalse(state.isAttached)
    }
}
