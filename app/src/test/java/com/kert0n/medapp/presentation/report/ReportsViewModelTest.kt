package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.report.StockSummary
import com.kert0n.medapp.feature.time.ClockShifts
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.storage.report.ReportStorageRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Место «Отчёты»: **какой вопрос** задан и когда он задаётся заново (PLAN H3 «Набор аналитики»).
 * Что лежит внутри отчёта, отвечают проверки домена и чтения на настоящей базе.
 *
 * Каждый случай здесь — переход из порядка, записанного в H3: первый вопрос, смена выбора,
 * полночь, негодный выбор.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {

    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")

    /** Часы никто не переводил; поток молчит, но не кончается — иначе оборвётся ожидание суток. */
    private object Quiet : ClockShifts {
        override val signals = MutableSharedFlow<Unit>()
    }

    /**
     * Чтение, которое запоминает заданные вопросы и отвечает **по требованию**: у каждого вопроса
     * свой ответ, и пока его не положили, поток молчит — как молчит настоящее чтение, пока база не
     * отдала первый снимок.
     */
    private class Reads : ReportStorageRepository {
        val horizons = mutableListOf<SpendingHorizon>()
        val stock = MutableStateFlow(StockSummary.of(emptyList()))
        private val answers = mutableMapOf<SpendingHorizon, MutableSharedFlow<FutureSpending>>()

        override fun observeStockSummary(): Flow<StockSummary> = stock

        override fun observeFutureSpending(horizon: SpendingHorizon): Flow<FutureSpending> {
            horizons += horizon
            return answerTo(horizon)
        }

        fun answer(horizon: SpendingHorizon, spending: FutureSpending = FutureSpending.EMPTY) {
            answerTo(horizon).tryEmit(spending)
        }

        private fun answerTo(horizon: SpendingHorizon) =
            answers.getOrPut(horizon) { MutableSharedFlow(replay = 1, extraBufferCapacity = 1) }

        val periods = mutableListOf<Pair<SpendingPeriod, ZoneId>>()

        override fun observeSpending(period: SpendingPeriod, zone: ZoneId): Flow<Spending> {
            periods += period to zone
            return MutableStateFlow(Spending.EMPTY)
        }

        override fun observeDayPlan(date: LocalDate, zone: ZoneId): Flow<DayPlan> =
            error("этот отчёт проверке не нужен")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * `viewModelScope` ставится на **те же** виртуальные часы, что и у проверки: у общего правила
     * планировщик свой, и наступившая в проверке полночь до модели не дошла бы вовсе — ожидание
     * суток внутри `Today` считало бы по чужим часам.
     */
    private fun TestScope.model(reads: Reads, clock: Clock): ReportsViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return ReportsViewModel(reads, Today(clock, Quiet))
    }

    private fun at(moment: String) = Clock.fixed(Instant.parse(moment), moscow)

    /** Первый вопрос собирается из пресета и сегодняшнего дня; умолчание — месяц вперёд. */
    @Test
    fun theFirstQuestionIsAskedFromTodayAndThePreset() = runTest {
        val reads = Reads()
        val model = model(reads, at("2027-03-10T09:00:00Z"))

        backgroundScope.launch { model.state.collect { } }
        runCurrent()

        assertEquals(
            listOf(SpendingHorizon(LocalDate.of(2027, 3, 10), LocalDate.of(2027, 4, 10))),
            reads.horizons
        )
    }

    /** Истраченное спрашивается за календарные дни человека — в его зоне, а не в UTC. */
    @Test
    fun spendingIsAskedInTheZoneOfTheHuman() = runTest {
        val reads = Reads()
        val model = model(reads, at("2027-03-10T09:00:00Z"))

        backgroundScope.launch { model.state.collect { } }
        runCurrent()

        assertEquals(
            listOf(SpendingPeriod(LocalDate.of(2027, 2, 10), LocalDate.of(2027, 3, 10)) to moscow),
            reads.periods
        )
    }

    /** Период длиннее года не применяется: предел принадлежит `SpendingPeriod` (ТЗ 4.1.1.10.2). */
    @Test
    fun aPeriodLongerThanAYearIsNotAsked() = runTest {
        val reads = Reads()
        val model = model(reads, at("2027-03-10T09:00:00Z"))
        backgroundScope.launch { model.state.collect { } }
        runCurrent()
        val asked = reads.periods.toList()

        model.choosePeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 3, 10))
        runCurrent()

        assertEquals(asked, reads.periods)
    }

    /** Другой пресет — другой вопрос, и он задаётся от того же сегодняшнего дня. */
    @Test
    fun anotherPresetAsksAnotherQuestion() = runTest {
        val reads = Reads()
        val model = model(reads, at("2027-03-10T09:00:00Z"))
        backgroundScope.launch { model.state.collect { } }
        runCurrent()

        model.choose(HorizonPreset.WEEK)
        runCurrent()

        assertEquals(LocalDate.of(2027, 3, 17), reads.horizons.last().until)
    }

    /**
     * Пока ответ на новый вопрос не пришёл, отчёт показывает ожидание, а не прежние числа под
     * новым заголовком: иначе сменивший срок человек читает чужой период.
     */
    @Test
    fun aChangedQuestionWaitsInsteadOfShowingTheOldAnswer() = runTest {
        val reads = Reads()
        val model = model(reads, at("2027-03-10T09:00:00Z"))
        backgroundScope.launch { model.state.collect { } }
        runCurrent()
        reads.answer(reads.horizons.single())
        runCurrent()
        assertTrue(model.state.value.future is ScreenState.Ready)

        model.choose(HorizonPreset.WEEK)
        runCurrent()

        assertTrue(model.state.value.future is ScreenState.Loading)

        reads.answer(reads.horizons.last())
        runCurrent()
        assertTrue(model.state.value.future is ScreenState.Ready)
    }

    /**
     * **Полночь двигает пресет, а не названную дату.** В 23:30 «месяц» — это 10 апреля; после
     * полуночи тот же чип спрашивает уже об 11-м.
     *
     * Красная проверка: спросить день один раз при создании — открытое с вечера приложение до
     * перезапуска считает вчерашним числом.
     */
    @Test
    fun midnightMovesThePresetOn() = runTest {
        val start = Instant.parse("2027-03-10T20:30:00Z") // 23:30 в Москве
        val running = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = moscow
            override fun withZone(zone: ZoneId): Clock = this
        }
        val reads = Reads()
        val model = model(reads, running)
        backgroundScope.launch { model.state.collect { } }
        runCurrent()

        advanceTimeBy(31 * 60 * 1000L)
        runCurrent()

        assertEquals(LocalDate.of(2027, 4, 10), reads.horizons.first().until)
        assertEquals(LocalDate.of(2027, 4, 11), reads.horizons.last().until)
    }

    /** Названная человеком дата полночь не двигает: он назвал её числом, а не сроком «от сегодня». */
    @Test
    fun midnightLeavesAnOwnDateWhereItWas() = runTest {
        val start = Instant.parse("2027-03-10T20:30:00Z")
        val running = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = moscow
            override fun withZone(zone: ZoneId): Clock = this
        }
        val reads = Reads()
        val model = model(reads, running)
        backgroundScope.launch { model.state.collect { } }
        runCurrent()

        model.chooseUntil(LocalDate.of(2027, 3, 20))
        runCurrent()
        advanceTimeBy(31 * 60 * 1000L)
        runCurrent()

        assertTrue(reads.horizons.drop(1).all { it.until == LocalDate.of(2027, 3, 20) })
    }

    /**
     * Дальше трёх месяцев не спрашивают: негодная дата **не применяется** — прежний вопрос стоит,
     * нового чтения не начинается, исключение наружу не выходит (ТЗ 4.1.1.10.1).
     */
    @Test
    fun aDayBeyondThreeMonthsIsNotAsked() = runTest {
        val reads = Reads()
        val model = model(reads, at("2027-03-10T09:00:00Z"))
        backgroundScope.launch { model.state.collect { } }
        runCurrent()
        val asked = reads.horizons.toList()

        model.chooseUntil(LocalDate.of(2027, 6, 11))
        runCurrent()

        assertEquals(asked, reads.horizons)
    }

    /** День в прошлом — тоже не вопрос: расход считается вперёд, а не назад. */
    @Test
    fun aDayInThePastIsNotAsked() = runTest {
        val reads = Reads()
        val model = model(reads, at("2027-03-10T09:00:00Z"))
        backgroundScope.launch { model.state.collect { } }
        runCurrent()
        val asked = reads.horizons.toList()

        model.chooseUntil(LocalDate.of(2027, 3, 9))
        runCurrent()

        assertEquals(asked, reads.horizons)
    }
}
