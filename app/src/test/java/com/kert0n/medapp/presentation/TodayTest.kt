package com.kert0n.medapp.presentation

import com.kert0n.medapp.platform.time.TimeShifts
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Какой сегодня день. Спросить его один раз при создании экрана значит оставить открытое
 * приложение во вчера: коробка, просроченная в полночь, до перезапуска выглядела бы годной
 * (наследство разбора #16).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayTest {

    private val moscow = ZoneId.of("Europe/Moscow")

    @Test
    fun theDayIsTheOneTheDeviceIsLivingIn() = runTest {
        // 21:30 UTC — в Москве это уже следующие сутки: день берётся в местной зоне, а не в UTC.
        val evening = Clock.fixed(Instant.parse("2026-09-15T21:30:00Z"), moscow)

        assertEquals(LocalDate.parse("2026-09-16"), Today(evening).observe().take(1).toList().single())
    }

    /**
     * Следующий день приходит сам, и приходит он в **местную** полночь, а не через двадцать
     * четыре часа после подписки.
     *
     * Красная проверка: отдать день один раз и замолчать — второго значения не будет.
     */
    @Test
    fun theNextDayArrivesAtLocalMidnight() = runTest {
        // Часы идут вслед за ожиданием, как на устройстве: время проверки — время часов.
        val start = Instant.parse("2026-09-15T20:00:00Z") // 23:00 в Москве
        val running = object : Clock() {

            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

            override fun getZone(): ZoneId = moscow

            override fun withZone(zone: ZoneId): Clock = this
        }

        val days = Today(running).observe().take(2).toList()

        assertEquals(listOf(LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-16")), days)
        // Ждали до полуночи, а не сутки: между двумя днями прошёл ровно час.
        assertEquals(Duration.ofHours(1).toMillis(), testScheduler.currentTime)
    }

    /**
     * Человек переехал — день у него сменился сразу, а не по старой полуночи.
     *
     * Ждущий спит до границы суток и сам о переезде не узнаёт: о нём говорит тот же сигнал
     * системы, по которому переставляются будильники.
     *
     * Красная проверка: ждать только полуночи — во Владивостоке уже утро шестнадцатого, а
     * приложение до московской полуночи показывает пятнадцатое.
     */
    @Test
    fun aChangedZoneMovesTheDayAtOnce() = runTest {
        val start = Instant.parse("2026-09-15T20:00:00Z") // 23:00 в Москве, 06:00 во Владивостоке
        var zone = moscow
        val running = object : Clock() {

            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)

            override fun getZone(): ZoneId = zone

            override fun withZone(zone: ZoneId): Clock = this
        }
        val shifts = TimeShifts()
        val days = mutableListOf<LocalDate>()

        backgroundScope.launch { Today(running, shifts).observe().collect { days += it } }
        runCurrent()
        zone = ZoneId.of("Asia/Vladivostok")
        shifts.happened()
        runCurrent()

        assertEquals(listOf(LocalDate.parse("2026-09-15"), LocalDate.parse("2026-09-16")), days)
        // Московская полночь ещё не наступила: день сменила весть, а не ожидание.
        assertEquals(0L, testScheduler.currentTime)
    }
}
