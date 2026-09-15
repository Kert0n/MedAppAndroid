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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.presentation.medkit.MedKitFormError
import com.kert0n.medapp.presentation.medkit.MedKitFormPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitFormUiState
import com.kert0n.medapp.presentation.medkit.MedKitFormViewModel
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState

/**
 * Где форма берёт своё состояние и куда уходит, записав. Экран об этом не знает: он получает
 * `state` и действия (PLAN H1).
 */
@Composable
fun MedKitFormRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MedKitFormViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved = (state as? MedKitFormUiState.Editing)?.isSaved == true
    LaunchedEffect(saved) { if (saved) onDone() }
    MedKitFormScreen(
        state = state,
        onEdit = viewModel::edit,
        onSave = viewModel::save,
        onCancel = onDone,
        modifier = modifier
    )
}

/**
 * Заведение и правка аптечки (PLAN H3 №3). Обязательно одно название; место хранения человек
 * указывает, если оно ему нужно.
 *
 * Кнопка сохранения не гаснет: погашенная не объясняет, чего не хватает, — отказ называет поле и
 * причину, а ввод его снимает.
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
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val editing = (state as? MedKitFormUiState.Editing)?.isEditing == true
                    Text(stringResource(if (editing) R.string.med_kit_edit else R.string.med_kit_new))
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
            // Полки нет — отказ, а не пустая форма: заполненную человек бы потерял.
            MedKitFormUiState.Gone -> ErrorMessage(
                text = stringResource(R.string.med_kit_gone),
                modifier = Modifier.padding(padding)
            )
            is MedKitFormUiState.Editing -> Fields(state, onEdit, onSave, onCancel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun Fields(
    state: MedKitFormUiState.Editing,
    onEdit: (MedKitFormPresentationDTO) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = state.form.name,
            onValueChange = { onEdit(state.form.copy(name = it)) },
            label = { Text(stringResource(R.string.med_kit_name)) },
            isError = state.error?.field == MedKitFormError.Field.NAME,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.form.location,
            onValueChange = { onEdit(state.form.copy(location = it)) },
            label = { Text(stringResource(R.string.med_kit_location)) },
            isError = state.error?.field == MedKitFormError.Field.LOCATION,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        state.error?.let { reason ->
            Text(
                text = reason.message(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
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

/** Текст причины — её свойство: экран не подбирает слова сам. */
@Composable
private fun MedKitFormError.message(): String = when (this) {
    MedKitFormError.Input.NAME_EMPTY -> stringResource(R.string.med_kit_name_empty)
    MedKitFormError.Input.NAME_TOO_LONG ->
        stringResource(R.string.med_kit_name_too_long, MedKit.NAME_MAX_LENGTH)
    MedKitFormError.Input.LOCATION_TOO_LONG ->
        stringResource(R.string.med_kit_location_too_long, MedKit.LOCATION_MAX_LENGTH)
    MedKitFormError.Busy -> stringResource(R.string.med_kit_busy)
}
