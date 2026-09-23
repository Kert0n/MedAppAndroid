package com.kert0n.medapp.domain.pack

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

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

    /** Сколько календарных дней до последнего дня годности, считая от [date]; просроченной — меньше нуля. */
    fun daysLeftOn(date: LocalDate): Long = ChronoUnit.DAYS.between(date, lastDay)

    /**
     * Какой этап предупреждения о годности идёт в день [date] (PLAN D8): заранее — когда осталось
     * три или два дня, за день, в последний день годности. Этап — **окно**, а не точный день:
     * сверка могла опоздать (телефон выключен, задача сдвинута), коробку могли подключить позже, и
     * предупреждение заранее не пропадает вместе с пропущенным днём. Этап при этом один — текущий:
     * залпа прошедших нет. По **календарным датам**, а не через `72h`: переход на летнее время день не
     * сдвигает. Просроченной этапов нет — у неё статус, а не предупреждение.
     */
    fun stageOn(date: LocalDate): Stage? = when (daysLeftOn(date)) {
        0L -> Stage.TODAY
        1L -> Stage.SOURCE_1D
        2L, 3L -> Stage.SOURCE_3D
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
