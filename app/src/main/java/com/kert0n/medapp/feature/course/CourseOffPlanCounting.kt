package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек правит счёт доз, принятых мимо плана (PLAN D5, C1 «Воздушные дозы»): из кармана, из
 * чужой аптечки, не тем приёмом. Это не приём, а поправка к счёту курса: остатка пачек она не
 * касается и в аналитику не входит; потребность уменьшается, а с ней и бронь — по порядку
 * расходования, как ушла бы плановая доза, — и уезжает разницей. Доз впереди не осталось —
 * лечение закончено.
 *
 * Условно по редакции, которую видел экран (F5); прошлое отмечается раньше, чем лечение трогают
 * (F4). Уменьшение счёта бронь обратно не растит: снимать её решал человек.
 */
class CourseOffPlanCounting @Inject constructor(
    private val courses: CourseStorageRepository,
    private val intakes: IntakeStorageRepository,
    private val packages: PackageStorageRepository,
    private val calendar: CourseCalendar,
    private val following: CourseFollowing,
    private val closing: CourseClosing,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun set(courseId: Uuid, expected: Revision, total: Doses): Outcome = transactions.run {
        val record = courses.findRecord(courseId) ?: return@run Outcome.Gone
        if (!record.isOpen) return@run Outcome.AlreadyFinished
        val before = courses.openPlan(courseId)
        if (before.revision != expected) return@run Outcome.Stale
        val now = clock.instant()
        calendar.missOverdue(before, now)
        val course = before.setTakenOffPlan(total, packages.availabilityFor(before), now)
        if (course === before) return@run Outcome.Set(before.projection())
        check(courses.reallocate(CourseReallocation(course, expected))) { "план прочитан этой же транзакцией" }
        following.announceClaims(before, course, now)

        val ofCourse = intakes.ofCourse(courseId).filterIsInstance<CourseIntake>()
        val progress = CourseProgress.of(ofCourse)
        val completion = CourseCompletion(course, progress)
        if (completion.reached) {
            val amended = checkNotNull(courses.findRecord(courseId)) { "запись эпизода прочитана этой же транзакцией" }
            closing.close(course, CourseCompletion.Closing.of(amended, CourseRecord.Outcome.COMPLETED, ofCourse, now), now)
            return@run Outcome.Finished
        }
        // Доз впереди стало меньше — лишние плановые пункты не факты и уходят.
        calendar.prune(course, course.remainingOccurrences(progress).toSet(), now)
        Outcome.Set(course.projection())
    }

    /**
     * Чем кончилось. Счёт записан — экран показывает план; лечение этим закончилось — открыть
     * историю; уже закончено или эпизода нет — закрыть; устарело — перечитать.
     */
    sealed interface Outcome {
        data class Set(val course: CourseProjection) : Outcome
        data object Finished : Outcome
        data object AlreadyFinished : Outcome
        data object Gone : Outcome
        data object Stale : Outcome
    }
}
