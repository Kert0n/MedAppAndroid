package com.kert0n.medapp.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.presentation.report.PeriodPreset
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.DayPicker
import com.kert0n.medapp.ui.daysBetween
import java.time.LocalDate

/**
 * За какие дни спрашивают об истраченном: четыре срока одним нажатием и свой период календарём.
 *
 * Свой период человек называет **двумя днями подряд** — сперва начало, потом конец: так календарь
 * остаётся одним, а предел второго дня считается от первого. Период длиннее года не набирается
 * вовсе — такие дни не нажимаются (ТЗ 4.1.1.10.2); вперёд тоже нельзя: истраченное — события
 * прошлого, и будущих приёмов в нём не бывает.
 *
 * Чипы переносятся строкой по той же причине, что и у срока расхода: уехавший за край чип не
 * виден вовсе.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeriodChips(
    preset: PeriodPreset?,
    today: LocalDate,
    from: LocalDate,
    to: LocalDate,
    onPreset: (PeriodPreset) -> Unit,
    onPeriod: (LocalDate, LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    var picking by remember { mutableStateOf<LocalDate?>(null) }
    var pickingStart by remember { mutableStateOf(false) }
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (entry in PeriodPreset.entries) {
            FilterChip(
                selected = preset == entry,
                onClick = { onPreset(entry) },
                label = { Text(stringResource(entry.label)) }
            )
        }
        FilterChip(
            selected = preset == null,
            onClick = { pickingStart = true },
            label = { Text(stringResource(R.string.reports_preset_own_period)) },
            trailingIcon = {
                Icon(
                    painterResource(R.drawable.ic_calendar_month),
                    contentDescription = stringResource(R.string.action_pick_date),
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
    if (pickingStart) {
        DayPicker(
            selected = from,
            onPick = { day -> picking = day },
            onDismiss = { pickingStart = false },
            selectable = daysBetween(today.minusYears(SpendingPeriod.MAX_YEARS).plusDays(1), today)
        )
    }
    // Конец периода спрашивается после начала и **от него**: дальше года от начала и раньше самого
    // начала дней в календаре нет.
    picking?.let { start ->
        DayPicker(
            selected = to.coerceAtLeast(start),
            onPick = { day -> day?.let { onPeriod(start, it) } },
            onDismiss = { picking = null },
            selectable = daysBetween(start, minOf(today, start.plusYears(SpendingPeriod.MAX_YEARS).minusDays(1)))
        )
    }
}

private val PeriodPreset.label: Int
    get() = when (this) {
        PeriodPreset.WEEK -> R.string.reports_preset_week
        PeriodPreset.MONTH -> R.string.reports_preset_month
        PeriodPreset.THREE_MONTHS -> R.string.reports_preset_three_months
        PeriodPreset.YEAR -> R.string.reports_preset_year
    }
