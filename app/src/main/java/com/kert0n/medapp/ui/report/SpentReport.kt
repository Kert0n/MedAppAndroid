package com.kert0n.medapp.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.report.PeriodPreset
import com.kert0n.medapp.presentation.report.SpendingPresentationDTO
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.words
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Сколько человек истратил за срок (PLAN H3 «Набор аналитики», экран 26; ТЗ 4.1.1.10.2).
 *
 * Считается по состоявшимся приёмам, и внеплановые в их числе: человек выпил таблетку мимо
 * лечения, и она истрачена (H6). Две полки, потому что за ними стоят разные вещи: лечения — за
 * записями эпизодов, живущими и после конца лечения, разовые приёмы — за вечными записями коробок.
 */
@Composable
fun SpentReport(
    report: SpendingPresentationDTO,
    onPreset: (PeriodPreset) -> Unit,
    onPeriod: (LocalDate, LocalDate) -> Unit,
    onCourse: (Uuid) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        PeriodChips(
            preset = report.preset,
            today = report.today,
            from = report.from,
            to = report.to,
            onPreset = onPreset,
            onPeriod = onPeriod
        )
        if (report.isEmpty) {
            EmptyState(text = stringResource(R.string.reports_spent_empty), icon = R.drawable.ic_history)
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("period") {
                Text(
                    stringResource(R.string.reports_spent_period, report.from.format(DAY), report.to.format(DAY)),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (report.episodes.isNotEmpty()) {
                item("episodes") {
                    ReportSectionTitle(R.string.reports_spent_by_course, R.drawable.ic_medication)
                }
                for (group in report.episodes) {
                    item("episodes-${group.total.unit.id}") {
                        Column {
                            ReportGroupTitle(group.total.words())
                            for (row in group.rows) {
                                ShareBar(
                                    label = row.title,
                                    value = row.amount.words(),
                                    supporting = pluralStringResource(R.plurals.reports_intakes, row.intakes, row.intakes),
                                    share = row.share,
                                    onClick = { onCourse(row.courseId) }
                                )
                            }
                        }
                    }
                }
            }
            if (report.boxes.isNotEmpty()) {
                item("boxes") {
                    ReportSectionTitle(R.string.reports_spent_one_off, R.drawable.ic_pill)
                }
                for (group in report.boxes) {
                    item("boxes-${group.total.unit.id}") {
                        Column {
                            ReportGroupTitle(group.total.words())
                            for (row in group.rows) {
                                ShareBar(
                                    label = row.name,
                                    value = row.amount.words(),
                                    supporting = pluralStringResource(R.plurals.reports_intakes, row.intakes, row.intakes),
                                    share = row.share
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
