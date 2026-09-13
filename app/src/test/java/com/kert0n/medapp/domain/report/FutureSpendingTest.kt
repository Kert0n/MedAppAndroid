package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.BERLIN
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.progress
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.tablets
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Расход на дату — дозы идущих курсов, которые придутся на горизонт, если все приёмы состоятся
 * (PLAN H6). Курс фикстуры: с понедельника 1 марта 2027 раз в день в 09:00 по Москве, семь доз по
 * две таблетки.
 */
class FutureSpendingTest {

    private val course = activeCourse()
    private val record = courseRecord().projection()

    private fun horizon(today: Int, until: Int) = SpendingHorizon(LocalDate.of(2027, 3, today), LocalDate.of(2027, 3, until))

    private fun spend(plan: CourseInProgress, horizon: SpendingHorizon) =
        FutureSpending.of(listOf(plan), mapOf(COURSE to record), horizon)

    /** Приняты 1 и 2 марта: с 3 по 5 марта — три дозы, шесть таблеток. */
    @Test
    fun theDosesOfTheHorizonAreCountedInTheCourseUnit() {
        val spending = spend(CourseInProgress(course, course.progress(taken = 2)), horizon(3, 5))

        assertEquals(listOf(FutureSpending.Episode(record, Doses(3), tablets("6"))), spending.episodes)
    }

    /**
     * 1 и 2 марта не отвечены, сегодня 3-е: их дозы уехали вперёд, в прошлое расход не считается.
     *
     * Красная проверка: раскладывать с начала курса — на 3–5 марта ляжет одна доза вместо трёх.
     */
    @Test
    fun unansweredPastItemsMoveTheirDosesForward() {
        val spending = spend(CourseInProgress(course, course.progress()), horizon(3, 5))

        assertEquals(Doses(3), spending.episodes.single().doses)
    }

    /** Горизонт длиннее лечения: считается оставшееся, а не дни горизонта. */
    @Test
    fun theHorizonDoesNotAddDosesBeyondTheCourse() {
        val spending = spend(CourseInProgress(course, course.progress(taken = 2)), horizon(3, 31))

        assertEquals(tablets("10"), spending.episodes.single().total)
    }

    @Test
    fun aCourseWithNothingLeftGivesNoRow() {
        assertTrue(spend(CourseInProgress(course, course.progress(taken = 7)), horizon(10, 20)).isEmpty)
    }

    /** Сутки — в зоне курса: берлинский приём в 23:30 3 марта — это 4 марта по Москве, но 3-е в Берлине. */
    @Test
    fun theDaysAreTheCourseDays() {
        val berlin = activeCourse(schedule = schedule(times = listOf(LocalTime.of(23, 30)), zone = BERLIN))

        val spending = spend(CourseInProgress(berlin, berlin.progress(taken = 2)), horizon(3, 3))

        assertEquals(Doses(1), spending.episodes.single().doses)
    }

    @Test
    fun episodesGoFromTheLatestStarted() {
        val other = Uuid.parse("00000000-0000-4000-8000-000000000052")
        val later = activeCourse(id = other)
        val laterRecord = courseRecord(id = other, startedAt = LATER).projection()

        val spending = FutureSpending.of(
            listOf(CourseInProgress(course, course.progress()), CourseInProgress(later, later.progress())),
            mapOf(COURSE to record, other to laterRecord),
            horizon(3, 3)
        )

        assertEquals(listOf(other, COURSE), spending.episodes.map { it.record.id })
    }
}
