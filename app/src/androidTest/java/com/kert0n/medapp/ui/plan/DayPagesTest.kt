package com.kert0n.medapp.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.AllAllowed
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.reportRepository
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.plan.DayPagePresentationDTO
import com.kert0n.medapp.presentation.plan.DayPlanViewModel
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Страницы дня (PLAN H3 №12). Какой день спрашивают и когда спрашивают заново, проверяет
 * `DayPlanningTest`; здесь — что страница у каждого сдвига **своя** и что второй взгляд на ту же
 * страницу нового чтения не заводит.
 */
@RunWith(AndroidJUnit4::class)
class DayPagesTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val opened = mutableListOf<ViewModel>()

    @Before
    fun setUp() = runBlocking {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
    }

    @After
    fun tearDown() {
        opened.forEach { it.viewModelScope.cancel() }
        database.close()
    }

    private fun model() = DayPlanViewModel(
        today = Today(clock, QuietClock),
        planning = DayPlanning(Today(clock, QuietClock), database.reportRepository()),
        confirmation = scenarios.intakeConfirmation,
        declining = scenarios.intakeDeclining,
        devicePermissions = AllAllowed,
        clock = clock,
        reminders = scenarios.reminderStore,
        intakes = database.intakeRepository(),
        courses = database.courseRepository()
    ).also { opened += it }

    private fun shown(model: DayPlanViewModel, daysAhead: Int): DayPagePresentationDTO {
        val page = model.page(daysAhead)
        val state = watching(page) { it.awaiting(PATIENTLY) { s -> s is ScreenState.Ready } }
        return (state as ScreenState.Ready).value
    }

    /**
     * У каждого сдвига своя страница: человек, листающий вперёд, держит на виду две разом, и одно
     * состояние на всех показало бы соседней странице чужие строки.
     */
    @Test
    fun eachOffsetHasItsOwnPage() {
        val model = model()

        assertEquals(LocalDate.of(2027, 3, 10), shown(model, 0).date)
        assertEquals(LocalDate.of(2027, 3, 11), shown(model, 1).date)
        assertEquals(0, shown(model, 0).daysAhead)
        assertEquals(1, shown(model, 1).daysAhead)
    }

    /**
     * Вернувшийся на ту же страницу получает её же. Спрашивай чтение заново на каждый оборот
     * вёрстки — и листание заводило бы по подписке на кадр, а страница мигала бы ожиданием.
     */
    @Test
    fun theSamePageIsNotAskedTwice() {
        val model = model()

        assertSame(model.page(0), model.page(0))
    }

    private companion object {
        /** Столько ждём чтения: между подпиской и состоянием стоят потоки Room. */
        val PATIENTLY: Duration = 15.seconds
    }
}
