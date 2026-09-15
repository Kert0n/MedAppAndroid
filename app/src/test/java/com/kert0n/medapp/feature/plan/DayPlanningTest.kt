package com.kert0n.medapp.feature.plan

import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.report.StockSummary
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.storage.report.ReportStorageRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * План дня — по сдвигу от сегодняшнего (PLAN H3 «План»). Что лежит в самом плане, проверяет
 * чтение на настоящей базе; здесь — **какой день** спрашивают и когда спрашивают заново.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayPlanningTest {

    private val moscow = ZoneId.of("Europe/Moscow")

    /**
     * Часы никто не переводил. Поток молчит, но не кончается: кончившийся сорвал бы ожидание
     * границы дня.
     */
    private object Quiet : ClockShifts {
        override val signals = MutableSharedFlow<Unit>()
    }

    /** Часы перевели — столько раз, сколько скажет проверка. */
    private class Shifts : ClockShifts {
        private val told = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val signals = told
        fun happened() { told.tryEmit(Unit) }
    }

    /** Чтение, которое только запоминает, о каком дне его спросили. */
    private class AskedDays : ReportStorageRepository {
        val asked = mutableListOf<Pair<LocalDate, ZoneId>>()

        override fun observeDayPlan(date: LocalDate, zone: ZoneId): Flow<DayPlan> {
            asked += date to zone
            return flowOf(DayPlan(date, emptyList()))
        }

        override fun observeSpending(period: SpendingPeriod, zone: ZoneId): Flow<Spending> =
            error("отчёты этой проверке не нужны")

        override fun observeFutureSpending(horizon: SpendingHorizon): Flow<FutureSpending> =
            error("отчёты этой проверке не нужны")

        override fun observeStockSummary(): Flow<StockSummary> =
            error("отчёты этой проверке не нужны")
    }

    @Test
    fun theOffsetNamesTheDayAndTheZoneComesFromTheClock() = runTest {
        val clock = Clock.fixed(Instant.parse("2027-03-10T09:00:00Z"), moscow)
        val reads = AskedDays()

        val plan = DayPlanning(Today(clock, Quiet), reads).observe(daysAhead = 2).first()

        assertEquals(LocalDate.of(2027, 3, 12), plan.date)
        assertEquals(listOf(LocalDate.of(2027, 3, 12) to moscow), reads.asked)
    }

    /**
     * Сдвиг переживает смену дня: «завтра» в полночь указывает уже на другое число, и страница
     * перечитывается сама.
     *
     * Красная проверка: спрашивать дату один раз при подписке — после полуночи страница
     * показывает вчерашнее завтра.
     */
    @Test
    fun theSameOffsetMovesWithTheDay() = runTest {
        val start = Instant.parse("2027-03-10T20:30:00Z") // 23:30 в Москве
        val running = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = moscow
            override fun withZone(zone: ZoneId): Clock = this
        }
        val reads = AskedDays()

        val days = DayPlanning(Today(running, Quiet), reads).observe(daysAhead = 1).take(2).toList()

        // 23:30 десятого в Москве: «завтра» — одиннадцатое, а после полуночи — двенадцатое.
        assertEquals(listOf(LocalDate.of(2027, 3, 11), LocalDate.of(2027, 3, 12)), days.map { it.date })
    }

    /** Переезд двигает день сразу — и план вместе с ним, не дожидаясь прежней полуночи. */
    @Test
    fun aChangedZoneMovesThePlanAtOnce() = runTest {
        val start = Instant.parse("2027-03-10T20:00:00Z") // 23:00 в Москве, 06:00 во Владивостоке
        var zone = moscow
        val running = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = zone
            override fun withZone(zone: ZoneId): Clock = this
        }
        val shifts = Shifts()
        val reads = AskedDays()
        val seen = mutableListOf<LocalDate>()

        val eyes = backgroundScope.launch {
            DayPlanning(Today(running, shifts), reads).observe().collect { seen += it.date }
        }
        runCurrent()
        zone = ZoneId.of("Asia/Vladivostok")
        shifts.happened()
        runCurrent()
        eyes.cancel()

        assertEquals(listOf(LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, 11)), seen)
    }

    /**
     * Переезд между зонами с одинаковым числом — **другой день**: начало и конец суток уехали,
     * и разовые приёмы попадают в него уже по новым границам. Чтение обязано перечитать.
     *
     * Красная проверка: следить только за числом — план остаётся в прежней зоне до следующей
     * полуночи, и «за сегодня» считается по чужим границам.
     */
    @Test
    fun aZoneChangeWithTheSameDateIsANewDayToo() = runTest {
        val start = Instant.parse("2027-03-10T09:00:00Z") // полдень в Москве, вечер в Ташкенте
        var zone = moscow
        val running = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = zone
            override fun withZone(zone: ZoneId): Clock = this
        }
        val shifts = Shifts()
        val reads = AskedDays()

        val eyes = backgroundScope.launch {
            DayPlanning(Today(running, shifts), reads).observe().collect { }
        }
        runCurrent()
        zone = ZoneId.of("Asia/Tashkent")
        shifts.happened()
        runCurrent()
        eyes.cancel()

        assertEquals(
            listOf(LocalDate.of(2027, 3, 10) to moscow, LocalDate.of(2027, 3, 10) to ZoneId.of("Asia/Tashkent")),
            reads.asked
        )
    }

    /** Назад пока не листают, и «так нельзя» не притворяется пустым днём. */
    @Test
    fun theDayBeforeTodayIsRefusedRatherThanShownEmpty() {
        val clock = Clock.fixed(Instant.parse("2027-03-10T09:00:00Z"), moscow)

        val refused = runCatching { DayPlanning(Today(clock, Quiet), AskedDays()).observe(daysAhead = -1) }

        assertEquals(
            "в прошлое пока не листают: сдвиг -1",
            refused.exceptionOrNull()?.message
        )
    }
}
