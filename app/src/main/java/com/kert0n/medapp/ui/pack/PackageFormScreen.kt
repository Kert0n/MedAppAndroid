package com.kert0n.medapp.ui.pack

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.pack.PackageFormError
import com.kert0n.medapp.presentation.pack.PackageFormPresentationDTO
import com.kert0n.medapp.presentation.pack.withForm
import com.kert0n.medapp.presentation.pack.PackageFormUiState
import com.kert0n.medapp.presentation.pack.Suggestions
import com.kert0n.medapp.presentation.pack.TemplatePresentationDTO
import com.kert0n.medapp.ui.DateField
import com.kert0n.medapp.ui.Form
import com.kert0n.medapp.ui.PickerField
import com.kert0n.medapp.ui.text

/**
 * Заведение и правка упаковки (PLAN H3 №7, №8).
 *
 * **Все поля видны сразу** (ТЗ 4.1.1.1): обязательные отличает не место, а подпись
 * «обязательно», и она исчезает, как только поле заполнено. Прятать остальные в свёрнутый
 * раздел значило бы прятать половину того, что человек про коробку знает.
 *
 * **В правке количество и аптечка показаны, но не правятся:** у пересчёта и переноса свой след,
 * а у правки описания его нет. Рядом с количеством стоит ссылка на пересчёт — иначе человек
 * ищет действие по экранам.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageFormScreen(
    state: PackageFormUiState,
    onEdit: (PackageFormPresentationDTO) -> Unit,
    onPick: (TemplatePresentationDTO) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onRecount: () -> Unit,
    modifier: Modifier = Modifier
) {
    val form = state.form
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isEditing) R.string.pack_edit else R.string.pack_new))
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
        Form(
            modifier = Modifier.padding(padding),
            actions = {
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
        ) {
            if (state.isEditing) {
                // Аптечка и количество показаны, но не правятся: их меняют перенос и пересчёт.
                Fact(stringResource(R.string.pack_med_kit), state.storedMedKitName())
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Fact(stringResource(R.string.pack_amount), state.storedAmount())
                    TextButton(onClick = onRecount, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                        Text(stringResource(R.string.pack_recount))
                    }
                }
            } else {
                PickerField(
                    label = stringResource(R.string.pack_med_kit),
                    selected = state.medKits.firstOrNull { it.id == form.medKitId },
                    options = state.medKits,
                    optionText = { it.name },
                    onPick = { onEdit(form.copy(medKitId = it.id)) },
                    isError = state.error?.field == PackageFormError.Field.MED_KIT,
                    emptyText = stringResource(R.string.pack_no_med_kits),
                    supporting = stringResource(R.string.field_required).takeIf { form.medKitId == null }
                )
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { onEdit(form.copy(name = it)) },
                label = { Text(stringResource(R.string.pack_name)) },
                singleLine = true,
                isError = state.error?.field == PackageFormError.Field.NAME,
                supportingText = { if (form.name.isBlank()) Text(stringResource(R.string.field_required)) },
                modifier = Modifier.fillMaxWidth()
            )

            SuggestionList(state.suggestions, onPick)

            // Форма выпуска — перед количеством: она подсказывает единицу, которой это меряют.
            PickerField(
                label = stringResource(R.string.pack_form),
                selected = state.forms.firstOrNull { it.id == form.form?.id },
                options = state.forms,
                optionText = { it.name },
                onPick = { onEdit(form.withForm(it, state.units)) },
                isError = state.error?.field == PackageFormError.Field.FORM,
                emptyText = stringResource(R.string.pack_no_forms)
            )

            if (!state.isEditing) {
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { onEdit(form.copy(amount = it)) },
                    label = { Text(stringResource(R.string.pack_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = state.error?.field == PackageFormError.Field.AMOUNT,
                    supportingText = { if (form.amount.isBlank()) Text(stringResource(R.string.field_required)) },
                    modifier = Modifier.fillMaxWidth()
                )
                PickerField(
                    label = stringResource(R.string.pack_unit),
                    selected = state.units.firstOrNull { it.id == form.unit?.id },
                    options = state.units,
                    optionText = { it.name },
                    onPick = { onEdit(form.copy(unit = it)) },
                    isError = state.error?.field == PackageFormError.Field.UNIT,
                    emptyText = stringResource(R.string.pack_no_units),
                    supporting = stringResource(R.string.field_required).takeIf { form.unit == null }
                )
            }
            OutlinedTextField(
                value = form.expiresOn,
                onValueChange = { onEdit(form.copy(expiresOn = it)) },
                label = { Text(stringResource(R.string.pack_expiry)) },
                singleLine = true,
                isError = state.error?.field == PackageFormError.Field.EXPIRY,
                // Срок перепечатывают с коробки как есть: и «03.2027», и «31.03.2027».
                supportingText = { Text(stringResource(R.string.pack_expiry_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.category,
                onValueChange = { onEdit(form.copy(category = it)) },
                label = { Text(stringResource(R.string.pack_category)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.manufacturer,
                onValueChange = { onEdit(form.copy(manufacturer = it)) },
                label = { Text(stringResource(R.string.pack_manufacturer)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.country,
                onValueChange = { onEdit(form.copy(country = it)) },
                label = { Text(stringResource(R.string.pack_country)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.description,
                onValueChange = { onEdit(form.copy(description = it)) },
                label = { Text(stringResource(R.string.pack_description)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.hintAmount,
                onValueChange = { onEdit(form.copy(hintAmount = it)) },
                label = { Text(stringResource(R.string.pack_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.error?.field == PackageFormError.Field.HINT,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.note,
                onValueChange = { onEdit(form.copy(note = it)) },
                label = { Text(stringResource(R.string.pack_note)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.price,
                onValueChange = { onEdit(form.copy(price = it)) },
                label = { Text(stringResource(R.string.pack_price)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = state.error?.field == PackageFormError.Field.PRICE,
                modifier = Modifier.fillMaxWidth()
            )
            DateField(
                label = stringResource(R.string.pack_purchased_on),
                value = form.purchasedOn,
                onPick = { onEdit(form.copy(purchasedOn = it)) }
            )
            DateField(
                label = stringResource(R.string.pack_opened_on),
                value = form.openedOn,
                onPick = { onEdit(form.copy(openedOn = it)) }
            )

        }
    }
}

/** Сведение, которое показано, но не правится. Подпись сверху — так её читает и экранный чтец. */
@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PackageFormUiState.storedMedKitName(): String =
    medKits.firstOrNull { it.id == stored?.medKitId }?.name ?: stringResource(R.string.pack_med_kit_unknown)

