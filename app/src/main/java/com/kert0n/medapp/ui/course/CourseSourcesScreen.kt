package com.kert0n.medapp.ui.course

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.presentation.course.CourseSourcePresentationDTO
import com.kert0n.medapp.presentation.course.CourseSourcesMessage
import com.kert0n.medapp.presentation.course.CourseSourcesUiState
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.presentation.value.toPresentationDTO
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * Источники лечения (PLAN H3 №16): стек коробок в порядке расходования. Порядок меняется
 * перетаскиванием за ручку и теми же двумя действиями у экранного чтеца — жест ему недоступен.
 *
 * Кнопки «Сохранить» здесь нет: отпущенная строка записана, и обеспечение с пределами приходит
 * следующим чтением. Спрашивают только об отвязке у идущего лечения — она освобождает коробку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSourcesScreen(
    state: CourseSourcesUiState,
    onMove: (Int, Int) -> Unit,
    onDetach: (Uuid) -> Unit,
    onConfirmDetach: () -> Unit,
    onDismissDetach: () -> Unit,
    onDismissMessage: () -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.title ?: stringResource(R.string.course_sources)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))
            state.isGone -> ErrorMessage(
                text = stringResource(R.string.course_missing),
                modifier = Modifier.padding(padding)
            )
            state.sources.isEmpty() -> EmptyState(
                text = stringResource(R.string.course_sources_empty),
                modifier = Modifier.padding(padding),
                actionText = stringResource(R.string.course_sources_add).takeUnless { state.isFinished },
                onAction = onAdd.takeUnless { state.isFinished }
            )
            else -> Sources(state, onMove, onDetach, onDismissMessage, onAdd, Modifier.padding(padding))
        }
    }
    state.asksToDetach?.let {
        DetachDialog(onConfirm = onConfirmDetach, onDismiss = onDismissDetach)
    }
}

@Composable
private fun Sources(
    state: CourseSourcesUiState,
    onMove: (Int, Int) -> Unit,
    onDetach: (Uuid) -> Unit,
    onDismissMessage: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var shift by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    val count = state.sources.size
    Column(modifier.fillMaxSize()) {
        state.message?.let { message ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(message.words(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_got_it)) }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(state.sources, key = { _, source -> source.packageId }) { index, source ->
                val held = dragging == index
                SourceRow(
                    source = source,
                    isFinished = state.isFinished,
                    onDetach = { onDetach(source.packageId) },
                    onMoveUp = { if (index > 0) onMove(index, index - 1) },
                    onMoveDown = { if (index < count - 1) onMove(index, index + 1) },
                    held = held,
                    modifier = Modifier
                        .zIndex(if (held) 1f else 0f)
                        .graphicsLayer { translationY = if (held) shift else 0f }
                        .onSizeChanged { if (it.height > 0) rowHeight = it.height },
                    handleModifier = Modifier.pointerInput(index, count, rowHeight) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = index; shift = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                shift += amount.y
                            },
                            onDragEnd = {
                                // Куда строка уехала: шаг — высота соседней строки, и она же
                                // говорит, через сколько соседей человек её перенёс.
                                val moved = if (rowHeight > 0) (shift / rowHeight).roundToInt() else 0
                                val target = (index + moved).coerceIn(0, count - 1)
                                dragging = null
                                shift = 0f
                                if (target != index) onMove(index, target)
                            },
                            onDragCancel = { dragging = null; shift = 0f }
                        )
                    }
                )
            }
        }
        if (!state.isFinished) {
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().padding(16.dp).defaultMinSize(minHeight = 48.dp)
            ) { Text(stringResource(R.string.course_sources_add)) }
        }
    }
}

@Composable
private fun SourceRow(
    source: CourseSourcePresentationDTO,
    isFinished: Boolean,
    onDetach: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    held: Boolean,
    modifier: Modifier,
    handleModifier: Modifier
) {
    val up = stringResource(R.string.course_source_move_up)
    val down = stringResource(R.string.course_source_move_down)
    ListItem(
        headlineContent = { Text(source.name) },
        supportingContent = {
            Column {
                Text(source.place(), style = MaterialTheme.typography.bodySmall)
                when (val fault = source.fault) {
                    null -> Text(source.allocation())
                    else -> Text(fault.words(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_drag_handle),
                contentDescription = stringResource(R.string.course_source_handle, source.name),
                modifier = handleModifier
            )
        },
        trailingContent = {
            if (!isFinished) {
                TextButton(onClick = onDetach) { Text(stringResource(R.string.course_source_detach)) }
            }
        },
        // Поднятая строка видна тенью, а отключённый источник — приглушён: он ничего не даёт.
        tonalElevation = if (held) 8.dp else ListItemDefaults.Elevation,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (source.fault == null) 1f else 0.6f)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(up) { onMoveUp(); true },
                    CustomAccessibilityAction(down) { onMoveDown(); true }
                )
            }
    )
}

/** Где коробка лежит и сколько в ней моего: полка, срок, свободное — одной строкой. */
@Composable
private fun CourseSourcePresentationDTO.place(): String = listOfNotNull(
    medKitName,
    expiresOn?.let { stringResource(R.string.course_source_expires, it.toPresentationDTO().text) },
    availableToMe?.let { stringResource(R.string.course_source_free, it.amount, it.unit.name) }
).joinToString(" · ")

/** Сколько приёмов выделено — и сколько это в единицах коробки: то же число привычной мерой. */
@Composable
private fun CourseSourcePresentationDTO.allocation(): String {
    val doses = pluralStringResource(R.plurals.course_source_allocated, allocatedDoses, allocatedDoses)
    return allocatedAmount?.let { "$doses · ${it.amount} ${it.unit.name}" } ?: doses
}

@Composable
private fun CourseSource.Fault.words(): String = stringResource(
    when (this) {
        CourseSource.Fault.UNIT_MISMATCH -> R.string.course_source_fault_unit
        CourseSource.Fault.FORM_MISMATCH -> R.string.course_source_fault_form
    }
)

/** Отказ сценария словами: человек читает, что случилось, и стек остаётся как был. */
@Composable
internal fun CourseSourcesMessage.words(): String = when (this) {
    is CourseSourcesMessage.Taken ->
        name?.let { stringResource(R.string.course_source_taken_named, it) }
            ?: stringResource(R.string.course_source_taken)
    is CourseSourcesMessage.Unusable ->
        name?.let { stringResource(R.string.course_source_unusable_named, it) }
            ?: stringResource(R.string.course_source_unusable)
    is CourseSourcesMessage.Refused -> stringResource(reason.text)
    is CourseSourcesMessage.BeyondLimit ->
        pluralStringResource(R.plurals.course_source_beyond_limit, limit, limit)
    CourseSourcesMessage.Finished -> stringResource(R.string.course_finished)
}

@Composable
private fun DetachDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_source_detach_title)) },
        text = { Text(stringResource(R.string.course_source_detach_explained)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.course_source_detach)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
