package com.kert0n.medapp.domain.report

import com.kert0n.medapp.fixture.MOSCOW
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Горизонт расхода — с сегодня до дня включительно, не дальше трёх месяцев (ТЗ 4.1.1.10.1). */
class SpendingHorizonTest {

    private val today = LocalDate.of(2027, 3, 10)

    @Test
    fun todayItselfIsAHorizon() {
        SpendingHorizon(today, today)
    }

    @Test
    fun theHorizonLooksForwardNotBack() {
        assertTrue(runCatching { SpendingHorizon(today, today.minusDays(1)) }.isFailure)
    }

    /** Красная проверка: сравнить с тремя месяцами строго — отвергнется законная дата. */
    @Test
    fun threeCalendarMonthsAreAllowedAndADayMoreIsNot() {
        SpendingHorizon(today, LocalDate.of(2027, 6, 10))
        assertTrue(runCatching { SpendingHorizon(today, LocalDate.of(2027, 6, 11)) }.isFailure)
    }

    @Test
    fun boundariesAreMidnightsInTheCourseZone() {
        val horizon = SpendingHorizon(today, today.plusDays(1))

        assertEquals(Instant.parse("2027-03-09T21:00:00Z"), horizon.startsAt(MOSCOW))
        assertEquals(Instant.parse("2027-03-11T21:00:00Z"), horizon.endsBefore(MOSCOW))
    }
}
