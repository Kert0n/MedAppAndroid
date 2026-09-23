package com.kert0n.medapp.domain.course

import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.domain.value.Quantity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
    val zone: ZoneId,                // зона курса: в ней считаются дни предупреждений
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
                zone == other.zone &&
                perSource == other.perSource
            )

    override fun hashCode(): Int = Objects.hash(
        requiredDoses, coveredDoses, coveredUntil, firstUncoveredAt, zone, perSource
    )

    override fun toString(): String =
        "CourseCoverage(нужно $requiredDoses, обеспечено $coveredDoses)"

    val missingDoses: Doses get() = requiredDoses - coveredDoses

    /**
     * Какое предупреждение о нехватке идёт в момент [at] (PLAN D8): заранее — от порога и ближе к
     * первому необеспеченному пункту, и в его день. День считается **в зоне курса** — той же, в
     * которой стоит пункт: день устройства у полуночи может быть уже другим. Обеспеченному курсу
     * предупреждать нечего.
     */
    fun noticeOn(at: Instant, thresholdDays: Long = 3): Notice? {
        val uncoveredOn = firstUncoveredAt?.atZone(zone)?.toLocalDate() ?: return null
        val today = at.atZone(zone).toLocalDate()
        // День исчерпания — первым: при пороге 0 обе даты совпадают, и сказать надо то, что
        // ближе к правде, — «заканчивается», а не «скоро закончится».
        // Разность дней, а не вычитание порога из даты: порог набирает человек. «Заранее» — окно до
        // порога, а не точный день: нехватка могла появиться ближе порога, или сверки в тот день не
        // было, — и предупредить всё ещё стоит.
        val daysLeft = ChronoUnit.DAYS.between(today, uncoveredOn)
        return when {
            daysLeft == 0L -> Notice.END
            daysLeft in 1..thresholdDays -> Notice.AHEAD
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
