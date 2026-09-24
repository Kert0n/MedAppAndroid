package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.course.CourseSchedule
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.intake.IntakeRecords
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Лечение изменилось — врач сменил дозу, расписание или форму, пропуски растянули его — и это
 * **тот же эпизод** (PLAN C1, D5, F5). Одной транзакцией: прошедшие неотвеченные пункты сперва
 * становятся пропущенными — прошлое уже случилось, — затем назначение меняется доменным переходом,
 * выделения зажимаются под новую дозу, план и снимок в записи эпизода ложатся вместе условно по
 * редакции, будущие плановые пункты перестраиваются, а брони общих пачек пересчитываются.
 * Отвеченные пункты — факты со своей плановой дозой, и их это не касается.
 *
 * Сократили число доз до уже принятого — лечение этим и закончилось, тем же закрытием, что после
 * последнего приёма.
 */
class CourseAmendment @Inject constructor(
    private val courses: CourseRecords,
    private val intakes: IntakeRecords,
    private val packages: PackageRecords,
    private val calendar: CourseCalendar,
    private val closing: CourseClosing,
    private val following: CourseFollowing,
    private val transactions: Transactions,
    private val clock: Clock
) {

    /** Изменения [changes] по порядку: ложатся все или ни одно. [expected] — редакция, которую видел экран. */
    suspend fun amend(id: Uuid, expected: Revision, changes: List<Change>): Outcome = transactions.run {
        val record = courses.findRecord(id) ?: return@run Outcome.Gone
        if (!record.isOpen) return@run Outcome.AlreadyFinished
        val before = courses.openPlan(id)
        if (before.revision != expected) return@run Outcome.Stale
        val now = clock.instant()
        var changed = before
        for (change in changes) {
            changed = when (change) {
                is Change.SetDose -> changed.changeDose(change.dose, now)
                is Change.SetForm -> changed.changeForm(change.form, now)
                is Change.SetSchedule -> changed.changeSchedule(change.schedule, now)
                is Change.SetTotalDoses -> changed.setTotalDoses(change.totalDoses, now)
            }.getOrElse { failure -> return@run Outcome.Rejected((failure as? CourseRejected ?: throw failure).reason) }
        }
        if (changed === before) return@run Outcome.Amended(before.projection())

        // Прошлое до изменения: неответ, чей день кончился, — пропуск по прежнему плану.
        calendar.missOverdue(before, now)
        val progress = CourseProgress.of(intakes.ofCourse(id).filterIsInstance<CourseIntake>())
        val completion = CourseCompletion(changed, progress)
        val course = if (completion.reached) {
            changed
        } else {
            changed.clamped(changed.remainingDoses(progress), packages.availabilityFor(changed), now)
        }
        if (!courses.amend(course, expected)) return@run Outcome.Stale

        if (completion.reached) {
            val amended = courses.findRecord(id).readThisTransaction("запись эпизода")
            val ofCourse = intakes.ofCourse(id).filterIsInstance<CourseIntake>()
            closing.close(course, CourseCompletion.Closing.of(amended, CourseRecord.Outcome.COMPLETED, ofCourse, now), now)
            return@run Outcome.Finished
        }
        calendar.replan(course, now)
        // Бронь — `выделено × доза`: изменилась доза или зажим — изменилась и она (PLAN D5).
        following.announceClaims(before, course, now)
        Outcome.Amended(course.projection())
    }

    /** Что человек изменил в назначении. Название и заметка правятся у записи эпизода отдельно. */
    sealed interface Change {
        data class SetDose(val dose: Dose) : Change
        data class SetForm(val form: DosageForm) : Change
        data class SetSchedule(val schedule: CourseSchedule) : Change
        data class SetTotalDoses(val totalDoses: Doses) : Change
    }

    /**
     * Чем кончилось. Изменили — экран показывает новый план; лечение этим закончилось — открыть
     * историю; уже закончено или эпизода нет — закрыть; устарело — перечитать; отказ домена —
     * причина по месту.
     */
    sealed interface Outcome {
        data class Amended(val course: CourseProjection) : Outcome
        data object Finished : Outcome
        data object AlreadyFinished : Outcome
        data object Gone : Outcome
        data object Stale : Outcome
        data class Rejected(val reason: CourseRejected.Reason) : Outcome
    }
}
