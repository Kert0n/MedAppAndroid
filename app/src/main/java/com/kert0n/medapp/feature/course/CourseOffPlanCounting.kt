package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseReallocation
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
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
 * (F4). Уменьшение счёта бронь обратно не растит: снимать её решал человек, — а календарь
 * следует за потребностью в обе стороны.
 */
class CourseOffPlanCounting @Inject constructor(
    private val courses: CourseStorageRepository,
    private val intakes: IntakeStorageRepository,
    private val packages: PackageRecords,
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
        // Прогресс читается **после** отметки прошлого и берёт его во внимание: пропуски этого
        // прохода уже записаны, и предел считается по тому, что в базе, а не по тому, что экран
        // видел до захода (PLAN F4, F5).
        val ofCourse = intakes.ofCourse(courseId).filterIsInstance<CourseIntake>()
        val progress = CourseProgress.of(ofCourse)
        // Мимо плана нельзя принять больше, чем лечению осталось: столько доз ему просто не
        // назначено, и счёт сверх этого — не поправка, а другое число (PLAN D5).
        val limit = before.totalDoses.minusOrNone(progress.takenDoses)
        if (total > limit) return@run Outcome.BeyondPlan(limit)
        val course = before.setTakenOffPlan(total, packages.availabilityFor(before), now)
        if (course === before) return@run Outcome.Set(before.projection())
        courses.reallocate(CourseReallocation(course, expected)).readThisTransaction("план")
        following.announceClaims(before, course, now)

        val completion = CourseCompletion(course, CourseProgress.of(intakes.ofCourse(courseId).filterIsInstance<CourseIntake>()))
        if (completion.reached) {
            val amended = courses.findRecord(courseId).readThisTransaction("запись эпизода")
            closing.close(course, CourseCompletion.Closing.of(amended, CourseRecord.Outcome.COMPLETED, ofCourse, now), now)
            return@run Outcome.Finished
        }
        // Потребность изменилась — в любую сторону: лишние плановые пункты не факты и уходят, а
        // вернувшаяся потребность достраивает окно заново той же дверью, что правка лечения (C1).
        calendar.replan(course, now)
        Outcome.Set(course.projection())
    }

    /**
     * Чем кончилось. Счёт записан — экран показывает план; лечение этим закончилось — открыть
     * историю; уже закончено или эпизода нет — закрыть; устарело — перечитать.
     */
    sealed interface Outcome {
        data class Set(val course: CourseProjection) : Outcome

        /** Больше, чем лечению осталось: столько доз ему не назначено — назван предел (PLAN D5). */
        data class BeyondPlan(val limit: Doses) : Outcome
        data object Finished : Outcome
        data object AlreadyFinished : Outcome
        data object Gone : Outcome
        data object Stale : Outcome
    }
}
