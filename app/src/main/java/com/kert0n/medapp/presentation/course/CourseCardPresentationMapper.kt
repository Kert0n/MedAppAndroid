package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Пункт лечения глазами карточки. Плановая доза и коробка — те, что записаны **в самом приёме**:
 * изменение лечения прошлое не переписывает (PLAN D5). Фактическое время и количество есть
 * только у подтверждённого.
 */
fun IntakeProjection.Scheduled.toPresentationDTO(zone: ZoneId): CourseItemPresentationDTO =
    CourseItemPresentationDTO(
        id = id,
        on = slot.localDate,
        at = slot.localTime,
        amount = plannedAmount.quantity.toPresentationDTO(),
        packageName = plannedPackage?.name ?: taken?.pkg?.name,
        status = status,
        takenAt = taken?.at?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0),
        takenAmount = taken?.amount?.quantity?.toPresentationDTO()
    )

/** Сокращение обеспечения строкой карточки: день — в зоне курса, имя коробки приносит вызывающий. */
fun CoverageReduction.toPresentationDTO(zone: ZoneId, names: Map<Uuid, String>): CoverageReductionPresentationDTO =
    CoverageReductionPresentationDTO(
        id = id,
        on = at.atZone(zone).toLocalDate(),
        packageName = names[packageId],
        coveredBefore = coveredBefore.count,
        coveredAfter = coveredAfter.count
    )
