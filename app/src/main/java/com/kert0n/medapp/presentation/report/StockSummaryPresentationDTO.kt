package com.kert0n.medapp.presentation.report

import com.kert0n.medapp.presentation.value.MoneyPresentationDTO

/**
 * Что у человека есть сейчас — глазами экрана (PLAN H3 «Набор аналитики», H6).
 *
 * Единственный отчёт не о расходе, и единственный, в котором есть деньги: цена — цена **всей**
 * пачки, как её назвал человек, и по мере того как пачку пьют, она не тает. Валюты не
 * складываются между собой, поэтому [prices] — список, а не число; [unpriced] стоит отдельно,
 * потому что «неизвестно» и «бесплатно» — разные состояния, и второе соврало бы о сумме.
 */
data class StockSummaryPresentationDTO(
    val packages: Int,
    val byCategory: List<ReportBarPresentationDTO>,
    val byForm: List<ReportBarPresentationDTO>,
    val prices: List<MoneyPresentationDTO>,
    val unpriced: Int
) {
    val isEmpty: Boolean get() = packages == 0
}

/**
 * Полоса сводки: подпись, число и доля от целого.
 *
 * [share] — свойство **рисунка**, а не отчёта: смени полосу на кольцо, и домен не тронется.
 * Оттого доля и считается здесь, а не в `StockSummary`. Число при этом остаётся: полоса — не
 * единственный носитель, и рядом с ней всегда стоит [packages] словами (PLAN H3 «Дизайн»).
 *
 * [name] — `null`, когда человек не назвал ни категории, ни формы: такие строки идут последними
 * и говорят «не указано» словами экрана, а не выдуманным именем.
 */
data class ReportBarPresentationDTO(val name: String?, val packages: Int, val share: Float)
