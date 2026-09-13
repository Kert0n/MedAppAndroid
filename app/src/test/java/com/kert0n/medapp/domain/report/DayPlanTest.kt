package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.plannedIntake
import com.kert0n.medapp.fixture.progress
import com.kert0n.medapp.fixture.unplannedIntake
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * План на дату — записанные пункты с ответами, ожидаемые пункты идущих лечений дальше окна и
 * разовые приёмы дня, по времени (PLAN H6). Курс фикстуры: с 1 марта 2027 раз в день в 09:00 по
 * Москве, семь доз.
 */
class DayPlanTest {

    private val course = activeCourse()
    private val records = mapOf(COURSE to courseRecord(title = "Парацетамол").projection())

    private fun day(n: Int) = LocalDate.of(2027, 3, n)

    private fun written(n: Int, id: Uuid = Uuid.random()) = plannedIntake(
        id = id, scheduledOn = day(n), plannedAt = ZonedDateTime.of(day(n), LocalTime.of(9, 0), MOSCOW).toInstant()
    )

    /** Записанный пункт показывается сам и ожидаемым не дублируется. */
    @Test
    fun aWrittenItemIsShownOnceWithItsStatus() {
        val third = written(3)

        val plan = DayPlan.of(day(3), listOf(third), emptyList(), listOf(CourseInProgress(course, course.progress(taken = 2))), records)

        val item = plan.items.single() as DayPlan.Item.Scheduled
        assertEquals(IntakeStatus.PLANNED, item.intake.status)
        assertEquals("Парацетамол", item.title)
    }

    /** Дальше окна календаря строки нет, а доза на день приходится: ожидаемый пункт с дозой курса. */
    @Test
    fun beyondTheWindowTheDoseIsExpected() {
        val plan = DayPlan.of(day(5), emptyList(), emptyList(), listOf(CourseInProgress(course, course.progress(taken = 2))), records)

        val item = plan.items.single() as DayPlan.Item.Expected
        assertEquals(day(5), item.slot.localDate)
        assertEquals(dose("2"), item.dose)
    }

    /**
     * Пропуск 1 марта сдвигает седьмую дозу на 8 марта; без пропуска 8-е пусто.
     *
     * Красная проверка: брать дни расписания, а не оставшиеся дозы, — 8-е будет пустым и с пропуском.
     */
    @Test
    fun aMissedItemMovesTheLastDoseForward() {
        val withMiss = DayPlan.of(day(8), emptyList(), emptyList(), listOf(CourseInProgress(course, course.progress(missed = 1))), records)
        val withoutMiss = DayPlan.of(day(8), emptyList(), emptyList(), listOf(CourseInProgress(course, course.progress())), records)

        assertEquals(1, withMiss.items.size)
        assertTrue(withoutMiss.isEmpty)
    }

    /** Отменённый пункт законченного лечения показывается со статусом; ожидать по нему нечего. */
    @Test
    fun aCancelledItemOfAClosedCourseIsShownAsItIs() {
        val cancelled = written(4).cancel(written(4).plannedAt)

        val plan = DayPlan.of(day(4), listOf(cancelled), emptyList(), emptyList(), records)

        assertEquals(IntakeStatus.CANCELLED, (plan.items.single() as DayPlan.Item.Scheduled).intake.status)
    }

    @Test
    fun itemsGoByTimeAndOneOffsAreIncluded() {
        val morning = ZonedDateTime.of(day(3), LocalTime.of(8, 0), MOSCOW).toInstant()
        val oneOff = unplannedIntake(taken = pack(id = PACK), takenAt = morning)

        val plan = DayPlan.of(day(3), listOf(written(3)), listOf(oneOff), emptyList(), records)

        assertEquals(listOf(DayPlan.Item.OneOff::class, DayPlan.Item.Scheduled::class), plan.items.map { it::class })
    }

    @Test
    fun aDayBeforeTheCourseIsEmpty() {
        assertTrue(DayPlan.of(day(1).minusDays(1), emptyList(), emptyList(), listOf(CourseInProgress(course, course.progress())), records).isEmpty)
    }
}
