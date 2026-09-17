package com.kert0n.medapp.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.report.FutureSpendingPresentationDTO
import com.kert0n.medapp.presentation.report.HorizonPreset
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.words
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Сколько человек израсходует до дня (PLAN H3 «Набор аналитики», экран 26; ТЗ 4.1.1.10.1).
 *
 * Считается по идущим лечениям так, будто все приёмы состоятся: нехватка коробок расчёт не режет —
 * лечение обещает дозы, а не их обеспечение (H6). Оттого и подпись под сроком: человек должен
 * знать, при каком условии число верно.
 */
@Composable
fun FutureReport(
    report: FutureSpendingPresentationDTO,
    onPreset: (HorizonPreset) -> Unit,
    onUntil: (LocalDate) -> Unit,
    onCourse: (Uuid) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        HorizonChips(preset = report.preset, today = report.today, until = report.until, onPreset = onPreset, onUntil = onUntil)
        if (report.isEmpty) {
            EmptyState(text = stringResource(R.string.reports_future_empty), icon = R.drawable.ic_event_upcoming)
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("until") {
                Column {
                    Text(
                        stringResource(R.string.reports_future_until, report.until.format(DAY)),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.reports_future_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            for (group in report.groups) {
                item(group.total.unit.id.toString()) {
                    Column {
                        if (report.groups.size > 1 || group.rows.size > 1) ReportGroupTitle(group.total.words())
                        for (row in group.rows) {
                            ShareBar(
                                label = row.title,
                                value = row.amount.words(),
                                supporting = pluralStringResource(R.plurals.reports_doses, row.doses, row.doses),
                                share = row.share,
                                onClick = { onCourse(row.courseId) }
                            )
                        }
                    }
                }
            }
        }
    }
}
