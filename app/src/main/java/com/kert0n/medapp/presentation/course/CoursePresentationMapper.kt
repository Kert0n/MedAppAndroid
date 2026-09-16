package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.course.CourseSchedule
import com.kert0n.medapp.presentation.value.toPresentationDTO

/** Черновик: назначение — что успели записать; нехватки и конца у него нет. */
fun CourseDraftProjection.toPresentationDTO(): CoursePresentationDTO = CoursePresentationDTO(
    id = id,
    title = title,
    note = note,
    kind = CoursePresentationDTO.Kind.DRAFT,
    dose = dose?.quantity?.toPresentationDTO(),
    form = form?.toPresentationDTO(),
    schedule = schedule?.toPresentationDTO(),
    totalDoses = totalDoses?.count,
    shortage = null,
    closedOn = null
)

/**
 * Запись эпизода: идущее лечение — с нехваткой по [coverage], законченное — с днём конца.
 * Нехватка есть только у идущего: у закрытого потребности нет, и обеспечение ему не считают.
 */
fun CourseRecordProjection.toPresentationDTO(coverage: CourseCoverage?): CoursePresentationDTO = CoursePresentationDTO(
    id = id,
    title = title,
    note = note,
    kind = when (outcome) {
        null -> CoursePresentationDTO.Kind.RUNNING
        CourseRecord.Outcome.COMPLETED -> CoursePresentationDTO.Kind.COMPLETED
        CourseRecord.Outcome.CANCELLED -> CoursePresentationDTO.Kind.CANCELLED
    },
    dose = prescription.dose.quantity.toPresentationDTO(),
    form = prescription.form.toPresentationDTO(),
    schedule = prescription.schedule.toPresentationDTO(),
    totalDoses = prescription.totalDoses.count,
    shortage = coverage?.takeIf { isOpen }?.toShortagePresentationDTO(),
    closedOn = closedAt?.atZone(prescription.schedule.zone)?.toLocalDate()
)

fun CourseSchedule.toPresentationDTO(): SchedulePresentationDTO =
    SchedulePresentationDTO(start = start, days = daysOfWeek, times = times, zone = zone)

/** Нехватка — только когда она есть: обеспеченный целиком курс нехватки не показывает. */
fun CourseCoverage.toShortagePresentationDTO(): ShortagePresentationDTO? {
    if (isFullyCovered) return null
    return ShortagePresentationDTO(
        missingDoses = missingDoses.count,
        firstUncoveredOn = firstUncoveredAt?.atZone(zone)?.toLocalDate()
    )
}
