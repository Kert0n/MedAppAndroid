package com.kert0n.medapp.domain.course

import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.progress
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.availability
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.domain.value.doses
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kert0n.medapp.domain.pack.Availability

/**
 * Обеспечение вычисляется и называет первый непокрытый приём (PLAN D5).
 *
 * Человеку нужно не «не хватает 19», а «не хватает с такого-то приёма»: расписание при нехватке
 * не трогается, поэтому сообщить можно только про обеспечение.
 */
class CourseCoverageTest {

    /** Неделя по четыре приёма в день — те самые 28 пунктов из приёмки PLAN. */
    private val fourTimesADay = schedule(
        times = listOf(
            LocalTime.of(9, 0),
            LocalTime.of(13, 0),
            LocalTime.of(18, 0),
            LocalTime.of(22, 0)
        )
    )

    /** Назначены те самые 28 доз, и с начала календаря они ложатся на неделю. */
    private val remaining: List<ScheduledOccurrence> = fourTimesADay.next(fourTimesADay.beginning, 28)

    private val availability = availability(PACK to tablets("20"), OTHER_PACK to tablets("12"))

    private fun twoPacks(first: Int, second: Int) = activeCourse(
        schedule = fourTimesADay,
        totalDoses = 28,
        sources = listOf(source(PACK, first), source(OTHER_PACK, second))
    )

    private fun Course.coverage(availability: Availability = this@CourseCoverageTest.availability) =
        coverage(CourseProgress.none, availability)

    @Test
    fun twentyEightNeededWithFiveAndFourAllocatedCoversNineAndNamesTheFirstGap() {
        assertEquals(28, remaining.size)
        val found = twoPacks(first = 5, second = 4).coverage()
        assertEquals(28.doses, found.requiredDoses)
        assertEquals(9.doses, found.coveredDoses)
        assertEquals(19.doses, found.missingDoses)
        assertFalse(found.isFullyCovered)
        assertEquals(remaining[8].at, found.coveredUntil)
        assertEquals(remaining[9].at, found.firstUncoveredAt)
    }

    @Test
    fun fullyCoveredCourseNamesNoGap() {
        val week = schedule()
        val plan = week.next(week.beginning, 7)
        val found = activeCourse(sources = listOf(source(PACK, 7))).coverage()
        assertTrue(found.isFullyCovered)
        assertEquals(plan.last().at, found.coveredUntil)
        assertNull(found.firstUncoveredAt)
    }

    @Test
    fun unsuppliedCourseIsCoveredFromTheVeryFirstIntake() {
        val found = activeCourse(schedule = fourTimesADay, totalDoses = 28).coverage()
        assertEquals(0.doses, found.coveredDoses)
        assertNull(found.coveredUntil)
        assertEquals(remaining.first().at, found.firstUncoveredAt)
    }

    @Test
    fun allocationBeyondWhatThePackageGivesIsNotCoverage() {
        // Выделено девять доз, а свободно шесть таблеток — три дозы. Обеспечение честно меньше
        // выделенного, и человек видит, почему.
        val shrunk = availability(PACK to tablets("6"), OTHER_PACK to tablets("12"))
        val found = twoPacks(first = 9, second = 0).coverage(shrunk)
        assertEquals(3.doses, found.coveredDoses)
        assertEquals(9.doses, found.perSource.first().allocatedDoses)
        assertEquals(3.doses, found.perSource.first().coveredDoses)
    }

    @Test
    fun remainderSmallerThanADoseStaysInItsRowAndDoesNotSpill() {
        // По одной таблетке в двух пачках при дозе в две: ноль покрытых приёмов, и остатки
        // видны каждый в своей строке, а не сложились в одну дозу.
        val singles = availability(PACK to tablets("1"), OTHER_PACK to tablets("1"))
        val found = twoPacks(first = 5, second = 4).coverage(singles)
        assertEquals(0.doses, found.coveredDoses)
        assertEquals(listOf(tablets("1"), tablets("1")), found.perSource.map { it.leftover })
    }

