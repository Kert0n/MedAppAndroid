package com.kert0n.medapp.ui.report

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.report.HorizonPreset
import com.kert0n.medapp.presentation.report.PeriodPreset
import com.kert0n.medapp.presentation.report.ReportsUiState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState
import java.time.LocalDate
import kotlin.uuid.Uuid

/** Три отчёта места «Отчёты» — три его состояния (PLAN H3 «Набор аналитики», C1). */
enum class ReportsMode { SUMMARY, FUTURE, SPENT }

/**
 * Место «Отчёты» — личная статистика человека (экран 26).
 *
 * Записи здесь нет: отчёты отвечают на вопрос и ничего не меняют, — поэтому ни подтверждений, ни
 * сообщений об исходе на экране не бывает. Режим держит оболочка, как у места «План»: он часть
 * места, а не состояние данных, и переживает уход в другое место.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    state: ReportsUiState,
    mode: ReportsMode,
    onMode: (ReportsMode) -> Unit,
    onHorizonPreset: (HorizonPreset) -> Unit,
    onHorizonUntil: (LocalDate) -> Unit,
    onPeriodPreset: (PeriodPreset) -> Unit,
    onPeriod: (LocalDate, LocalDate) -> Unit,
    onCourse: (Uuid) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.reports_title)) }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ReportsMode.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = mode == entry,
                        onClick = { onMode(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ReportsMode.entries.size),
                        label = { Text(stringResource(entry.label)) }
                    )
                }
            }
            when (mode) {
                ReportsMode.SUMMARY -> Report(state.summary) { SummaryReport(it) }
                ReportsMode.FUTURE -> Report(state.future) {
                    FutureReport(report = it, onPreset = onHorizonPreset, onUntil = onHorizonUntil, onCourse = onCourse)
                }
                ReportsMode.SPENT -> Report(state.spent) {
                    SpentReport(report = it, onPreset = onPeriodPreset, onPeriod = onPeriod, onCourse = onCourse)
                }
            }
        }
    }
}

/**
 * Три вида одного показа: ожидание, отказ и содержимое. Отказ у местных чтений не случается, но
 * показать его есть чем — молчать о нём значило бы оставить человека перед вечным кружком.
 */
@Composable
private fun <T> Report(state: ScreenState<T>, content: @Composable (T) -> Unit) {
    when (state) {
        ScreenState.Loading -> LoadingState()
        is ScreenState.Failed -> ErrorMessage(state.reason)
        is ScreenState.Ready -> content(state.value)
    }
}

@get:StringRes
private val ReportsMode.label: Int
    get() = when (this) {
        ReportsMode.SUMMARY -> R.string.reports_mode_summary
        ReportsMode.FUTURE -> R.string.reports_mode_future
        ReportsMode.SPENT -> R.string.reports_mode_spent
    }
