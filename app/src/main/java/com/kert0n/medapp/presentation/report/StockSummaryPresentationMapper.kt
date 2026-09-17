package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.domain.report.StockSummary
import com.kert0n.medapp.presentation.value.MoneyPresentationDTO

/**
 * Сводка — в состояние экрана. Порядок групп задаёт домен (от больших к меньшим, «не указано»
 * последним), и менять его здесь нечем: маппер добавляет только долю и строки цен.
 */
fun StockSummary.toPresentationDTO(): StockSummaryPresentationDTO = StockSummaryPresentationDTO(
    packages = packages,
    byCategory = byCategory.map { bar(it.category, it.packages) },
    byForm = byForm.map { bar(it.form?.name, it.packages) },
    prices = prices.map { MoneyPresentationDTO(it.amount.stripTrailingZeros().toPlainString(), it.currencyCode) },
    unpriced = unpriced
)

/**
 * Доля считается от **всех живых пачек**, а не от наибольшей группы: полосы одной сводки тогда
 * складываются в целое, и «девять из тридцати семи» видно на глаз. Пустая сводка полос не даёт
 * вовсе, поэтому делить на ноль здесь нечего.
 */
private fun StockSummary.bar(name: String?, packages: Int) =
    ReportBarPresentationDTO(name, packages, packages.toFloat() / this.packages)