    @Test
    fun leftoverIsWhatCannotMakeAWholeDose() {
        val odd = availability(PACK to tablets("5"), OTHER_PACK to tablets("12"))
        val found = twoPacks(first = 2, second = 0).coverage(odd)
        assertEquals(tablets("1"), found.perSource.first().leftover)
        assertEquals(tablets("0"), found.perSource.last().leftover)
    }

    @Test
    fun aSourceMissingFromTheAvailabilityIsACallerError() {
        // Число есть у каждой пачки: расклад без источника — не «неизвестно», а ошибка того, кто
        // его собрал, и молча она не проходит.
        val partial = availability(OTHER_PACK to tablets("12"))
        assertThrows(IllegalArgumentException::class.java) {
            twoPacks(first = 5, second = 4).coverage(partial)
        }
    }

    /**
     * Граница ползунка лежит в строке источника и считается теми же входами: первой пачке —
     * не больше того, что она даёт (10 доз из 20 таблеток), и не больше, чем потребность
     * оставляет сверх выделенного второй (28 − 4); второй — 6 доз из 12 таблеток.
     */
    @Test
    fun eachRowNamesTheCeilingOfItsOwnSlider() {
        val found = twoPacks(first = 5, second = 4).coverage()
        assertEquals(10.doses, found.perSource[0].maxDoses)
        assertEquals(6.doses, found.perSource[1].maxDoses)

        // Потребность почти выбрана первой: второй остаётся столько, сколько потребность оставляет.
        val nearlyAllocated = twoPacks(first = 26, second = 0).coverage()
        assertEquals(2.doses, nearlyAllocated.perSource[1].maxDoses)
        assertEquals(10.doses, nearlyAllocated.perSource[0].maxDoses)
    }

    @Test
    fun coverageNeverExceedsTheNeed() {
        // Выделено больше, чем осталось приёмов: обеспечено ровно столько, сколько нужно.
        val found = activeCourse(sources = listOf(source(PACK, 10))).coverage()
        assertEquals(7.doses, found.requiredDoses)
        assertEquals(7.doses, found.coveredDoses)
        assertNull(found.firstUncoveredAt)
    }

    @Test
    fun everythingTakenNeedsNothing() {
        val found = twoPacks(first = 5, second = 4)
            .let { it.coverage(it.progress(taken = 28), availability) }
        assertEquals(0.doses, found.requiredDoses)
        assertTrue(found.isFullyCovered)
        assertNull(found.coveredUntil)
        assertNull(found.firstUncoveredAt)
    }

    @Test
    fun requiredDosesAreWhatIsPrescribedMinusWhatIsTaken() {
        // Потребность — от назначенного числа, а не от окна календаря: приняли пять из
        // двадцати восьми — впереди двадцать три, и первая из них ложится на шестой пункт.
        val found = twoPacks(first = 5, second = 4)
            .let { it.coverage(it.progress(taken = 5), availability) }
        assertEquals(23.doses, found.requiredDoses)
        assertEquals(remaining[5].at, found.coveredUntil?.let { remaining[5].at })
    }

    @Test
    fun aMissedDoseMovesTheEndAndDoesNotShortenTheCourse() {
        // Пропуск первого дня: осталось те же семь доз, они ложатся на следующие семь дней, и
        // ожидаемый конец сдвигается на день — календарь говорит когда, а не до какого числа.
        val week = schedule()
        val course = activeCourse(sources = listOf(source(PACK, 7)))
        val firstMissed = course.progress(missed = 1)
        val ahead = course.remainingOccurrences(firstMissed)
        assertEquals(7, ahead.size)
        assertEquals(week.start.plusDays(1), ahead.first().localDate)
        assertEquals(week.start.plusDays(7), course.expectedEnd(firstMissed)?.localDate)
        assertEquals(week.start.plusDays(6), course.expectedEnd(CourseProgress.none)?.localDate)
        assertNull(course.expectedEnd(course.progress(taken = 7)))
    }
}
