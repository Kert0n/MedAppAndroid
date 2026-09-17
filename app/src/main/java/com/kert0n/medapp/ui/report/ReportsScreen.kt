package com.kert0n.medapp.ui.report

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.report.ReportsUiState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState

/**
 * Место «Отчёты» — личная статистика человека (PLAN H3 «Набор аналитики», экран 26).
 *
 * Записи здесь нет: отчёты отвечают на вопрос и ничего не меняют, — поэтому ни подтверждений, ни
 * сообщений об исходе на экране не бывает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(state: ReportsUiState, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.reports_title)) }) }
    ) { padding ->
        when (val summary = state.summary) {
            ScreenState.Loading -> LoadingState(Modifier.padding(padding))
            is ScreenState.Failed -> ErrorMessage(summary.reason, Modifier.padding(padding))
            is ScreenState.Ready -> SummaryReport(summary.value, Modifier.padding(padding))
        }
    }
}
