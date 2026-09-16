package com.kert0n.medapp.domain.notification

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.availability
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила дат уведомлений живут на своих величинах и считают календарь, а не часы (PLAN D8). */
class NotificationRulesTest {

    private val lastDay = LocalDate.of(2027, 3, 31)
    private val expiry = ExpiryDate(lastDay)

    @Test
    fun expiryStagesAreCalendarDaysNotHours() {
        assertEquals(ExpiryDate.Stage.SOURCE_3D, expiry.stageOn(lastDay.minusDays(3)))
        assertEquals(ExpiryDate.Stage.SOURCE_1D, expiry.stageOn(lastDay.minusDays(1)))
        assertEquals(ExpiryDate.Stage.TODAY, expiry.stageOn(lastDay))
        assertNull(expiry.stageOn(lastDay.minusDays(2)))
        assertNull(expiry.stageOn(lastDay.minusDays(4)))
        // Просроченной этапов нет: у неё статус, а не предупреждение.
        assertNull(expiry.stageOn(lastDay.plusDays(1)))
    }

    /**
     * Ночь перехода на летнее время короче на час: считай «за три дня» как `72h` — и день
     * уехал бы (красная проверка). Дата — календарная, и этап стоит на своём дне.
     */
    @Test
    fun daylightSavingDoesNotShiftTheStage() {
        // Ночь 28 марта 2027 в Берлине короче на час: 72 часа от полуночи 27-го кончаются 30-го в 01:00,
        // а не в полночь — считай часами, и «три дня до 30-го» встали бы на другой день.
        val berlin = ZoneId.of("Europe/Berlin")
        val expires = ExpiryDate(LocalDate.of(2027, 3, 30))
        val threeDaysBefore = LocalDate.of(2027, 3, 27)
        val byHours = threeDaysBefore.atStartOfDay(berlin).toInstant().plusSeconds(72 * 3600).atZone(berlin)
        assertEquals(LocalTime.of(1, 0), byHours.toLocalTime())
        assertEquals(ExpiryDate.Stage.SOURCE_3D, expires.stageOn(threeDaysBefore))
        assertNull(expires.stageOn(threeDaysBefore.minusDays(1)))
    }

    @Test
    fun keysTellStagesAndDatesApart() {
        val threeDays = NotificationKey.expiry(PACK, expiry, NotificationKind.EXPIRY_SOURCE_3D)
        val oneDay = NotificationKey.expiry(PACK, expiry, NotificationKind.EXPIRY_SOURCE_1D)
        val corrected = NotificationKey.expiry(PACK, ExpiryDate(lastDay.plusMonths(1)), NotificationKind.EXPIRY_SOURCE_3D)
        assertNotEquals(threeDays, oneDay)
        assertNotEquals(threeDays, corrected)
        assertEquals(threeDays, NotificationKey.expiry(PACK, ExpiryDate(lastDay), NotificationKind.EXPIRY_SOURCE_3D))
        assertNotEquals(NotificationKey.intake(PACK, NotificationKind.INTAKE_DUE), NotificationKey.intake(OTHER_PACK, NotificationKind.INTAKE_DUE))
    }

