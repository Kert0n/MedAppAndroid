package com.kert0n.medapp.feature.plan

import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.feature.report.ReportReadings
import com.kert0n.medapp.feature.time.Day
import com.kert0n.medapp.feature.time.Today
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * План на день — по **сдвигу** от сегодняшнего, а не по дате (PLAN C1 «День — общая
 * инфраструктура», H3 «План»).
 *
 * Страница плана листается вперёд: сегодня, завтра, послезавтра. Держи она дату — в полночь
 * показывала бы вчерашний день, и её пришлось бы переставлять руками; держит сдвиг — и тот же
 * «через день» сам начинает указывать на другое число, а набранное человеком остаётся: страница
 * та же.
 *
 * Зона приходит вместе с днём: день приёма — календарная дата человека **в его зоне**, и
 * спрашивать её у экрана значило бы завести второе мнение о том, где он живёт.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DayPlanning @Inject constructor(
    private val today: Today,
    private val reports: ReportReadings
) {

    /**
     * План дня, отстоящего от сегодняшнего на [daysAhead] дней.
     *
     * Назад не листают: в прошлом ответы уже даны, и менять их пока нечем — это отдельная
     * работа (issue #44). Отрицательный сдвиг не
     * отвечает пустым планом, а падает: пустой план значит «в этот день ничего не назначено», и
     * выдавать им «так нельзя» — врать экрану.
     */
    fun observe(daysAhead: Int = 0): Flow<DayPlan> {
        require(daysAhead >= 0) { "в прошлое пока не листают: сдвиг $daysAhead" }
        return today.observe()
            // Зона берётся из того же дня, а не спрашивается у часов второй раз: по ней
            // считаются начало и конец суток, и переехавший в полдень получает другие границы
            // при том же числе. Спроси её отдельно — чтение осталось бы в прежней зоне до
            // следующей смены числа.
            .map { day -> Day(day.date.plusDays(daysAhead.toLong()), day.zone) }
            .distinctUntilChanged()
            .flatMapLatest { day -> reports.observeDayPlan(day.date, day.zone) }
    }
}
