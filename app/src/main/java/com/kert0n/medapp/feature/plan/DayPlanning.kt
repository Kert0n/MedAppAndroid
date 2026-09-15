package com.kert0n.medapp.feature.plan

import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.feature.time.Today
import com.kert0n.medapp.storage.report.ReportStorageRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
 * Зона берётся у часов здесь же: день приёма — календарная дата человека, и спрашивать её у
 * экрана значило бы завести второе мнение о том, где он живёт.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DayPlanning @Inject constructor(
    private val today: Today,
    private val reports: ReportStorageRepository,
    private val clock: Clock
) {

    /**
     * План дня, отстоящего от сегодняшнего на [daysAhead] дней.
     *
     * Назад не листают: в прошлом ответы уже даны, и менять их пока нечем — это отдельная
     * работа (issue «Правка записанных ответов и листание в прошлое»). Отрицательный сдвиг не
     * отвечает пустым планом, а падает: пустой план значит «в этот день ничего не назначено», и
     * выдавать им «так нельзя» — врать экрану.
     */
    fun observe(daysAhead: Int = 0): Flow<DayPlan> {
        require(daysAhead >= 0) { "в прошлое пока не листают: сдвиг $daysAhead" }
        return today.observe()
            .map { it.plusDays(daysAhead.toLong()) }
            .flatMapLatest { date -> reports.observeDayPlan(date, clock.zone) }
    }
}
