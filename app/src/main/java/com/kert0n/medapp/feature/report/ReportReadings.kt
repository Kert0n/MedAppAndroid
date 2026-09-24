package com.kert0n.medapp.feature.report

import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.domain.report.StockSummary
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от этого хранения: чтения потоком. Объявляет сценарий, исполняет хранение. */
interface ReportReadings {

    /** Сколько я истратил за [period], дни которого считаются в [zone]. */
    fun observeSpending(period: SpendingPeriod, zone: ZoneId): Flow<Spending>

    /** Сколько я израсходую по идущим лечениям на [horizon], если все приёмы состоятся. */
    fun observeFutureSpending(horizon: SpendingHorizon): Flow<FutureSpending>

    /** Что у меня есть сейчас: живые пачки всех доступных полок по категориям, формам и цене. */
    fun observeStockSummary(): Flow<StockSummary>

    /** План на день [date]: пункты моих курсов и разовые приёмы дня, чей момент — в [zone]. */
    fun observeDayPlan(date: LocalDate, zone: ZoneId): Flow<DayPlan>
}
