package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Шаг внутри чужой транзакции: коробка стала меньше — пересчёт, утилизация, разовый приём, — и
 * лечение, державшее её, зажимается под то, что в ней теперь есть (PLAN D5): выделение не больше
 * доступного, а на общей полке изменившаяся бронь уезжает разницей — `SetClaim` либо
 * `ReleaseClaim`. Расписание не трогается: нехватка меняет обеспечение, а не план (C1).
 * Прошлое отмечается раньше, чем лечение трогают (F4).
 *
 * Зовут это те, кто меняет число коробки, оставшейся у человека: кончившуюся коробку лечение
 * теряет её же концом, и зажимать по ней нечего.
 */
class CourseClamping @Inject constructor(
    private val courses: CourseStorageRepository,
    private val intakes: IntakeStorageRepository,
    private val packages: PackageStorageRepository,
    private val calendar: CourseCalendar,
    private val queue: QueueService
) {

    /** [after] — сколько в [pkg] теперь доступно: названное число или проекция после команды. */
    suspend fun clampTheCourseHolding(pkg: Package, after: Quantity, now: Instant) {
        val course = courses.courseHolding(pkg.id)?.let { courses.openPlan(it) } ?: return
        calendar.missOverdue(course, now)
        val progress = CourseProgress.of(intakes.ofCourse(course.id).filterIsInstance<CourseIntake>())
        val availability = calendar.availabilityOf(course).with(pkg.ref, after)
        val clamped = course.clamped(course.remainingDoses(progress), availability, now)
        if (clamped === course) return
        check(courses.reallocate(CourseReallocation(clamped, course.revision))) { "план прочитан этой же транзакцией" }
        announceClaims(course, clamped, now)
    }

    /**
     * Бронь — `выделено × доза`: изменилось выделение — зажимом, счётом доз мимо плана —
     * изменилась и она, и уезжает разницей по каждой пачке (PLAN D5).
     */
    suspend fun announceClaims(before: Course, after: Course, now: Instant) {
        for (source in after.sources) {
            val claim = after.allocatedOf(source.pkg)
            if (before.allocatedOf(source.pkg) == claim) continue
            val pkg = packages.find(source.pkg.id) ?: continue
            val command = if (claim == null || claim.isZero) {
                PackageSyncCommand.ReleaseClaim(pkg.id)
            } else {
                PackageSyncCommand.SetClaim(pkg.id, claim)
            }
            queue.change(pkg.medKit, listOf(QueuedCommand(Uuid.random(), command)), now) { true }
        }
    }
}
