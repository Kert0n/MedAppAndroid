package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.feature.packages.PackageQuery
import java.time.LocalDate

/**
 * Запрос списка. Дискриминатор фильтра и его аргументы разъезжаются здесь, потому что в SQL
 * закрытого типа нет, а один запрос на все сочетания лучше шести похожих.
 *
 * `today` приходит аргументом: «просрочено» зависит от дня, а база системных часов не читает.
 */
suspend fun PackageDao.matching(
    query: PackageQuery,
    today: LocalDate
): List<PackageStorageRow> = query(
    medKitId = query.medKitId,
    text = query.searchText,
    filter = when (query.filter) {
        null, PackageQuery.Filter.HasFree -> "NONE"
        PackageQuery.Filter.Expired -> "EXPIRED"
        is PackageQuery.Filter.ExpiringWithin -> "EXPIRING"
        PackageQuery.Filter.OnCourse -> "ON_COURSE"
        is PackageQuery.Filter.OfCategory -> "CATEGORY"
        is PackageQuery.Filter.OfForm -> "FORM"
    },
    today = today,
    until = (query.filter as? PackageQuery.Filter.ExpiringWithin)?.let { today.plusDays(it.days) },
    category = (query.filter as? PackageQuery.Filter.OfCategory)?.category,
    formId = (query.filter as? PackageQuery.Filter.OfForm)?.formId,
    sort = query.sort.name
)
