package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import kotlin.uuid.Uuid

/**
 * Сколько я израсходую до дня, если все приёмы состоятся: по моим идущим курсам, и ничего больше
 * (ТЗ 4.1.1.10.1; PLAN H6). Прогноза остатка пачек здесь нет — мы прогнозируем не пачки, а то, что
 * примем сами, — и нехватка расчёт не режет: курс обещает дозы, а не их обеспечение.
 *
 * Строка — эпизод: сколько доз придётся на горизонт и сколько это в его единице.
 */
data class FutureSpending(val episodes: List<Episode>) {

    data class Episode(val record: CourseRecordProjection, val doses: Doses, val total: Quantity)

    val isEmpty: Boolean get() = episodes.isEmpty()

    /** Идущее лечение вместе с тем, что по нему уже отвечено. */
    class Plan(val course: Course, val progress: CourseProgress)

    companion object {

        /**
         * Расход идущих лечений [plans] на [horizon]; границы суток — в зоне каждого курса. Лечение,
         * которому до горизонта нечего принять, строки не даёт. Эпизоды — от начатых позже.
         */
        fun of(plans: List<Plan>, records: Map<Uuid, CourseRecordProjection>, horizon: SpendingHorizon): FutureSpending =
            FutureSpending(
                plans.mapNotNull { plan ->
                    val zone = plan.course.schedule.zone
                    val doses = plan.course.dosesDue(plan.progress, horizon.startsAt(zone), horizon.endsBefore(zone))
                    if (doses.isNone) return@mapNotNull null
                    val record = requireNotNull(records[plan.course.id]) { "у идущего лечения есть запись эпизода ${plan.course.id}" }
                    Episode(record, doses, plan.course.dose.quantity * doses)
                }.sortedByDescending { it.record.startedAt }
            )

        val EMPTY = FutureSpending(emptyList())
    }
}
