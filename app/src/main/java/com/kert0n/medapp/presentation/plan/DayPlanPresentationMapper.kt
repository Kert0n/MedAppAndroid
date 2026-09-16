package com.kert0n.medapp.presentation.plan

import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * План дня — в страницу экрана.
 *
 * Зону приносит спрашивающий: день человека — дата **в его зоне** (PLAN C1 «День — общая
 * инфраструктура»), и время строки считается по ней же. Считать её здесь по часам значило бы
 * завести второе мнение о том, где человек живёт, — и в полдень переезда страница разошлась бы
 * сама с собой.
 */
fun DayPlan.toPresentationDTO(
    daysAhead: Int,
    zone: ZoneId,
    answering: Set<Uuid> = emptySet()
): DayPagePresentationDTO {
    val rows = items.map { it.toPresentationDTO(zone).copy(isAnswering = it.intakeId in answering) }
    // Отвечают на записанные пункты; разовые приёмы и дозы за окном календаря идут ниже и без
    // действий. Порядок внутри каждой части — тот, что пришёл: план уже отдал строки по времени.
    val (answerable, rest) = rows.partition { it.state.isAnswerable }
    return DayPagePresentationDTO(
        date = date,
        daysAhead = daysAhead,
        items = answerable,
        alsoOnThisDay = rest
    )
}

/** Отвечают на то, что записано календарём: остальное показывают. */
private val DayItemPresentationDTO.State.isAnswerable: Boolean
    get() = this != DayItemPresentationDTO.State.EXPECTED && this != DayItemPresentationDTO.State.ONE_OFF

/** Номер записи о приёме; у дозы за окном календаря записи ещё нет. */
private val DayPlan.Item.intakeId: Uuid?
    get() = when (this) {
        is DayPlan.Item.Scheduled -> intake.id
        is DayPlan.Item.OneOff -> intake.id
        is DayPlan.Item.Expected -> null
    }

private fun DayPlan.Item.toPresentationDTO(zone: ZoneId): DayItemPresentationDTO = when (this) {
    is DayPlan.Item.Scheduled -> {
        val taken = intake.taken
        DayItemPresentationDTO(
            intakeId = intake.id,
            courseId = intake.courseId,
            title = title,
            at = at.atZone(zone).toLocalTime(),
            // Состоявшийся приём говорит о себе сам: сколько взяли и откуда, а не что назначали.
            dose = (taken?.amount ?: intake.plannedAmount).quantity.toPresentationDTO(),
            packageName = (taken?.pkg ?: intake.plannedPackage)?.name,
            state = intake.status.toState(),
            answeredAt = intake.answer?.at?.atZone(zone)?.toLocalTime()
        )
    }
    is DayPlan.Item.Expected -> DayItemPresentationDTO(
        intakeId = null,
        courseId = courseId,
        title = title,
        at = at.atZone(zone).toLocalTime(),
        dose = dose.quantity.toPresentationDTO(),
        // Пачку выбирают при ответе, а строки ещё нет: называть её заранее — обещать за человека.
        packageName = null,
        state = DayItemPresentationDTO.State.EXPECTED,
        answeredAt = null
    )
    is DayPlan.Item.OneOff -> DayItemPresentationDTO(
        intakeId = intake.id,
        // Лечения за разовым приёмом нет: он и есть «мимо всякого лечения» (PLAN D6).
        courseId = null,
        title = intake.dose.pkg.name,
        at = at.atZone(zone).toLocalTime(),
        dose = intake.dose.amount.quantity.toPresentationDTO(),
        packageName = null,
        state = DayItemPresentationDTO.State.ONE_OFF,
        answeredAt = at.atZone(zone).toLocalTime()
    )
}

private fun IntakeStatus.toState(): DayItemPresentationDTO.State = when (this) {
    IntakeStatus.PLANNED -> DayItemPresentationDTO.State.PLANNED
    IntakeStatus.TAKEN -> DayItemPresentationDTO.State.TAKEN
    IntakeStatus.MISSED -> DayItemPresentationDTO.State.MISSED
    IntakeStatus.CANCELLED -> DayItemPresentationDTO.State.CANCELLED
}
