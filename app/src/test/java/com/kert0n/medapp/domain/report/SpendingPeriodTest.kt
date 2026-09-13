package com.kert0n.medapp.domain.report

import com.kert0n.medapp.fixture.MOSCOW
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Период истраченного — календарные дни включительно и не больше года (ТЗ 4.1.1.10.2; PLAN H6). */
class SpendingPeriodTest {

    private fun day(text: String) = LocalDate.parse(text)

    @Test
    fun aSingleDayIsAPeriod() {
        SpendingPeriod(day("2027-03-10"), day("2027-03-10"))
    }

    @Test
    fun aPeriodDoesNotEndBeforeItStarts() {
        assertTrue(runCatching { SpendingPeriod(day("2027-03-10"), day("2027-03-09")) }.isFailure)
    }

    /** Красная проверка: сравнить `to` с `from + 1 год` включительно — пройдёт год и день. */
    @Test
    fun exactlyAYearIsAllowedAndADayMoreIsNot() {
        SpendingPeriod(day("2027-03-10"), day("2028-03-09"))
        assertTrue(runCatching { SpendingPeriod(day("2027-03-10"), day("2028-03-10")) }.isFailure)
    }

    @Test
    fun aYearFromTheLeapDayEndsOnTheLastFebruaryDay() {
        SpendingPeriod(day("2028-02-29"), day("2029-02-27"))
        assertTrue(runCatching { SpendingPeriod(day("2028-02-29"), day("2029-02-28")) }.isFailure)
    }

    /** Дни — местные: полночь по Москве, а не по UTC. */
    @Test
    fun boundariesAreLocalMidnights() {
        val period = SpendingPeriod(day("2027-03-10"), day("2027-03-11"))

        assertEquals(Instant.parse("2027-03-09T21:00:00Z"), period.startsAt(MOSCOW))
        assertEquals(Instant.parse("2027-03-11T21:00:00Z"), period.endsBefore(MOSCOW))
    }
}
