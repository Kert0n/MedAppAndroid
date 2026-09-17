package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.SpendingHorizon
import java.time.LocalDate

/**
 * До какого дня человек спрашивает о будущем расходе. Случая два, и различает их **полночь**:
 * пресет считается от нынешнего дня и в полночь начинает указывать на другое число, а названная
 * человеком дата не двигается — он назвал её числом (PLAN C1 «Пресет считается от сегодня»).
 */
sealed interface HorizonChoice {

    /** Срок от сегодняшнего дня: «Неделя», «Месяц», «3 месяца». */
    data class Preset(val preset: HorizonPreset) : HorizonChoice

    /** День, который человек выбрал в календаре. */
    data class Until(val date: LocalDate) : HorizonChoice

    /** До какого дня спрашивают, если сегодня [today]. */
    fun until(today: LocalDate): LocalDate = when (this) {
        is Preset -> preset.until(today)
        is Until -> date
    }
}

/**
 * Сроки, которые человек выбирает одним нажатием. Дальше трёх календарных месяцев спросить
 * нельзя (ТЗ 4.1.1.10.1), и «3 месяца» — ровно предел: пресеты не умеют быть незаконными.
 */
enum class HorizonPreset(private val months: Long, private val days: Long) {
    WEEK(0, 7),
    MONTH(1, 0),
    THREE_MONTHS(SpendingHorizon.MAX_MONTHS, 0);

    fun until(today: LocalDate): LocalDate = today.plusMonths(months).plusDays(days)
}
