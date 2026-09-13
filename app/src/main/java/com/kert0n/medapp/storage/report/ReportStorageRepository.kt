package com.kert0n.medapp.storage.report

import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.StockSummary
import com.kert0n.medapp.domain.report.SpendingPeriod
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * Ответы личного кабинета (ТЗ 4.1.1.10; PLAN H6). Каждый поток — готовый отчёт, собранный одним
 * снимком базы: приём, записанный во время чтения, либо целиком в отчёте, либо целиком нет.
 * Зона приходит аргументом: база системных часов и часового пояса не читает.
 */
interface ReportStorageRepository {

    /** Сколько я истратил за [period], дни которого считаются в [zone]. */
    fun observeSpending(period: SpendingPeriod, zone: ZoneId): Flow<Spending>

    /** Сколько я израсходую по идущим лечениям на [horizon], если все приёмы состоятся. */
    fun observeFutureSpending(horizon: SpendingHorizon): Flow<FutureSpending>

    /** Что у меня есть сейчас: живые пачки всех доступных полок по категориям, формам и цене. */
    fun observeStockSummary(): Flow<StockSummary>

    /** План на день [date]: пункты моих курсов и разовые приёмы дня, чей момент — в [zone]. */
    fun observeDayPlan(date: LocalDate, zone: ZoneId): Flow<DayPlan>
}
