package com.kert0n.medapp.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.presentation.course.CourseCardUiState
import com.kert0n.medapp.presentation.course.CourseItemPresentationDTO
import com.kert0n.medapp.presentation.course.CoverageReductionPresentationDTO
import com.kert0n.medapp.presentation.course.CoursePresentationDTO
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState

/**
 * Карточка лечения (PLAN H3 №14). Первым — обеспечение: за ним человек сюда и приходит. Дальше
 * назначение словами, короткий список источников, сокращения обеспечения и пункты по датам.
 *
 * У законченного лечения плана нет: остаются плашка с исходом и история приёмов, а действий не
 * бывает — отменять и править уже нечего.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseCardScreen(
    state: CourseCardUiState,
    onEdit: () -> Unit,
    onSources: () -> Unit,
    onAskToCancel: () -> Unit,
    onConfirmCancel: () -> Unit,
    onDismissCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.course?.title ?: stringResource(R.string.course_card)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = { if (state.isRunning) RunningMenu(onEdit, onSources, onAskToCancel) }
            )
        }
    ) { padding ->
        val course = state.course
        when {
            state.isGone -> ErrorMessage(
                text = stringResource(R.string.course_missing),
                modifier = Modifier.padding(padding)
            )
            course == null -> LoadingState(Modifier.padding(padding))
            else -> Card(state, course, Modifier.padding(padding))
        }
    }
    if (state.asksToCancel) {
        CancelDialog(onConfirm = onConfirmCancel, onDismiss = onDismissCancel)
    }
}

@Composable
private fun Card(state: CourseCardUiState, course: CoursePresentationDTO, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("head") {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                course.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                // Обеспечение первым: за ним человек и открыл карточку.
                Coverage(state.coverage, isDraft = false)
                Text(course.prescriptionWords(), style = MaterialTheme.typography.bodyMedium)
                course.schedule?.let { Text(it.timesWords(), style = MaterialTheme.typography.bodySmall) }
                if (!state.isRunning) {
                    Text(course.closedWords(), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        if (state.reductions.isNotEmpty()) {
            item("reductions-title") { SectionTitle(R.string.course_reductions) }
            items(state.reductions, key = { it.id }) { Reduction(it) }
        }
        if (state.items.isNotEmpty()) {
            item("items-title") { SectionTitle(R.string.course_items) }
            items(state.items, key = { it.id }) { Item(it) }
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    HorizontalDivider()
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** Сокращение — событие: было столько, стало столько, из-за этой коробки и в этот день. */
@Composable
private fun Reduction(reduction: CoverageReductionPresentationDTO) {
    ListItem(
        headlineContent = {
            Text(
                stringResource(
                    R.string.course_reduction,
                    reduction.coveredBefore,
                    reduction.coveredAfter
                )
            )
        },
        supportingContent = {
            Text(listOfNotNull(reduction.on.format(DAY), reduction.packageName).joinToString(" · "))
        }
    )
}

/** Пункт: когда, сколько и из какой коробки; состояние — словами и значком, а не цветом. */
@Composable
private fun Item(item: CourseItemPresentationDTO) {
    ListItem(
        headlineContent = { Text("${item.on.format(DAY)} · ${item.at}") },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        "${item.amount.amount} ${item.amount.unit.name}",
                        item.packageName
                    ).joinToString(" · ")
                )
                Text(item.statusWords(), color = item.statusColor())
            }
        },
        leadingContent = {
            Icon(painterResource(item.statusIcon()), contentDescription = null, tint = item.statusColor())
        }
    )
}

@Composable
private fun CourseItemPresentationDTO.statusWords(): String = when (status) {
    IntakeStatus.PLANNED -> stringResource(R.string.course_item_planned)
    IntakeStatus.TAKEN -> takenAt?.let { stringResource(R.string.course_item_taken_at, it.toString()) }
        ?: stringResource(R.string.course_item_taken)
    IntakeStatus.MISSED -> stringResource(R.string.course_item_missed)
    IntakeStatus.CANCELLED -> stringResource(R.string.course_item_cancelled)
}

@Composable
private fun CourseItemPresentationDTO.statusColor() = when (status) {
    IntakeStatus.MISSED -> MaterialTheme.colorScheme.error
    IntakeStatus.TAKEN -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun CourseItemPresentationDTO.statusIcon(): Int = when (status) {
    IntakeStatus.PLANNED -> R.drawable.ic_schedule
    IntakeStatus.TAKEN -> R.drawable.ic_check_circle
    IntakeStatus.MISSED -> R.drawable.ic_warning
    IntakeStatus.CANCELLED -> R.drawable.ic_close
}

/** Чем лечение кончилось: «Завершён 24.09.2027» или «Отменён 24.09.2027». */
@Composable
private fun CoursePresentationDTO.closedWords(): String {
    val day = closedOn?.format(DAY).orEmpty()
    return when (kind) {
        CoursePresentationDTO.Kind.CANCELLED -> stringResource(R.string.course_cancelled_on, day)
        else -> stringResource(R.string.course_completed_on, day)
    }
}

@Composable
private fun RunningMenu(onEdit: () -> Unit, onSources: () -> Unit, onAskToCancel: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.course_edit_action)) },
            onClick = { open = false; onEdit() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.course_sources)) },
            onClick = { open = false; onSources() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.course_cancel_action)) },
            onClick = { open = false; onAskToCancel() }
        )
    }
}

@Composable
private fun CancelDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_cancel_title)) },
        text = { Text(stringResource(R.string.course_cancel_explained)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.course_cancel_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
