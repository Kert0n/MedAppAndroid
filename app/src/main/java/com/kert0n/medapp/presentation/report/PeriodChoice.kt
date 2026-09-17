package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.SpendingPeriod
import java.time.LocalDate
import java.time.Period

/**
 * За какие дни человек спрашивает, сколько истратил. Случая два, и различает их — как у срока
 * расхода — **полночь**: пресет считается от нынешнего дня, а названные числа не двигаются
 * (PLAN C1 «Пресет считается от сегодня»).
 */
sealed interface PeriodChoice {

    /** Срок, кончающийся сегодня: «Неделя», «Месяц», «3 месяца», «Год». */
    data class Preset(val preset: PeriodPreset) : PeriodChoice

    /** Два числа, которые человек назвал сам. */
    data class Own(val from: LocalDate, val to: LocalDate) : PeriodChoice

    /** За какие дни спрашивают, если сегодня [today]. */
    fun period(today: LocalDate): SpendingPeriod = when (this) {
        is Preset -> preset.period(today)
        is Own -> SpendingPeriod(from, to)
    }
}

/**
 * Сроки, которые человек выбирает одним нажатием. Считаются назад от сегодняшнего дня и кончаются
 * сегодня: истраченное — события прошлого, будущих приёмов в нём не бывает.
 *
 * «Год» — ровно предел `SpendingPeriod`: последний день года назад входит в период, а день перед
 * ним уже нет (ТЗ 4.1.1.10.2). Пресеты не умеют быть незаконными.
 */
enum class PeriodPreset(private val span: Period) {
    WEEK(Period.ofDays(6)),
    MONTH(Period.ofMonths(1)),
    THREE_MONTHS(Period.ofMonths(3)),

    /**
     * Год — это сегодняшний день и 364 предыдущих: оба конца входят в период, и «та же дата год
     * назад» была бы уже 366-м днём, то есть длиннее предела (`SpendingPeriod.MAX_YEARS`).
     */
    YEAR(Period.ofYears(1).minusDays(1));

    fun period(today: LocalDate): SpendingPeriod = SpendingPeriod(today.minus(span), today)
}
