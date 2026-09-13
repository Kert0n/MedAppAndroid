package com.kert0n.medapp.domain.report

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * До какого дня человек спрашивает, сколько израсходует: с сегодняшнего [today] по [until]
 * включительно и не дальше трёх календарных месяцев (ТЗ 4.1.1.10.1; PLAN H6). Сегодня называет
 * спрашивающий: база системных часов не читает.
 */
data class SpendingHorizon(val today: LocalDate, val until: LocalDate) {
    init {
        require(!until.isBefore(today)) { "расход считается вперёд, а не назад: $today — $until" }
        require(!until.isAfter(today.plusMonths(MAX_MONTHS))) {
            "горизонт расхода — $MAX_MONTHS календарных месяца: $today — $until"
        }
    }

    /** С начала сегодняшних суток в зоне курса: неотвеченный утренний приём ещё состоится. */
    fun startsAt(zone: ZoneId): Instant = today.atStartOfDay(zone).toInstant()

    /** Сразу после [until] в зоне курса: приёмы последнего дня входят. */
    fun endsBefore(zone: ZoneId): Instant = until.plusDays(1).atStartOfDay(zone).toInstant()

    companion object {
        const val MAX_MONTHS = 3L
    }
}
