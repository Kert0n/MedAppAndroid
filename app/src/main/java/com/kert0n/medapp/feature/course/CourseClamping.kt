package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.pack.claimChangesSince
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Шаг внутри чужой транзакции: коробка стала меньше — пересчёт, утилизация, разовый приём, — и
 * лечение, державшее её, следует за ней той же дверью, что и укладка снимка:
 * `CourseStorageRepository.clampHolding` (PLAN D5). Выделение — не больше **доступного мне**,
 * посчитанного от того же числа, которое человек видит на экране; на общей полке изменившаяся
 * бронь уезжает разницей. Расписание не трогается: нехватка меняет обеспечение, а не план (C1).
 * Прошлое отмечается раньше, чем лечение трогают (F4).
 *
 * Зовут это те, кто меняет число коробки, оставшейся у человека: кончившуюся коробку лечение
 * теряет её же концом, и зажимать по ней нечем.
 */
class CourseClamping @Inject constructor(
    private val courses: CourseStorageRepository,
    private val packages: PackageStorageRepository,
    private val calendar: CourseCalendar,
    private val queue: QueueService
) {

    /** Своё действие в коробке уже записано этой транзакцией: дверь читает её такой, какая она теперь. */
    suspend fun clampTheCourseHolding(pkg: Package, now: Instant) {
        courses.courseHolding(pkg.id)?.let { calendar.missOverdue(courses.openPlan(it), now) }
        for (followed in courses.clampHolding(pkg.id, now)) announceClaims(followed.before, followed.after, now)
    }

    /**
     * Бронь — `выделено × доза`: изменилось выделение — зажимом, счётом доз мимо плана —
     * изменилась и она, и уезжает разницей по каждой пачке (PLAN D5, E2).
     */
    suspend fun announceClaims(before: Course, after: Course, now: Instant) {
        for (command in after.claimChangesSince(before)) {
            val pkg = packages.find(command.packageId) ?: continue
            queue.change(pkg.medKit, listOf(QueuedCommand(Uuid.random(), command)), now) { true }
        }
    }
}
