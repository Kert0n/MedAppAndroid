package com.kert0n.medapp.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.presentation.course.CourseFormError
import com.kert0n.medapp.presentation.course.CourseFormPresentationDTO
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState

/**
 * Редактор лечения (PLAN H3 №15). Обязательно одно — название: черновик с одной заметкой —
 * «записал у врача, куплю завтра» — сохраняется (D5).
 *
 * **Кнопка не гаснет.** Погашенная не объясняет, чего не хватает; нажал — форма называет поле и
 * причину, а ввод отказ снимает. Удаление черновика спрашивается до сценария: в нём может лежать
 * единственная запись назначения от врача (H3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseFormScreen(
    state: CourseFormUiState,
    onEdit: (CourseFormPresentationDTO) -> Unit,
    onSave: () -> Unit,
    onAskToDiscard: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val editing = state as? CourseFormUiState.Editing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editing?.mode == CourseFormUiState.Mode.DRAFT) R.string.course_edit else R.string.course_new
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { if (editing?.mode == CourseFormUiState.Mode.DRAFT) DraftMenu(onAskToDiscard) }
            )
        }
    ) { padding ->
        when (state) {
            CourseFormUiState.Loading -> LoadingState(Modifier.padding(padding))
            // Черновика нет — это отказ, а не пустая форма: заполненную человек сохранил бы и
            // не понял, куда делась его правка.
            CourseFormUiState.Gone -> ErrorMessage(text = stringResource(R.string.course_gone), modifier = Modifier.padding(padding))
            is CourseFormUiState.Editing -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = state.form.title,
                    onValueChange = { onEdit(state.form.copy(title = it)) },
                    label = { Text(stringResource(R.string.course_title)) },
                    singleLine = true,
                    isError = state.error == CourseFormError.Input.TITLE_EMPTY ||
                        state.error == CourseFormError.Input.TITLE_TOO_LONG,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.form.note,
                    onValueChange = { onEdit(state.form.copy(note = it)) },
                    label = { Text(stringResource(R.string.course_note)) },
                    minLines = 2,
                    isError = state.error == CourseFormError.Input.NOTE_TOO_LONG,
                    modifier = Modifier.fillMaxWidth()
                )
                state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                ) { Text(stringResource(R.string.action_save)) }
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
    if (editing?.asksToDiscard == true) DiscardDialog(onConfirm = onConfirmDiscard, onDismiss = onDismissDiscard)
}

/** Меню черновика: удаление — одно действие, но за меню, а не на виду: случайно его не нажать. */
@Composable
private fun DraftMenu(onAskToDiscard: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.course_discard)) },
            onClick = { open = false; onAskToDiscard() }
        )
    }
}

@Composable
private fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_discard_title)) },
        text = { Text(stringResource(R.string.course_discard_explained)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.course_discard)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Слова отказа подбирает экран; предел длины берётся у записи эпизода, а не повторяется числом. */
@Composable
private fun CourseFormError.message(): String = when (this) {
    CourseFormError.Input.TITLE_EMPTY -> stringResource(R.string.course_title_empty)
    CourseFormError.Input.TITLE_TOO_LONG ->
        pluralStringResource(R.plurals.course_title_too_long, CourseRecord.TITLE_MAX_LENGTH, CourseRecord.TITLE_MAX_LENGTH)
    CourseFormError.Input.NOTE_TOO_LONG ->
        pluralStringResource(R.plurals.course_note_too_long, CourseRecord.NOTE_MAX_LENGTH, CourseRecord.NOTE_MAX_LENGTH)
    CourseFormError.Stale -> stringResource(R.string.course_stale)
}
