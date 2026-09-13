package com.kert0n.medapp.domain.report

import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.UnplannedIntake
import com.kert0n.medapp.domain.value.Dose
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Что у меня назначено и что принято в день [date] (ТЗ 4.1.1.10.4; PLAN H6): пункты моих курсов со
 * статусом и пачкой и мои разовые приёмы этого дня, по времени.
 *
 * Пункт курса бывает двух видов, и экран делает с ними разное: **записанный** календарём можно
 * принять, пропустить, у него есть ответ; **ожидаемый** — дальше окна календаря — только показать:
 * строки у него ещё нет, и отвечать не на что. Дата пункта — день курса в его зоне, дата разового
 * приёма — день его момента в зоне спрашивающего.
 */
data class DayPlan(val date: LocalDate, val items: List<Item>) {

    sealed interface Item {
        val at: Instant

        /** Пункт, записанный календарём: с ответом, если он есть, и плановой пачкой. */
        data class Scheduled(val intake: IntakeProjection.Scheduled, val title: String) : Item {
            override val at: Instant get() = intake.slot.at
        }

        /** Пункт идущего лечения дальше окна календаря: доза, которая придётся на этот день. */
        data class Expected(val courseId: Uuid, val title: String, val slot: ScheduledOccurrence, val dose: Dose) : Item {
            override val at: Instant get() = slot.at
        }

        /** Разовый приём этого дня. */
        data class OneOff(val intake: IntakeProjection.Unplanned) : Item {
            override val at: Instant get() = intake.dose.at
        }
    }

    val isEmpty: Boolean get() = items.isEmpty()

    companion object {

        /**
         * План дня из записанных пунктов этой даты [scheduled], разовых приёмов дня [oneOffs] и идущих
         * лечений [inProgress] — у них дозы, ещё не записанные календарём, ложатся на свои пункты.
         */
        fun of(
            date: LocalDate,
            scheduled: List<CourseIntake>,
            oneOffs: List<UnplannedIntake>,
            inProgress: List<CourseInProgress>,
            records: Map<Uuid, CourseRecordProjection>
        ): DayPlan {
            fun title(courseId: Uuid) = requireNotNull(records[courseId]) { "у пункта курса есть запись эпизода $courseId" }.title
            val written = scheduled.groupBy({ it.courseId }, { it.slot.slot })
            val expected = inProgress.flatMap { plan ->
                val course = plan.course
                val known = written[course.id].orEmpty().toSet()
                course.remainingOccurrences(plan.progress)
                    .filter { it.localDate == date && it.slot !in known }
                    .map { Item.Expected(course.id, title(course.id), it, course.dose) }
            }
            val items = scheduled.map { Item.Scheduled(it.projection(), title(it.courseId)) } +
                expected +
                oneOffs.map { Item.OneOff(it.projection()) }
            return DayPlan(date, items.sortedBy { it.at })
        }
    }
}