@Composable
private fun PackageFormUiState.storedAmount(): String =
    stored?.let { stringResource(R.string.pack_left, it.quantity.amount, it.quantity.unit.name) }.orEmpty()

/** Текст отказа — по его причине: слова подбирает экран, а не сценарий (PLAN H1). */
@Composable
private fun PackageFormError.message(): String = when (this) {
    PackageFormError.MedKitMissing -> stringResource(R.string.pack_med_kit_missing)
    PackageFormError.NameEmpty -> stringResource(R.string.pack_name_empty)
    PackageFormError.UnitMissing -> stringResource(R.string.pack_unit_missing)
    PackageFormError.AmountIsZero -> stringResource(R.string.pack_amount_is_zero)
    PackageFormError.HintIsZero -> stringResource(R.string.pack_hint_is_zero)
    PackageFormError.FormUnknown -> stringResource(R.string.pack_unknown_in_vocabulary)
    PackageFormError.FormClearUnsupported -> stringResource(R.string.pack_form_clear_unsupported)
    PackageFormError.MedKitGone -> stringResource(R.string.pack_med_kit_gone)
    PackageFormError.MedKitBusy -> stringResource(R.string.pack_med_kit_busy)
    PackageFormError.PackageGone -> stringResource(R.string.pack_gone)
    PackageFormError.PackageBusy -> stringResource(R.string.pack_busy)
    is PackageFormError.Amount -> stringResource(reason.text)
    is PackageFormError.Hint -> stringResource(reason.text)
    is PackageFormError.Expiry -> stringResource(reason.text)
    is PackageFormError.Price -> stringResource(reason.text)
    is PackageFormError.TooLong -> pluralStringResource(R.plurals.pack_too_long, limit, limit)
}

/**
 * Подсказки справочника под полем названия (PLAN H3 №7, U2). Состояния различимы словами: ничего
 * не спрашивали — ничего; ищем; нашлось — строки; не нашлось — не ошибка; недоступно — причина, и
 * форма живёт. Список короткий и известен целиком — `Column`, а не `LazyColumn` внутри прокрутки.
 */
@Composable
private fun SuggestionList(suggestions: Suggestions, onPick: (TemplatePresentationDTO) -> Unit) {
    when (suggestions) {
        Suggestions.None -> Unit
        Suggestions.Searching -> Note(stringResource(R.string.pack_suggestions_searching))
        is Suggestions.Unavailable -> Text(
            stringResource(R.string.pack_suggestions_unavailable, stringResource(suggestions.reason.text)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        is Suggestions.Found -> if (suggestions.templates.isEmpty()) {
            Note(stringResource(R.string.pack_suggestions_none))
        } else {
            Column(Modifier.fillMaxWidth()) {
                for (template in suggestions.templates) {
                    ListItem(
                        headlineContent = { Text(template.name) },
                        // Чем узнать карточку: форма и производитель — что известно; пустое молчит.
                        supportingContent = listOfNotNull(template.form?.name, template.manufacturer)
                            .takeIf { it.isNotEmpty() }
                            ?.let { known -> { Text(known.joinToString(" · ")) } },
                        modifier = Modifier.clickable { onPick(template) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
