package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.feature.course.CourseCalendar
import com.kert0n.medapp.feature.course.openPlan
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек отказался от приёма по пункту курса (ТЗ 4.1.1.11; PLAN D6). Отказ и неответ — один
 * случай `MISSED` (C1): расхода нет, выделения и брони не меняются, потребность уезжает вперёд, и
 * календарь достраивает ещё один пункт. Лечение отказом не кончается: закончить раньше человек
 * может числом доз.
 *
 * Переход условный — из `PLANNED` (F2, F5): пункт, отвеченный тем временем, не перетирается, и
 * повтор ничего не меняет. Прошлое отмечается раньше (F4). Момент называет человек ([at]):
 * отказаться можно и задним числом.
 */
class IntakeDeclining @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val courses: CourseStorageRepository,
    private val calendar: CourseCalendar,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun decline(intakeId: Uuid, at: Instant): Outcome = transactions.run {
        val intake = intakes.find(intakeId) as? CourseIntake ?: return@run Outcome.GONE
        val record = courses.findRecord(intake.courseId) ?: return@run Outcome.GONE
        if (!record.isOpen) return@run Outcome.EPISODE_CLOSED
        if (intake.status != IntakeStatus.PLANNED) return@run Outcome.ALREADY_ANSWERED
        val course = courses.openPlan(intake.courseId)
        val now = clock.instant()
        calendar.missOverdue(course, now)
        val declined = intakes.record(IntakeOutcome(intake.miss(at), expected = setOf(IntakeStatus.PLANNED), recordedAt = now))
        if (!declined) return@run Outcome.ALREADY_ANSWERED
        // Доза уехала вперёд: окно календаря достраивается на один пункт (F4).
        calendar.extend(course, now)
        Outcome.DECLINED
    }

    /**
     * Чем кончилось. Отказано — пункт показан пропущенным; уже отвечен — экран показывает то, что
     * записано; эпизод закрыт — план ответов не принимает (D6); пункта или эпизода нет — закрыть.
     */
    enum class Outcome { DECLINED, ALREADY_ANSWERED, EPISODE_CLOSED, GONE }
}
