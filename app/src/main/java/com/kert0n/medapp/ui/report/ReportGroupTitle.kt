package com.kert0n.medapp.ui.report

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R

/**
 * Заголовок полки отчёта: сумма в её единице. Полка на то и заведена, что складывать можно только
 * внутри единицы (PLAN H6), и сумма стоит там, где это видно.
 *
 * Единственную строку единственной полки сумма не сопровождает: «Всего 1 шт» над строкой «1 шт» —
 * то же число дважды, и читать его два раза человеку незачем.
 */
@Composable
fun ReportGroupTitle(total: String, modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.reports_group_total, total),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(bottom = 4.dp)
    )
}
