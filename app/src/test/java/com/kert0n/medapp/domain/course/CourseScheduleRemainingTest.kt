package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.value.doses
import com.kert0n.medapp.fixture.BERLIN
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.progress
import com.kert0n.medapp.fixture.schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Сколько осталось — знает курс: назначенное число доз за вычетом принятых. Календарь только
 * раскладывает их по дням, а окно материализации в шестьдесят дней (PLAN F4) полного размера
 * плана не знает и знать не должно.
 */
class CourseScheduleRemainingTest {

    private val start: LocalDate = LocalDate.of(2027, 3, 1).with(DayOfWeek.MONDAY)

    private val fourTimesADay = schedule(
        start = start,
        times = listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0), LocalTime.of(22, 0))
    )

    @Test
    fun wholeYearIsCountedBeyondTheSixtyDayWindow() {
        // 365 дней по четыре приёма: окно бы дало 240, а назначено 1460 — и все впереди.
        val year = activeCourse(schedule = fourTimesADay, totalDoses = 365 * 4)
        assertEquals((365 * 4).doses, year.remainingDoses(CourseProgress.none))
        assertEquals(start.plusDays(364), year.expectedEnd(CourseProgress.none)?.localDate)
    }

    /**
     * Черновик с числом доз сверх всякой меры либо не становится лечением, либо лечение по нему
     * отвечает, сколько осталось, — но расчёт не падает: обеспечение считается на каждое
     * изменение базы, и упавший расчёт ронял бы приложение при каждом запуске.
     *
     * Красная проверка: черновик на `Int.MAX_VALUE` доз начинался, а `remainingOccurrences`
     * выделял список на всё число и падал `OutOfMemoryError`.
     */
    @Test
    fun anAbsurdNumberOfDosesDoesNotCrashWhatIsLeft() {
        val draft = course(dose = dose("1"), form = TABLET_FORM, schedule = fourTimesADay, totalDoses = Int.MAX_VALUE)

        draft.activate(EARLIER).onSuccess { it.course.remainingOccurrences(CourseProgress.none) }
    }

    @Test
    fun takenDosesAreNotNeededAgain() {
        val week = activeCourse()
        assertEquals(5.doses, week.remainingDoses(week.progress(taken = 2)))
    }

    @Test
    fun aMissedDoseIsStillNeededAndMovesTheEnd() {
        // Пропуск не уменьшает потребность — потребность уезжает вперёд.
        val week = activeCourse()
        val afterAMiss = week.progress(missed = 1)
        assertEquals(7.doses, week.remainingDoses(afterAMiss))
        assertEquals(week.schedule.start.plusDays(7), week.expectedEnd(afterAMiss)?.localDate)
    }

    @Test
    fun finishedTreatmentNeedsNothing() {
        val week = activeCourse()
        assertEquals(0.doses, week.remainingDoses(week.progress(taken = 7)))
        assertNull(week.expectedEnd(week.progress(taken = 7)))
        // Принято больше назначенного — потребность ноль, а не долг.
        assertEquals(0.doses, week.remainingDoses(week.progress(taken = 9)))
    }

    @Test
    fun shorteningTheTotalEndsTheTreatmentEarlierAndRaisesTheRevision() {
        // Хочет закончить раньше — сокращает число доз рукой; отдельного «отказался» не нужно.
        val week = activeCourse()
        val shortened = week.setTotalDoses(3.doses, week.updatedAt.plusSeconds(1)).getOrThrow()
        assertEquals(3.doses, shortened.totalDoses)
        assertEquals(week.revision.next(), shortened.revision)
        assertEquals(week.schedule.start.plusDays(2), shortened.expectedEnd(CourseProgress.none)?.localDate)
        assertEquals(week.schedule.beginning.plusSeconds(1).let { shortened.updatedAt }, shortened.updatedAt)
    }

    @Test
    fun twoDosesInsideOneMissingHourStayTwoDoses() {
        // Оба времени сдвигаются в один момент; это разные назначенные пункты, и оба нужны.
        val transition = LocalDate.of(2027, 3, 28)
        val day = schedule(
            start = transition,
            daysOfWeek = setOf(DayOfWeek.SUNDAY),
            times = listOf(LocalTime.of(2, 15), LocalTime.of(2, 45)),
            zone = BERLIN
        )
        val found = day.next(day.beginning, 2)
        assertEquals(listOf(LocalTime.of(2, 15), LocalTime.of(2, 45)), found.map { it.localTime })
        assertEquals(found[0].at, found[1].at)
    }

    @Test
    fun remainingOccurrencesSkipTheAnsweredOnes() {
        val week = activeCourse()
        val ahead = week.remainingOccurrences(week.progress(taken = 3))
        assertEquals(4, ahead.size)
        assertEquals(week.schedule.start.plusDays(3), ahead.first().localDate)
        assertEquals(week.schedule.start.plusDays(6), ahead.last().localDate)
    }

    @Test
    fun remainingDosesFallOnTheUnansweredSlotsAndNotOnTheNextOnesAfterAMoment() {
        // Три приёма в день, назначено три дозы. Подтвердили 13:00, а 09:00 ещё не отвечен:
        // остаток — «09:00 и 18:00», а не «09:00 и 13:00» — число само по себе этого не выражает.
        val day = activeCourse(
            schedule = schedule(start = start, times = listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0))),
            totalDoses = 3
        )
        val slots = day.schedule.next(day.schedule.beginning, 3)
        val noonTaken = CourseProgress(taken = setOf(slots[1]))

        assertEquals(2.doses, day.remainingDoses(noonTaken))
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), day.remainingOccurrences(noonTaken).map { it.localTime })
        assertEquals(slots[2], day.expectedEnd(noonTaken))
    }

    /** Пункт — это дата и время: тот же пункт с другим моментом дважды не принимается. */
    @Test(expected = IllegalArgumentException::class)
    fun oneSlotIsNotTakenTwiceUnderDifferentMoments() {
        val slot = schedule().next(schedule().beginning, 1).single()
        CourseProgress(taken = setOf(slot, slot.copy(at = slot.at.plusSeconds(3600))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oneSlotIsNotBothTakenAndMissedUnderDifferentMoments() {
        val slot = schedule().next(schedule().beginning, 1).single()
        CourseProgress(taken = setOf(slot), missed = setOf(slot.copy(at = slot.at.plusSeconds(3600))))
    }

    @Test
    fun aLateAnswerToAMissedDoseMovesTheEndBack() {
        // Пропустили первый день — конец уехал на восьмой; ответили по нему позже — вернулся на
        // седьмой, и восьмой пункт стал лишним.
        val week = activeCourse()
        val slots = week.schedule.next(week.schedule.beginning, 8)
        val missed = CourseProgress(missed = setOf(slots[0]))
        val answeredLate = CourseProgress(taken = setOf(slots[0]))

        assertEquals(slots[7], week.expectedEnd(missed))
        assertEquals(slots[6], week.expectedEnd(answeredLate))
        assertEquals(false, slots[7] in week.remainingOccurrences(answeredLate))
    }
}
