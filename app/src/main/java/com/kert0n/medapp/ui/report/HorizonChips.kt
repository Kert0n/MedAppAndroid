package com.kert0n.medapp.ui.report

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.presentation.report.HorizonPreset
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.DayPicker
import com.kert0n.medapp.ui.daysBetween
import java.time.LocalDate

/**
 * До какого дня спрашивают о расходе: три срока одним нажатием и свой день календарём.
 *
 * Своя дата отмечена **числом на чипе**, а не словом «своя»: выбранное человек должен видеть, не
 * открывая календарь снова. Дальше трёх месяцев в календаре не нажимается ни один день (ТЗ
 * 4.1.1.10.1) — предел показан там, где человек выбирает, а не отказом после выбора.
 *
 * Чипы прокручиваются вбок: на 360 dp и крупном шрифте пять слов в строку не умещаются, а
 * перенос строкой увёл бы сам отчёт под нижний край.
 */
@Composable
fun HorizonChips(
    preset: HorizonPreset?,
    today: LocalDate,
    until: LocalDate,
    onPreset: (HorizonPreset) -> Unit,
    onUntil: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (entry in HorizonPreset.entries) {
            FilterChip(
                selected = preset == entry,
                onClick = { onPreset(entry) },
                label = { Text(stringResource(entry.label)) }
            )
        }
        FilterChip(
            selected = preset == null,
            onClick = { picking = true },
            label = { Text(if (preset == null) until.format(DAY) else stringResource(R.string.reports_preset_own_date)) },
            trailingIcon = {
                Icon(
                    painterResource(R.drawable.ic_calendar_month),
                    contentDescription = stringResource(R.string.action_pick_date),
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
    if (picking) {
        DayPicker(
            selected = until,
            onPick = { day -> day?.let(onUntil) },
            onDismiss = { picking = false },
            selectable = daysBetween(today, today.plusMonths(SpendingHorizon.MAX_MONTHS))
        )
    }
}

private val HorizonPreset.label: Int
    get() = when (this) {
        HorizonPreset.WEEK -> R.string.reports_preset_week
        HorizonPreset.MONTH -> R.string.reports_preset_month
        HorizonPreset.THREE_MONTHS -> R.string.reports_preset_three_months
    }
