package com.kert0n.medapp.domain.course

import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * Расписание — календарное намерение со своей зоной. Проверяются инварианты D5 и подсчёт
 * пунктов; разрешение перехода на летнее время сюда не входит — это вычисление, а не намерение.
 */
class CourseScheduleTest {

    private val monday: LocalDate = LocalDate.of(2027, 3, 1).with(DayOfWeek.MONDAY)

    @Test
    fun zoneIsPartOfTheSchedule() {
        // Перелёт не сдвигает лечение молча: «девять утра» — девять утра зоны курса, и другая
        // зона означает другое расписание, а не то же самое.
        assertNotEquals(schedule(zone = MOSCOW), schedule(zone = ZoneId.of("UTC")))
    }

    @Test
    fun scheduleHasNoEndOfItsOwn() {
        // Конец лечения — следствие числа доз, и календарь его не знает: два расписания с одним
        // началом, днями и временами — одно расписание.
        assertEquals(schedule(start = monday), schedule(start = monday))
        assertNotEquals(schedule(start = monday), schedule(start = monday.plusDays(1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyDayMaskIsRejected() {
        // Пустая маска — не «каждый день», а расписание без единого приёма.
        schedule(daysOfWeek = emptySet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyTimesAreRejected() {
        schedule(times = emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun repeatedTimeIsRejected() {
        // Одно и то же время дважды — это один приём, а не два.
        schedule(times = listOf(LocalTime.of(9, 0), LocalTime.of(9, 0)))
    }

    /**
     * Секунды в назначении не значат ничего, а хранение держит минуты: допустив 09:00:10 и
     * 09:00:50, расписание называло бы разными два описания одного приёма.
     */
    @Test(expected = IllegalArgumentException::class)
    fun timeWithSecondsIsRejected() {
        schedule(times = listOf(LocalTime.of(9, 0, 10)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsortedTimesAreRejected() {
        schedule(times = listOf(LocalTime.of(21, 0), LocalTime.of(9, 0)))
    }

    @Test
    fun fourteenDosesTwiceADayTakeAWeek() {
        val twiceADay = schedule(start = monday, times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)))
        val found = twiceADay.next(twiceADay.beginning, 14).toList()
        assertEquals(14, found.size)
        assertEquals(monday.plusDays(6), found.last().localDate)
        assertEquals(LocalTime.of(21, 0), found.last().localTime)
    }

    @Test
    fun eightDosesOnTwoWeekdaysTakeTwoWeeks() {
        val twoWeekdays = schedule(
            start = monday,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))
        )
        assertEquals(monday.plusDays(9), twoWeekdays.next(twoWeekdays.beginning, 8).toList().last().localDate)
    }

    @Test
    fun twoMondayDosesLandOnTheFirstAndEighthDay() {
        val mondays = schedule(start = monday, daysOfWeek = setOf(DayOfWeek.MONDAY))
        assertEquals(
            listOf(monday, monday.plusDays(7)),
            mondays.next(mondays.beginning, 2).toList().map { it.localDate }
        )
    }

    @Test
    fun countingStartsFromTheGivenMomentNotFromTheStart() {
        // С середины недели семь доз ложатся на семь следующих дней: пропущенное не исчезает,
        // а сдвигает конец.
        val week = schedule(start = monday)
        val thursday = monday.plusDays(3).atStartOfDay(MOSCOW).toInstant()
        assertEquals(monday.plusDays(9), week.next(thursday, 7).toList().last().localDate)
    }

    @Test
    fun nothingIsNeededWhenNothingRemains() {
        assertTrue(schedule().next(schedule().beginning, 0).toList().isEmpty())
    }

    @Test
    fun changingTheListAfterwardsDoesNotChangeTheSchedule() {
        // `val` защищает ссылку, а не содержимое: без своей копии список, оставшийся у
        // вызывающего, менял бы и действующий курс, и назначение в записи эпизода.
        val times = mutableListOf(LocalTime.of(9, 0))
        val week = schedule(times = times)
        times[0] = LocalTime.of(21, 0)
        assertEquals(listOf(LocalTime.of(9, 0)), week.times)
        assertEquals(schedule(), week)
    }

    @Test
    fun sameScheduleIsTheSameValue() {
        assertEquals(schedule(), schedule())
        assertEquals(schedule().hashCode(), schedule().hashCode())
    }
}
