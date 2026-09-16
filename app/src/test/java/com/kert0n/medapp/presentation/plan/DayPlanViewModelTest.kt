package com.kert0n.medapp.presentation.plan

import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.report.StockSummary
import com.kert0n.medapp.feature.plan.DayPlanning
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.QuietClock
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.watching
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.storage.report.ReportStorageRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * Страницы дня (PLAN H3 №12). Какой день спрашивают и когда спрашивают заново, проверяет
 * `DayPlanningTest`; здесь — что страница у каждого сдвига **своя** и что второй взгляд на ту же
 * страницу нового чтения не заводит.
 */
class DayPlanViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    /** Чтение, которое помнит, о каком дне его спросили, и отвечает пустым планом этого дня. */
    private class AskedDays : ReportStorageRepository {
        val asked = mutableListOf<LocalDate>()

        override fun observeDayPlan(date: LocalDate, zone: ZoneId): Flow<DayPlan> {
            asked += date
            return flowOf(DayPlan(date, emptyList()))
        }

        override fun observeSpending(period: SpendingPeriod, zone: ZoneId): Flow<Spending> =
            error("отчёты этой проверке не нужны")

        override fun observeFutureSpending(horizon: SpendingHorizon): Flow<FutureSpending> =
            error("отчёты этой проверке не нужны")

        override fun observeStockSummary(): Flow<StockSummary> =
            error("отчёты этой проверке не нужны")
    }

    private val reads = AskedDays()

    private val model = DayPlanViewModel(
        today = Today(Clock.fixed(Instant.parse("2027-03-10T09:00:00Z"), MOSCOW), QuietClock),
        planning = DayPlanning(Today(Clock.fixed(Instant.parse("2027-03-10T09:00:00Z"), MOSCOW), QuietClock), reads)
    )

    private fun shown(daysAhead: Int): DayPagePresentationDTO {
        val page = model.page(daysAhead)
        val state = watching(page) { it.awaiting { s -> s is ScreenState.Ready } }
        return (state as ScreenState.Ready).value
    }

    /**
     * У каждого сдвига своя страница: человек, листающий вперёд, держит на виду две разом, и одно
     * состояние на всех показало бы соседней странице чужие строки.
     */
    @Test
    fun eachOffsetHasItsOwnPage() {
        assertEquals(LocalDate.of(2027, 3, 10), shown(0).date)
        assertEquals(LocalDate.of(2027, 3, 11), shown(1).date)
        assertEquals(0, shown(0).daysAhead)
        assertEquals(1, shown(1).daysAhead)
    }

    /**
     * Вернувшийся на ту же страницу получает её же. Спрашивай чтение заново на каждый оборот
     * вёрстки — и листание заводило бы по подписке на кадр, а страница мигала бы ожиданием.
     */
    @Test
    fun theSamePageIsNotAskedTwice() {
        assertSame(model.page(0), model.page(0))
    }
}
