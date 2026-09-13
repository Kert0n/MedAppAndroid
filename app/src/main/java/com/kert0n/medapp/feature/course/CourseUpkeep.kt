package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Календарь идущих лечений в порядке (PLAN D6, F4): пункт, чей день **в зоне курса** кончился без
 * ответа, становится `MISSED` — отказ и неответ один случай, расхода нет, доза уезжает вперёд, — и
 * окно плановых пунктов достраивается. Выделения и брони это не трогает (D5).
 *
 * Сети здесь нет, и связь ей не нужна: зовут её при входе в приложение и регулярным фоновым
 * заходом (E4). Каждое лечение — своей транзакцией: одно, которое писать некуда, не держит
 * остальные. Проход один: второй вызов ждёт первого.
 */
@Singleton
class CourseUpkeep @Inject constructor(
    private val courses: CourseStorageRepository,
    private val intakes: IntakeStorageRepository,
    private val calendar: CourseCalendar,
    private val transactions: Transactions,
    private val clock: Clock
) {

    private val single = Mutex()

    /** Сколько пунктов пропущено и сколько заведено этим проходом. */
    suspend fun keepUp(): Report = single.withLock {
        val now = clock.instant()
        var missed = 0
        var planned = 0
        for (id in courses.planIds()) {
            transactions.run {
                val course = courses.findPlan(id) ?: return@run
                missed += missOverdue(id, course.schedule.zone, now)
                planned += calendar.extend(course, now)
            }
        }
        Report(missed, planned)
    }

    /**
     * Неотвеченные пункты, чей день кончился, — `MISSED` условным переходом из `PLANNED`: ответ,
     * пришедший тем временем, не перетирается. Моментом ответа служит конец дня пункта — тогда
     * неответ и наступил.
     */
    internal suspend fun missOverdue(courseId: Uuid, zone: ZoneId, now: Instant): Int {
        val today = now.atZone(zone).toLocalDate()
        var missed = 0
        val overdue = intakes.ofCourse(courseId).filterIsInstance<CourseIntake>()
            .filter { it.status == IntakeStatus.PLANNED && it.slot.localDate.isBefore(today) }
        for (intake in overdue) {
            val endOfDay = intake.slot.localDate.plusDays(1).atStartOfDay(zone).toInstant()
            val outcome = IntakeOutcome(intake.miss(endOfDay), expected = setOf(IntakeStatus.PLANNED), recordedAt = now)
            if (intakes.record(outcome)) missed++
        }
        return missed
    }

    data class Report(val missed: Int, val planned: Int)
}
