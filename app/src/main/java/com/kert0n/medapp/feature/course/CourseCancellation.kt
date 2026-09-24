package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.feature.intake.IntakeRecords
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек отменяет лечение (PLAN D5, F5). Эпизод закрывается исходом «отменено» — тем же
 * закрытием, что и состоявшееся лечение: запись остаётся навсегда, план уничтожается, будущие
 * пункты отменены, пачки и брони освобождены. Принятое и пропущенное — факты, и отмена их не
 * трогает. Черновик не отменяют, а удаляют: отменять в нём нечего.
 */
class CourseCancellation @Inject constructor(
    private val courses: CourseRecords,
    private val intakes: IntakeRecords,
    private val calendar: CourseCalendar,
    private val closing: CourseClosing,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun cancel(id: Uuid): Outcome = transactions.run {
        val record = courses.findRecord(id) ?: return@run Outcome.GONE
        if (!record.isOpen) return@run Outcome.ALREADY_FINISHED
        val course = courses.openPlan(id)
        val now = clock.instant()
        // Прошлое до отмены: неответ, чей день кончился, — пропуск, а не отменённый пункт.
        calendar.missOverdue(course, now)
        val ofCourse = intakes.ofCourse(id).filterIsInstance<CourseIntake>()
        closing.close(course, CourseCompletion.Closing.of(record, CourseRecord.Outcome.CANCELLED, ofCourse, now), now)
        Outcome.CANCELLED
    }

    /** Чем кончилось. Отменили; лечение уже закончено — нечего отменять; эпизода нет — это черновик или ошибка номера. */
    enum class Outcome { CANCELLED, ALREADY_FINISHED, GONE }
}
