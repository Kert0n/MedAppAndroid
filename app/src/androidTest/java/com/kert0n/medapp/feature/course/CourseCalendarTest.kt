package com.kert0n.medapp.feature.course

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.courseRecord
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.intakeRepository
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Календарь лечения — окном 60 дней, и неответ отмечается по концу дня **в зоне курса** (PLAN D6,
 * F4). Сети здесь нет: это приводится в порядок при входе в приложение и фоновым заходом.
 */
@RunWith(AndroidJUnit4::class)
class CourseCalendarTest {

    private lateinit var database: MedAppDatabase

    /** 10 марта 2027, 15:00 в Москве. */
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() = database.close()

    /** Лечение раз в день в 09:00 по Москве с [start], [totalDoses] доз. */
    private suspend fun started(start: LocalDate, totalDoses: Int) {
        val plan = activeCourse(schedule = schedule(start = start, zone = MOSCOW), totalDoses = totalDoses)
        database.courseRepository().activate(CourseDraft.Activation(plan, courseRecord(prescription = plan.prescription)))
    }

    private suspend fun intakes(): List<CourseIntake> =
        database.intakeRepository().ofCourse(COURSE).filterIsInstance<CourseIntake>().sortedBy { it.plannedAt }

    /** Долгий курс лежит в базе окном: пункты не дальше 60 дней, и повтор второго такого же не заводит. */
    @Test
    fun aLongCourseLiesInAWindowOfSixtyDays() = runTest {
        started(LocalDate.of(2027, 3, 10), totalDoses = 365)
        val upkeep = Scenarios(database, now).courseUpkeep

        val first = upkeep.keepUp()
        val second = upkeep.keepUp()

        val planned = intakes()
        assertTrue(planned.all { it.plannedAt.isBefore(now.plus(CourseCalendar.WINDOW)) })
        // Сегодняшний пункт в 09:00 уже прошёл, но день не кончился — он ждёт ответа; и 60 дней вперёд.
        assertEquals(61, planned.size)
        assertEquals(61, first.planned)
        assertEquals(CourseUpkeep.Report(missed = 0, planned = 0), second)
    }

    /** Короткий курс — ровно столько пунктов, сколько доз: окно режет и число доз. */
    @Test
    fun aShortCourseHasAsManyItemsAsDoses() = runTest {
        started(LocalDate.of(2027, 3, 10), totalDoses = 5)

        Scenarios(database, now).courseUpkeep.keepUp()

        assertEquals(
            (10..14).map { LocalDate.of(2027, 3, it) },
            intakes().map { it.slot.localDate }
        )
    }

    /**
     * Неответ наступает с концом дня в зоне курса, а не по UTC: в 23:30 по Москве сегодняшний пункт
     * ещё ждёт, в 00:30 следующего дня он пропущен.
     */
    @Test
    fun aDoseIsMissedWhenItsDayEndsInTheCourseZone() = runTest {
        started(LocalDate.of(2027, 3, 10), totalDoses = 5)
        Scenarios(database, now).courseUpkeep.keepUp()

        Scenarios(database, Instant.parse("2027-03-10T20:30:00Z")).courseUpkeep.keepUp()
        assertEquals(IntakeStatus.PLANNED, intakes().first().status)

        val late = Scenarios(database, Instant.parse("2027-03-10T21:30:00Z")).courseUpkeep.keepUp()
        assertEquals(1, late.missed)
        assertEquals(IntakeStatus.MISSED, intakes().first().status)
    }

    /** Пропуск не сокращает лечение: доза уезжает вперёд, и в конце появляется ещё один пункт (PLAN D5). */
    @Test
    fun aMissedDoseMovesTheEndForward() = runTest {
        started(LocalDate.of(2027, 3, 8), totalDoses = 3)
        val upkeep = Scenarios(database, now).courseUpkeep

        upkeep.keepUp()
        assertEquals((8..10).map { LocalDate.of(2027, 3, it) }, intakes().map { it.slot.localDate })

        upkeep.keepUp()

        val items = intakes()
        assertEquals((8..12).map { LocalDate.of(2027, 3, it) }, items.map { it.slot.localDate })
        assertEquals(
            listOf(IntakeStatus.MISSED, IntakeStatus.MISSED, IntakeStatus.PLANNED, IntakeStatus.PLANNED, IntakeStatus.PLANNED),
            items.map { it.status }
        )
    }
}
