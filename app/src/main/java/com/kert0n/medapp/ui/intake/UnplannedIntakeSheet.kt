package com.kert0n.medapp.ui.intake

import com.kert0n.medapp.ui.pack.Marker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.presentation.intake.UnplannedIntakeError
import com.kert0n.medapp.presentation.intake.UnplannedIntakePresentationDTO
import com.kert0n.medapp.presentation.intake.UnplannedIntakeUiState
import com.kert0n.medapp.ui.text
import com.kert0n.medapp.ui.words

/**
 * Разовый приём (PLAN H3 №10) — листом снизу на карточке коробки: вопрос один, и ради него незачем
 * уводить человека с карточки, которую он и открыл, чтобы посмотреть на эту коробку.
 *
 * Доза стоит в поле подсказкой коробки и переписывается; единицу называет сама коробка — набирать
 * её человеку не из чего.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnplannedIntakeSheet(
    state: UnplannedIntakeUiState,
    onEdit: (UnplannedIntakePresentationDTO) -> Unit,
    onRecord: () -> Unit,
    onAcknowledge: () -> Unit,
    onDismissQuestions: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.intake_unplanned_title), style = MaterialTheme.typography.titleLarge)
            // Пока коробка перечитывается, на месте полей — загрузка той же высоты: лист не прыгает,
            // а набрать число по старому остатку нечем (PLAN E4).
            if (state.isLoading) {
                LoadingState(Modifier.fillMaxWidth().height(160.dp))
                return@Column
            }
            state.free?.let {
                Text(
                    // Единица названа один раз: «свободно 11 из 15 таблеток» — обе половины об
                    // одном и том же, и повторять её незачем.
                    stringResource(R.string.intake_free_of_total, it.amount, state.inTheBox?.words().orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.expired?.let {
                Marker(
                    icon = R.drawable.ic_expired,
                    text = stringResource(R.string.pack_expired_on, it.text),
                    color = MaterialTheme.colorScheme.error
                )
            }
            OutlinedTextField(
                value = state.form.amount,
                onValueChange = { onEdit(state.form.copy(amount = it)) },
                label = { Text(stringResource(R.string.intake_taken_amount)) },
                suffix = state.unit?.let { { Text(it.name) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.error != null,
                modifier = Modifier.fillMaxWidth()
            )
            state.error?.let { Text(it.words(), color = MaterialTheme.colorScheme.error) }
            // Кнопка не гаснет: погашенная не объясняет, чего не хватает (H3 №3).
            Button(
                onClick = onRecord,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) { Text(stringResource(R.string.intake_record)) }
        }
    }
    // Вопросы — тем же диалогом, что и на карточке пункта: спрашивает не экран, а сценарий.
    if (state.questions.isNotEmpty()) {
        IntakeQuestionsDialog(state.questions, onAcknowledge = onAcknowledge, onDismiss = onDismissQuestions)
    }
}

/** Слова беды подбирает экран: причина — значение, и текст к ней живёт в `R.string.*` (H1). */
@Composable
private fun UnplannedIntakeError.words(): String = when (this) {
    is UnplannedIntakeError.Amount -> stringResource(error.text)
    is UnplannedIntakeError.Rejected -> stringResource(reason.text)
}
