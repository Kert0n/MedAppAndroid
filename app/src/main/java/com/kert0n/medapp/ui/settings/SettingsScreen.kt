package com.kert0n.medapp.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.settings.SettingsFormError
import com.kert0n.medapp.presentation.settings.SettingsFormPresentationDTO
import com.kert0n.medapp.presentation.settings.SettingsUiState
import com.kert0n.medapp.ui.Form
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.PickerField
import com.kert0n.medapp.ui.TimeField

/**
 * Настройки (PLAN H3 №27): пороги и время уведомлений, интервал обмена. Форма, а не список
 * переключателей с мгновенным применением: решение — о всём наборе, и записывается оно одним
 * нажатием, как остальные формы приложения. Первым — то, что выключают чаще всего.
 *
 * Пределы величин форма не знает: ступени интервала строят `SyncInterval` сами, а минуты и дни
 * разбирает представление и называет поле, которое не приняли.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEdit: (SettingsFormPresentationDTO) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        when (state) {
            SettingsUiState.Loading -> LoadingState(Modifier.padding(padding))
            is SettingsUiState.Editing -> Form(
                modifier = Modifier.padding(padding),
                actions = {
                    state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = onSave,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    ) { Text(stringResource(R.string.action_save)) }
                }
            ) {
                val form = state.form
                SwitchRow(
                    text = stringResource(R.string.settings_intake_reminders),
                    checked = form.intakeReminders,
                    onChecked = { onEdit(form.copy(intakeReminders = it)) }
                )
                OutlinedTextField(
                    value = form.snoozeMinutes,
                    onValueChange = { onEdit(form.copy(snoozeMinutes = it)) },
                    label = { Text(stringResource(R.string.settings_snooze_minutes)) },
                    singleLine = true,
                    enabled = form.intakeReminders,
                    isError = state.error.isAbout(SettingsFormError.Input.SNOOZE_NOT_A_NUMBER, SettingsFormError.Input.SNOOZE_NOT_FORWARD),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Section(stringResource(R.string.settings_section_expiry))
                SwitchRow(
                    text = stringResource(R.string.settings_expiry_source_reminders),
                    checked = form.expirySourceReminders,
                    onChecked = { onEdit(form.copy(expirySourceReminders = it)) }
                )
                OutlinedTextField(
                    value = form.coverageThresholdDays,
                    onValueChange = { onEdit(form.copy(coverageThresholdDays = it)) },
                    label = { Text(stringResource(R.string.settings_threshold_days)) },
                    singleLine = true,
                    isError = state.error.isAbout(SettingsFormError.Input.THRESHOLD_NOT_A_NUMBER, SettingsFormError.Input.THRESHOLD_NEGATIVE),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Section(stringResource(R.string.settings_section_digest))
                SwitchRow(
                    text = stringResource(R.string.settings_digest),
                    checked = form.digest,
                    onChecked = { onEdit(form.copy(digest = it)) }
                )
                TimeField(
                    label = stringResource(R.string.settings_digest_at),
                    value = form.digestAt,
                    onPick = { onEdit(form.copy(digestAt = it)) },
                    enabled = form.digest
                )
                Section(stringResource(R.string.settings_section_remote))
                SwitchRow(
                    text = stringResource(R.string.settings_remote_change),
                    checked = form.remoteChange,
                    onChecked = { onEdit(form.copy(remoteChange = it)) }
                )
                Section(stringResource(R.string.settings_section_sync))
                val resources = LocalResources.current
                PickerField(
                    label = stringResource(R.string.settings_sync_interval),
                    selected = form.syncIntervalMinutes,
                    options = SettingsFormPresentationDTO.SYNC_INTERVAL_STEPS,
                    // Меню строит пункты вне композиции: слова берутся у ресурсов напрямую.
                    optionText = { it.intervalWords(resources) },
                    onPick = { onEdit(form.copy(syncIntervalMinutes = it)) }
                )
            }
        }
    }
}

/** Заголовок секции формы: слова, а не черта, — черта на маленьком экране читается как конец. */
@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/**
 * Переключатель со словами слева: нажимается вся строка (роль `Switch` для экранного чтеца), а
 * не один рычажок в 52 dp.
 */
@Composable
private fun SwitchRow(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Ступень интервала — словами: до часа минутами, дальше часами. */
private fun Long.intervalWords(resources: Resources): String =
    if (this < 60) resources.getString(R.string.settings_interval_minutes, this)
    else resources.getString(R.string.settings_interval_hours, this / 60)

private fun SettingsFormError?.isAbout(vararg inputs: SettingsFormError.Input): Boolean = this in inputs

/** Слова отказа подбирает экран: причина — значение, текст к ней живёт в `R.string.*` (H1). */
@Composable
private fun SettingsFormError.message(): String = when (this) {
    SettingsFormError.Input.SNOOZE_NOT_A_NUMBER -> stringResource(R.string.settings_snooze_not_a_number)
    SettingsFormError.Input.SNOOZE_NOT_FORWARD -> stringResource(R.string.settings_snooze_not_forward)
    SettingsFormError.Input.THRESHOLD_NOT_A_NUMBER -> stringResource(R.string.settings_threshold_not_a_number)
    SettingsFormError.Input.THRESHOLD_NEGATIVE -> stringResource(R.string.settings_threshold_negative)
    SettingsFormError.NotSaved -> stringResource(R.string.settings_not_saved)
}
