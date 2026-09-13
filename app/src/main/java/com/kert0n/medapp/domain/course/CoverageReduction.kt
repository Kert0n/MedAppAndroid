package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.value.Doses
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Сокращение обеспечения — событие, а не состояние (PLAN D5): курс следовал за коробкой и
 * обеспеченных доз стало меньше. По нему уведомляют (D8 `COVERAGE_SHORT`) и его показывает
 * карточка курса. Мой приём по плану уменьшает выделение на принятое — это не сокращение, и
 * события не даёт. Величина: что было, что стало, из-за какой коробки и когда.
 */
data class CoverageReduction(
    val id: Uuid,
    val courseId: Uuid,
    val packageId: Uuid,
    val coveredBefore: Doses,
    val coveredAfter: Doses,
    val at: Instant
) {
    init {
        require(coveredAfter < coveredBefore) { "сокращение — это меньше, чем было: $coveredBefore → $coveredAfter" }
    }
}
