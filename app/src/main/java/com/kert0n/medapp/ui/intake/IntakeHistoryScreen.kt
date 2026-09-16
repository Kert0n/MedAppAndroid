package com.kert0n.medapp.ui.intake

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.intake.IntakeHistoryRowPresentationDTO
import com.kert0n.medapp.presentation.intake.IntakeHistoryUiState
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.TIME
import com.kert0n.medapp.ui.words

/**
 * История приёмов (PLAN H3 №19): что принимали и чем это кончилось, сверху — последнее.
 *
 * Экран один на два вопроса — «что я принимал из этой коробки» и «как шло это лечение», — и
 * различает их только то, чего в строке не повторяют: пришли с коробки — строка называет лечение,
 * пришли с лечения — коробку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeHistoryScreen(
    state: IntakeHistoryUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifEmpty { stringResource(R.string.intake_history_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> LoadingState(Modifier.fillMaxSize())
                state.isEmpty -> EmptyState(stringResource(R.string.intake_history_empty), Modifier.fillMaxSize())
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                        // Черта между фактами: у строки должна быть граница, иначе список читается
                        // сплошняком (Material 3, замечание владельца 2026-09-16).
                        if (index > 0) HorizontalDivider()
                        HistoryRow(row)
                    }
                }
            }
        }
    }
}

/**
 * Строка истории: сколько приняли, когда и откуда — а справа то, чем это кончилось.
 *
 * Строк **две**, а не три: у трёхстрочной строки Material 3 прижимает боковые части к верху, и
 * слово состояния уезжает от того, к чему относится. День и время стоят в той же второй строке, что
 * и лечение, — читаются они вместе.
 */
@Composable
private fun HistoryRow(row: IntakeHistoryRowPresentationDTO) {
    ListItem(
        headlineContent = { Text(row.amount?.words() ?: stringResource(R.string.intake_history_no_amount)) },
        supportingContent = { Text(row.details()) },
        trailingContent = { Text(row.stateWords(), style = MaterialTheme.typography.labelLarge) }
    )
}

/** Когда и откуда: «10.03.2027 · 09:12 · Цетрин». Чего не знаем, того в строке нет. */
private fun IntakeHistoryRowPresentationDTO.details(): String =
    listOfNotNull("${DAY.format(on)} · ${TIME.format(at)}", subject).joinToString(" · ")

@Composable
private fun IntakeHistoryRowPresentationDTO.stateWords(): String = when (state) {
    IntakeHistoryRowPresentationDTO.State.TAKEN -> stringResource(R.string.intake_state_taken, TIME.format(at))
    IntakeHistoryRowPresentationDTO.State.MISSED -> stringResource(R.string.intake_state_missed)
    IntakeHistoryRowPresentationDTO.State.CANCELLED -> stringResource(R.string.intake_state_cancelled)
    IntakeHistoryRowPresentationDTO.State.ONE_OFF -> stringResource(R.string.intake_state_one_off, TIME.format(at))
    IntakeHistoryRowPresentationDTO.State.PLANNED -> stringResource(R.string.intake_state_planned)
}
