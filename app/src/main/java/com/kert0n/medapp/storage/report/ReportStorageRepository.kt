package com.kert0n.medapp.storage.report

import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingPeriod
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
}
