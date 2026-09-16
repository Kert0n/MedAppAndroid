package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid

/**
 * Пункт лечения строкой карточки (PLAN H3 №14): когда, сколько и из какой коробки. День и время —
 * в зоне курса, а не устройства: лечение назначено в своей зоне (D5).
 *
 * Прошлые пункты несут **свою** плановую дозу, а не нынешнюю: изменение лечения их не
 * переписывает. [takenAt] есть у подтверждённого — человек помнит приём по времени, а не по
 * плану; [takenAmount] отличается от [amount], когда принято не ровно плановое.
 */
data class CourseItemPresentationDTO(
    val id: Uuid,
    val on: LocalDate,
    val at: LocalTime,
    val amount: QuantityPresentationDTO,
    val packageName: String?,
    val status: IntakeStatus,
    val takenAt: LocalTime?,
    val takenAmount: QuantityPresentationDTO?
)

/**
 * Сокращение обеспечения строкой карточки (PLAN D5): было столько приёмов, стало столько —
 * из-за этой коробки и в этот день. Событие, а не состояние: человек читает, что случилось.
 */
data class CoverageReductionPresentationDTO(
    val id: Uuid,
    val on: LocalDate,
    val packageName: String?,
    val coveredBefore: Int,
    val coveredAfter: Int
)
