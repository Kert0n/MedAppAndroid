package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Objects

/**
 * Обеспечение курса: сколько из оставшихся приёмов покрывают пачки препарата и с какого приёма
 * не хватает. Вычисляется и не хранится (PLAN D5); нехватка меняет обеспечение, а не расписание.
 */
class CourseCoverage(
    val requiredDoses: Doses,        // сколько приёмов ещё впереди
    val coveredDoses: Doses,         // сколько из них обеспечено
    val coveredUntil: Instant?,      // до какого приёма хватит
    val firstUncoveredAt: Instant?,  // с какого приёма не хватает
    perSource: List<Source>
) {
    /** Своя копия: посчитанное обеспечение не меняется вслед за списком у вызывающего. */
    val perSource: List<Source> = perSource.toList()

    init {
        require(coveredDoses <= requiredDoses) {
            "обеспечено больше, чем нужно: $coveredDoses из $requiredDoses"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is CourseCoverage &&
                requiredDoses == other.requiredDoses &&
                coveredDoses == other.coveredDoses &&
                coveredUntil == other.coveredUntil &&
                firstUncoveredAt == other.firstUncoveredAt &&
                perSource == other.perSource
            )

    override fun hashCode(): Int = Objects.hash(
        requiredDoses, coveredDoses, coveredUntil, firstUncoveredAt, perSource
    )

    override fun toString(): String =
        "CourseCoverage(нужно $requiredDoses, обеспечено $coveredDoses)"

    val missingDoses: Doses get() = requiredDoses - coveredDoses

    /**
     * Какое предупреждение о нехватке наступает в день [today] в зоне курса (PLAN D8): за три
     * календарных дня до первого необеспеченного пункта и в его день. Обеспеченному курсу
     * предупреждать нечего.
     */
    fun noticeOn(today: LocalDate, zone: ZoneId, thresholdDays: Long = 3): Notice? {
        val uncoveredOn = firstUncoveredAt?.atZone(zone)?.toLocalDate() ?: return null
        return when (today) {
            uncoveredOn.minusDays(thresholdDays) -> Notice.AHEAD
            uncoveredOn -> Notice.END
            else -> null
        }
    }

    /** Предупреждения о нехватке: заранее и в день исчерпания. */
    enum class Notice { AHEAD, END }

    val isFullyCovered: Boolean get() = missingDoses.isNone

    /**
     * Строка по одной пачке. [coveredDoses] — часть выделения, которую подтверждает остаток;
     * разница с [allocatedDoses] объясняет человеку, почему обеспечено меньше выделенного.
     * [leftover] — остаток меньше дозы, не переливающийся в следующую пачку. [maxDoses] —
     * верхняя граница ползунка этой пачки: не больше того, что она даёт, и того, что потребность
     * оставляет сверх выделенного остальным (PLAN D5); считается теми же входами, что и
     * обеспечение, и лежит в той же строке. [fault] — источник отключён с причиной: он ничего не
     * даёт, но виден, чтобы человек понял, что случилось и с какой коробкой.
     */
    data class Source(
        val pkg: PackageRef,
        val allocatedDoses: Doses,
        val coveredDoses: Doses,
        val leftover: Quantity,
        val maxDoses: Doses,
        val fault: CourseSource.Fault? = null
    ) {
        init {
            require(coveredDoses <= allocatedDoses) {
                "покрыто больше, чем выделено: $coveredDoses из $allocatedDoses"
            }
        }
    }
}
