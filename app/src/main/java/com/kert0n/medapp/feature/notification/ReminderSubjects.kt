package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.ReminderSubject
import com.kert0n.medapp.feature.course.CourseRecords
import com.kert0n.medapp.feature.intake.IntakeRecords
import com.kert0n.medapp.feature.packages.PackageReadings
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Предмет обязательства по идентификаторам его цели: курс и доза приёма, коробка и её срок,
 * обеспечение курса. Читается в миг показа, а не при обещании: коробку могли переименовать, курс —
 * закрыть. Не нашлось — `null`, и повода больше нет.
 */
class ReminderSubjects @Inject constructor(
    private val intakes: IntakeRecords,
    private val courses: CourseRecords,
    private val packages: PackageReadings
) {

    suspend fun of(target: NotificationTarget): ReminderSubject? = when (target) {
        is NotificationTarget.Intake -> {
            val intake = intakes.find(target.intakeId) as? CourseIntake
            val course = intake?.let { courses.findRecord(it.courseId) }
            if (intake == null || course == null) null
            else ReminderSubject.Intake(course.title, intake.plannedAmount, intake.plannedPackage?.name)
        }
        is NotificationTarget.PackageCard -> packages.observe(target.packageId).first()?.let { pkg ->
            pkg.facts.expiresOn?.let { ReminderSubject.Expiry(pkg.name, it) }
        }
        is NotificationTarget.CourseSources -> courses.findRecord(target.courseId)?.let { record ->
            val zone = record.prescription.schedule.zone
            val until = courses.observeCoverage(target.courseId).first()?.coveredUntil?.atZone(zone)?.toLocalDate()
            ReminderSubject.Coverage(record.title, until)
        }
        is NotificationTarget.DayPlan -> ReminderSubject.DayPlan(target.date)
        NotificationTarget.SyncStatus -> ReminderSubject.SyncStatus
    }
}
