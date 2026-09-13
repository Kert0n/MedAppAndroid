package com.kert0n.medapp.domain.report

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * За какие дни человек спрашивает, сколько истратил: с [from] по [to] включительно и не больше
 * года (ТЗ 4.1.1.10.2; PLAN H6). Дни — календарные в зоне, которую называет спрашивающий: приём,
 * записанный вчера вечером, относится ко вчерашнему дню, где бы ни была полночь по UTC.
 */
data class SpendingPeriod(val from: LocalDate, val to: LocalDate) {
    init {
        require(!to.isBefore(from)) { "период кончается не раньше, чем начинается: $from — $to" }
        require(to.isBefore(from.plusYears(MAX_YEARS))) { "период не длиннее года: $from — $to" }
    }

    /** Первый момент периода: полночь [from] в [zone]. */
    fun startsAt(zone: ZoneId): Instant = from.atStartOfDay(zone).toInstant()

    /** Момент сразу после периода: полночь дня после [to] в [zone]; сам он в период не входит. */
    fun endsBefore(zone: ZoneId): Instant = to.plusDays(1).atStartOfDay(zone).toInstant()

    companion object {
        const val MAX_YEARS = 1L
    }
}
