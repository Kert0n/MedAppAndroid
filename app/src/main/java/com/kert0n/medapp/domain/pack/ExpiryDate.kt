package com.kert0n.medapp.domain.pack

import java.time.LocalDate
import java.time.YearMonth

/**
 * Срок годности — последний день, когда пачка ещё годна. `null` вместо него значит «срока не
 * знаем», и просроченной такая пачка не бывает (PLAN D8).
 */
@JvmInline
value class ExpiryDate(val lastDay: LocalDate) {

    /** Дата **включительная**: пачка, годная «до 31 марта», просрочена только 1 апреля. */
    fun isExpiredOn(date: LocalDate): Boolean = lastDay.isBefore(date)

    /**
     * Истекает ли срок не позже чем через [days] дней, считая [date]: окно, а не точный день,
     * потому что фоновая проверка может опоздать (D8). Просроченная пачка «скоро» не истекает.
     */
    fun expiresWithin(date: LocalDate, days: Long): Boolean {
        require(days >= 0) { "окно предупреждения не бывает отрицательным" }
        if (isExpiredOn(date)) return false
        return !lastDay.isAfter(date.plusDays(days))
    }

    /**
     * Какой этап предупреждения о годности наступает в день [date] (PLAN D8): за три дня, за день,
     * в последний день годности. По **календарным датам**, а не через `72h`: переход на летнее
     * время день не сдвигает. Просроченной этапов нет — у неё статус, а не предупреждение; и
     * поздно подключённая коробка получает только этап сегодняшнего дня, а не залп прошедших.
     */
    fun stageOn(date: LocalDate): Stage? = when (date) {
        lastDay.minusDays(3) -> Stage.SOURCE_3D
        lastDay.minusDays(1) -> Stage.SOURCE_1D
        lastDay -> Stage.TODAY
        else -> null
    }

    /** Этапы годности: первые два — коробкам-источникам системным уведомлением, последний — всем баннером (D8). */
    enum class Stage { SOURCE_3D, SOURCE_1D, TODAY }

    companion object {
        /** С какого числа дней до конца срока пачка «истекает скоро» — больший из порогов D8. */
        const val SOON_DAYS = 3L

        /** «03.2027» на упаковке значит «годен весь март»: срок — последний день месяца (D3). */
        fun of(month: YearMonth): ExpiryDate = ExpiryDate(month.atEndOfMonth())
    }
}
