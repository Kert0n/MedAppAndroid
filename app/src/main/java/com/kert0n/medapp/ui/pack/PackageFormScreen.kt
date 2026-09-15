package com.kert0n.medapp.ui.pack

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.presentation.pack.PackageFormError
import com.kert0n.medapp.presentation.pack.PackageFormPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageFormUiState
import com.kert0n.medapp.presentation.pack.PackageFormViewModel
import com.kert0n.medapp.presentation.value.ExpiryDatePresentationError
import com.kert0n.medapp.presentation.value.MoneyPresentationError
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.ui.DateField
import com.kert0n.medapp.ui.ExpandableSection
import com.kert0n.medapp.ui.PickerField
import kotlin.uuid.Uuid

/**
 * Где форма берёт своё состояние и куда уходит, записав. Экран об этом не знает: он получает
 * `state` и действия (PLAN H1).
 */
@Composable
fun PackageFormRoute(
    onSaved: (Uuid) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeAmount: (Uuid) -> Unit = {},
    viewModel: PackageFormViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }
    PackageFormScreen(
        state = state,
        onEdit = viewModel::edit,
        onSave = viewModel::save,
        onCancel = onCancel,
        onChangeAmount = { state.stored?.id?.let(onChangeAmount) },
        modifier = modifier
    )
}

/**
 * Заведение упаковки (PLAN H3 №7). Сверху — четыре поля, без которых упаковки не бывает: аптечка,
 * название, количество и единица (PLAN C1). Всё остальное, что человек может знать о коробке,
 * лежит ниже в раскрываемом разделе — **все поля до одного** (ТЗ 4.1.1.1), но заполнять их сразу
 * он не обязан.
 *
 * Кнопка сохранения не гаснет: погашенная не объясняет, чего не хватает, — отказ называет поле и
 * причину, а ввод его снимает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageFormScreen(
    state: PackageFormUiState,
    onEdit: (PackageFormPresentationDTO) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeAmount: () -> Unit = {}
) {
    val form = state.form
    // Раскрыт ли раздел — дело самого экрана, а не состояния: за ним не стоит ни записи, ни
    // действия, и переживать смерть процесса ему достаточно через rememberSaveable.
    var optionalShown by rememberSaveable { mutableStateOf(false) }
    // Отказ в необязательном поле бесполезен, пока раздел свёрнут: человек не видит, что чинить.
    LaunchedEffect(state.error) {
        if (state.error?.field?.isRequired == false) optionalShown = true
    }
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.isEditing) {
                // Показано, но не правится: у переноса и пересчёта свой след, и ведут туда свои
                // экраны — сказать об этом здесь дешевле, чем заставить искать (PLAN D3).
                Stored(
                    label = stringResource(R.string.pack_med_kit),
                    value = state.medKits.firstOrNull { it.id == form.medKitId }?.name
                        ?: stringResource(R.string.pack_med_kit_unknown)
                )
                Stored(
                    label = stringResource(R.string.pack_amount),
                    value = state.stored?.let {
                        stringResource(R.string.pack_left, it.quantity.amount, it.quantity.unit.name)
                    } ?: stringResource(R.string.state_loading),
                    action = stringResource(R.string.pack_change_amount),
                    onAction = onChangeAmount
                )
            } else {
                PickerField(
                    label = stringResource(R.string.pack_med_kit),
                    selected = state.medKits.firstOrNull { it.id == form.medKitId },
                    options = state.medKits,
                    optionText = { it.name },
                    onPick = { onEdit(form.copy(medKitId = it.id)) },
                    isError = state.error?.field == PackageFormError.Field.MED_KIT,
                    emptyText = stringResource(R.string.pack_no_med_kits)
                )
            }
            OutlinedTextField(
                value = form.name,
                onValueChange = { onEdit(form.copy(name = it)) },
                label = { Text(stringResource(R.string.pack_name)) },
                isError = state.error?.field == PackageFormError.Field.NAME,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (!state.isEditing) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { onEdit(form.copy(amount = it)) },
                    label = { Text(stringResource(R.string.pack_amount)) },
                    isError = state.error?.field == PackageFormError.Field.AMOUNT,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                PickerField(
                    label = stringResource(R.string.pack_unit),
                    selected = state.units.firstOrNull { it.id == form.unit?.id },
                    options = state.units,
                    optionText = { it.name },
                    onPick = { onEdit(form.copy(unit = it)) },
                    isError = state.error?.field == PackageFormError.Field.UNIT,
                    modifier = Modifier.weight(1f)
                )
            }

            ExpandableSection(
                title = stringResource(R.string.pack_optional),
                expanded = optionalShown,
                onToggle = { optionalShown = !optionalShown }
            ) {
                Optional(state, onEdit)
            }

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
}

/** Всё, что человек может знать о коробке и может не знать: ни одно поле не обязательно. */
@Composable
private fun Optional(state: PackageFormUiState, onEdit: (PackageFormPresentationDTO) -> Unit) {
    val form = state.form
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PickerField(
            label = stringResource(R.string.pack_form),
            selected = state.forms.firstOrNull { it.id == form.form?.id },
            options = state.forms,
            optionText = { it.name },
            onPick = { onEdit(form.copy(form = it)) },
            isError = state.error?.field == PackageFormError.Field.FORM
        )
        FormField(
            value = form.expiresOn,
            label = R.string.pack_expires_on,
            placeholder = R.string.pack_expires_on_example,
            error = state.error?.field == PackageFormError.Field.EXPIRY,
            onValueChange = { onEdit(form.copy(expiresOn = it)) }
        )
        FormField(
            value = form.category,
            label = R.string.pack_category,
            error = state.error?.field == PackageFormError.Field.CATEGORY,
            onValueChange = { onEdit(form.copy(category = it)) }
        )
        FormField(
            value = form.manufacturer,
            label = R.string.pack_manufacturer,
            error = state.error?.field == PackageFormError.Field.MANUFACTURER,
            onValueChange = { onEdit(form.copy(manufacturer = it)) }
        )
        FormField(
            value = form.country,
            label = R.string.pack_country,
            error = state.error?.field == PackageFormError.Field.COUNTRY,
            onValueChange = { onEdit(form.copy(country = it)) }
        )
        FormField(
            value = form.description,
            label = R.string.pack_description,
            error = state.error?.field == PackageFormError.Field.DESCRIPTION,
            singleLine = false,
            onValueChange = { onEdit(form.copy(description = it)) }
        )
        FormField(
            value = form.defaultIntakeAmount,
            label = R.string.pack_intake_hint,
            supporting = R.string.pack_intake_hint_explained,
            error = state.error?.field == PackageFormError.Field.HINT,
            decimal = true,
            onValueChange = { onEdit(form.copy(defaultIntakeAmount = it)) }
        )
        FormField(
            value = form.note,
            label = R.string.pack_note,
            error = state.error?.field == PackageFormError.Field.NOTE,
            singleLine = false,
            onValueChange = { onEdit(form.copy(note = it)) }
        )
        FormField(
            value = form.price,
            label = R.string.pack_price,
            error = state.error?.field == PackageFormError.Field.PRICE,
            decimal = true,
            onValueChange = { onEdit(form.copy(price = it)) }
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

/**
 * Сведение, которое форма показывает, но не правит: его меняют другим действием и с другим
 * следом. Куда идти за этим действием, сказано тут же — иначе человек ищет его по экранам.
 */
@Composable
private fun Stored(
    label: String,
    value: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(action)
                }
            }
        }
    }
}