    @Test
    fun coverageNoticesComeThreeCalendarDaysAheadAndOnTheDayInTheCourseZone() {
        val course = activeCourse(schedule = schedule(start = LocalDate.of(2027, 3, 10), times = listOf(LocalTime.of(1, 0))), totalDoses = 7, sources = listOf(source(PACK, 3)))
        val coverage: CourseCoverage = course.coverage(CourseProgress.none, availability(PACK to tablets("20")))
        // Первый необеспеченный — четвёртый пункт, 13 марта в 01:00 МСК — 12 марта по UTC.
        val uncoveredOn = LocalDate.of(2027, 3, 13)
        assertEquals(uncoveredOn.atTime(1, 0).atZone(MOSCOW).toInstant(), coverage.firstUncoveredAt)
        // Зона — курса, и обеспечение несёт её само: полночь по Москве — ещё вчера по UTC.
        assertEquals(MOSCOW, coverage.zone)
        fun moscowMidnight(day: LocalDate) = day.atStartOfDay(MOSCOW).toInstant()
        assertEquals(CourseCoverage.Notice.AHEAD, coverage.noticeOn(moscowMidnight(uncoveredOn.minusDays(3))))
        assertEquals(CourseCoverage.Notice.END, coverage.noticeOn(moscowMidnight(uncoveredOn)))
        assertNull(coverage.noticeOn(moscowMidnight(uncoveredOn.minusDays(1))))
        // По UTC этот миг — ещё 12 марта; по зоне курса — уже день исчерпания.
        assertEquals(CourseCoverage.Notice.END, coverage.noticeOn(uncoveredOn.atStartOfDay(MOSCOW).toInstant()))
        // Порог 0 — «только в день»: день исчерпания остаётся исчерпанием, а не «скоро».
        assertEquals(CourseCoverage.Notice.END, coverage.noticeOn(moscowMidnight(uncoveredOn), thresholdDays = 0))
        // Полностью обеспеченному предупреждать нечего.
        val covered = activeCourse(totalDoses = 3, sources = listOf(source(PACK, 3))).coverage(CourseProgress.none, availability(PACK to tablets("20")))
        assertNull(covered.noticeOn(moscowMidnight(uncoveredOn)))
    }

    @Test
    fun onlyTheIntakeReminderIsExact() {
        assertTrue(NotificationKind.INTAKE_DUE.exact)
        assertFalse(NotificationKind.entries.filter { it != NotificationKind.INTAKE_DUE }.any { it.exact })
        val planned = Reminder(NotificationKey.digest(lastDay), NotificationTarget.DayPlan(lastDay), Instant.EPOCH)
        assertFalse(planned.exact)
        assertEquals(NotificationChannel.DIGEST, planned.channel)
        assertEquals(NoticeDelivery.SYSTEM, planned.delivery)
        // Доставка внутри приложения — свойство вида, а не решение вызывающего: последний день
        // годности и пропуск, который приходит попапом при входе (D8, C1 «Попап пропущенного»).
        assertEquals(
            setOf(NotificationKind.EXPIRY_TODAY, NotificationKind.INTAKE_MISSED),
            NotificationKind.entries.filter { it.delivery == NoticeDelivery.IN_APP_BANNER }.toSet()
        )
        assertEquals(NotificationChannel.Importance.HIGH, NotificationChannel.INTAKES.importance)
    }

    /** Внимание к очереди — системное, неточное, без действий, на своём канале; ключ один на всю очередь. */
    @Test
    fun syncAttentionIsOneQuietNoticeOnItsOwnChannel() {
        val kind = NotificationKind.SYNC_ATTENTION
        assertEquals(NotificationChannel.SYNC, kind.channel)
        assertEquals(NotificationChannel.Importance.DEFAULT, NotificationChannel.SYNC.importance)
        assertEquals(NoticeDelivery.SYSTEM, kind.delivery)
        assertTrue(!kind.exact)
        assertEquals(emptyList<NotificationAction>(), kind.actions)
        assertEquals(NotificationKey.sync(Uuid.parse("00000000-0000-4000-8000-000000000001")).kind, kind)
    }

    @Test
    fun settingsHoldTheDefaultsOfD8() {
        val settings = NotificationSettings.DEFAULT
        assertEquals(15, settings.snoozeMinutes)
        assertEquals(3L, settings.coverageThresholdDays)
        assertEquals(LocalTime.of(9, 0), settings.digestAt)
        assertTrue(runCatching { NotificationSettings(snoozeMinutes = 0) }.isFailure)
        assertTrue(runCatching { NotificationKey(NotificationKind.DAILY_DIGEST, " ") }.isFailure)
        assertEquals(NotificationKey.reduction(Uuid.parse("00000000-0000-4000-8000-000000000001")).kind, NotificationKind.COVERAGE_SHORT)
    }
}
