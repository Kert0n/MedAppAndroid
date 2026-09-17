package com.kert0n.medapp.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.report.ReportBarPresentationDTO
import com.kert0n.medapp.presentation.report.StockSummaryPresentationDTO
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.text

/**
 * Что у человека есть сейчас (PLAN H3 «Набор аналитики», экран 26). Единственный отчёт не о
 * расходе: живые пачки всех доступных полок по категориям, формам и цене.
 *
 * **Первым** — число упаковок: за ним сюда и приходят. Цена идёт следом и **по валютам порознь**,
 * а пачки без цены названы своим числом — «бесплатно» соврало бы о сумме.
 */
@Composable
fun SummaryReport(summary: StockSummaryPresentationDTO, modifier: Modifier = Modifier) {
    if (summary.isEmpty) {
        EmptyState(
            text = stringResource(R.string.reports_stock_empty),
            modifier = modifier,
            icon = R.drawable.ic_inventory_2
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item("head") { Head(summary) }
        if (summary.byCategory.isNotEmpty()) {
            item("categories") {
                Bars(R.string.reports_by_category, R.drawable.ic_category, summary.byCategory)
            }
        }
        if (summary.byForm.isNotEmpty()) {
            item("forms") {
                Bars(R.string.reports_by_form, R.drawable.ic_pill, summary.byForm)
            }
        }
    }
}

/** Сколько всего и на сколько денег. Цена всей пачки не тает по мере того, как её пьют (PLAN H6). */
@Composable
private fun Head(summary: StockSummaryPresentationDTO) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pluralStringResource(R.plurals.packages_count, summary.packages, summary.packages),
                style = MaterialTheme.typography.headlineMedium
            )
            for (price in summary.prices) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_payments),
                        contentDescription = stringResource(R.string.reports_total_price),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(price.text(), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (summary.unpriced > 0) {
                Text(
                    pluralStringResource(R.plurals.reports_unpriced, summary.unpriced, summary.unpriced),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Разрез сводки полосами: «не указано» стоит последним и приглушено — его порядок задал домен. */
@Composable
private fun Bars(title: Int, icon: Int, bars: List<ReportBarPresentationDTO>) {
    Column {
        ReportSectionTitle(title, icon)
        for (bar in bars) {
            ShareBar(
                label = bar.name ?: stringResource(R.string.reports_not_specified),
                value = bar.packages.toString(),
                share = bar.share,
                dimmed = bar.name == null
            )
        }
    }
}