/** Обычное текстовое поле формы: десять таких подряд, и каждое повторяло бы одно и то же. */
@Composable
private fun FormField(
    value: String,
    @StringRes label: Int,
    error: Boolean,
    onValueChange: (String) -> Unit,
    @StringRes placeholder: Int? = null,
    @StringRes supporting: Int? = null,
    singleLine: Boolean = true,
    decimal: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(stringResource(it)) } },
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        isError = error,
        singleLine = singleLine,
        keyboardOptions = if (decimal) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else {
            KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Текст отказа — его собственное свойство: экран не подбирает слова сам. Слишком длинное поле
 * называет и себя, и свой предел, а предел держит тип сведений, а не форма.
 */
@Composable
private fun PackageFormError.message(): String = when (this) {
    PackageFormError.MedKitMissing -> stringResource(R.string.pack_med_kit_missing)
    PackageFormError.MedKitGone -> stringResource(R.string.pack_med_kit_gone)
    PackageFormError.MedKitBusy -> stringResource(R.string.pack_med_kit_busy)
    PackageFormError.PackageGone -> stringResource(R.string.pack_gone)
    PackageFormError.PackageBusy -> stringResource(R.string.pack_busy)
    PackageFormError.FormClearUnsupported -> stringResource(R.string.pack_form_clear_unsupported)
    PackageFormError.UnitMissing -> stringResource(R.string.pack_unit_missing)
    PackageFormError.NameEmpty -> stringResource(R.string.pack_name_empty)
    is PackageFormError.TooLong ->
        stringResource(R.string.pack_too_long, stringResource(field.label), limit)
    is PackageFormError.Amount -> stringResource(reason.text)
    PackageFormError.AmountIsZero -> stringResource(R.string.pack_amount_is_zero)
    is PackageFormError.Hint -> stringResource(reason.text)
    PackageFormError.HintIsZero -> stringResource(R.string.pack_hint_is_zero)
    is PackageFormError.Expiry -> stringResource(reason.text)
    is PackageFormError.Price -> stringResource(reason.text)
    is PackageFormError.UnknownInVocabulary -> stringResource(R.string.pack_unknown_in_vocabulary)
}

/** Предел берётся у типа сведений, а не повторяется числом в тексте. */
private val PackageFormError.TooLong.limit: Int
    get() = when (field) {
        PackageFormError.Field.NAME -> PackageSharedFacts.NAME_MAX_LENGTH
        PackageFormError.Field.CATEGORY -> PackageSharedFacts.CATEGORY_MAX_LENGTH
        PackageFormError.Field.MANUFACTURER -> PackageSharedFacts.MANUFACTURER_MAX_LENGTH
        PackageFormError.Field.COUNTRY -> PackageSharedFacts.COUNTRY_MAX_LENGTH
        PackageFormError.Field.DESCRIPTION -> PackageSharedFacts.DESCRIPTION_MAX_LENGTH
        else -> PackageFacts.NOTE_MAX_LENGTH
    }

@get:StringRes
private val PackageFormError.Field.label: Int
    get() = when (this) {
        PackageFormError.Field.MED_KIT -> R.string.pack_med_kit
        PackageFormError.Field.NAME -> R.string.pack_name
        PackageFormError.Field.AMOUNT -> R.string.pack_amount
        PackageFormError.Field.UNIT -> R.string.pack_unit
        PackageFormError.Field.FORM -> R.string.pack_form
        PackageFormError.Field.CATEGORY -> R.string.pack_category
        PackageFormError.Field.MANUFACTURER -> R.string.pack_manufacturer
        PackageFormError.Field.COUNTRY -> R.string.pack_country
        PackageFormError.Field.DESCRIPTION -> R.string.pack_description
        PackageFormError.Field.EXPIRY -> R.string.pack_expires_on
        PackageFormError.Field.HINT -> R.string.pack_intake_hint
        PackageFormError.Field.NOTE -> R.string.pack_note
        PackageFormError.Field.PRICE -> R.string.pack_price
    }

@get:StringRes
private val QuantityPresentationError.text: Int
    get() = when (this) {
        QuantityPresentationError.EMPTY -> R.string.quantity_empty
        QuantityPresentationError.TOO_LONG -> R.string.quantity_too_long
        QuantityPresentationError.NOT_A_DECIMAL -> R.string.quantity_not_a_decimal
        QuantityPresentationError.TOO_MANY_FRACTION_DIGITS -> R.string.quantity_too_many_fraction_digits
        QuantityPresentationError.TOO_MANY_INTEGER_DIGITS -> R.string.quantity_too_many_integer_digits
        QuantityPresentationError.OUT_OF_DOMAIN_RANGE -> R.string.quantity_out_of_range
        QuantityPresentationError.UNKNOWN_UNIT -> R.string.pack_unknown_in_vocabulary
    }

@get:StringRes
private val ExpiryDatePresentationError.text: Int
    get() = when (this) {
        ExpiryDatePresentationError.EMPTY -> R.string.expiry_empty
        ExpiryDatePresentationError.UNKNOWN_FORMAT -> R.string.expiry_unknown_format
        ExpiryDatePresentationError.IMPOSSIBLE_DATE -> R.string.expiry_impossible_date
    }

@get:StringRes
private val MoneyPresentationError.text: Int
    get() = when (this) {
        MoneyPresentationError.EMPTY -> R.string.price_empty
        MoneyPresentationError.TOO_LONG -> R.string.price_too_long
        MoneyPresentationError.NOT_A_DECIMAL -> R.string.price_not_a_decimal
        MoneyPresentationError.UNKNOWN_CURRENCY -> R.string.price_unknown_currency
        MoneyPresentationError.OUT_OF_CURRENCY_RANGE -> R.string.price_out_of_range
    }
