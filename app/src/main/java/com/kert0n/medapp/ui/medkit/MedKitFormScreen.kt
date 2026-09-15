package com.kert0n.medapp.ui.medkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.presentation.medkit.MedKitFormError
import com.kert0n.medapp.presentation.medkit.MedKitFormPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitFormUiState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState

/**
 * Создание и правка аптечки (PLAN H3 №3). Обязательное здесь одно — название; место хранения
 * человек указывает, если хочет.
 *
 * **Кнопка не гаснет.** Погашенная не объясняет, чего не хватает: человек видит серое
 * «Сохранить» и гадает. Нажал — форма называет поле и причину, а ввод отказ снимает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedKitFormScreen(
    state: MedKitFormUiState,
    onEdit: (MedKitFormPresentationDTO) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val editing = state as? MedKitFormUiState.Editing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editing?.isEditing == true) R.string.med_kit_edit else R.string.med_kit_new
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (state) {
            MedKitFormUiState.Loading -> LoadingState(Modifier.padding(padding))
            // Полки нет — это отказ, а не пустая форма: заполненную человек сохранил бы и не
            // понял, куда делась его правка.
            MedKitFormUiState.Gone -> ErrorMessage(
                text = stringResource(R.string.med_kit_gone),
                modifier = Modifier.padding(padding)
            )
            is MedKitFormUiState.Editing -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = state.form.name,
                    onValueChange = { onEdit(state.form.copy(name = it)) },
                    label = { Text(stringResource(R.string.med_kit_name)) },
                    singleLine = true,
                    isError = state.error is MedKitFormError.Input &&
                        state.error != MedKitFormError.Input.LOCATION_TOO_LONG,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.form.location,
                    onValueChange = { onEdit(state.form.copy(location = it)) },
                    label = { Text(stringResource(R.string.med_kit_location)) },
                    singleLine = true,
                    isError = state.error == MedKitFormError.Input.LOCATION_TOO_LONG,
                    modifier = Modifier.fillMaxWidth()
                )
                state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                ) { Text(stringResource(R.string.action_save)) }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

/**
 * Слова отказа подбирает экран: причина — значение, и текст к ней живёт в `R.string.*` (H1).
 * Предел длины берётся у самой аптечки, а не повторяется здесь числом.
 */
@Composable
private fun MedKitFormError.message(): String = when (this) {
    MedKitFormError.Input.NAME_EMPTY -> stringResource(R.string.med_kit_name_empty)
    MedKitFormError.Input.NAME_TOO_LONG ->
        pluralStringResource(R.plurals.med_kit_name_too_long, MedKit.NAME_MAX_LENGTH, MedKit.NAME_MAX_LENGTH)
    MedKitFormError.Input.LOCATION_TOO_LONG -> pluralStringResource(
        R.plurals.med_kit_location_too_long, MedKit.LOCATION_MAX_LENGTH, MedKit.LOCATION_MAX_LENGTH
    )
    MedKitFormError.Busy -> stringResource(R.string.med_kit_busy)
}
