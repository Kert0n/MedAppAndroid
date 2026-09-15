package com.kert0n.medapp.ui.pack

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.pack.AmountChange
import com.kert0n.medapp.presentation.pack.PackageAmountError
import com.kert0n.medapp.presentation.pack.PackageAmountPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageAmountUiState
import com.kert0n.medapp.presentation.pack.PackageAmountViewModel
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState

/** Где экран берёт состояние и куда уходит, записав. */
@Composable
fun PackageAmountRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PackageAmountViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isDone) { if (state.isDone) onDone() }
    PackageAmountScreen(
        state = state,
        onEdit = viewModel::edit,
        onSubmit = viewModel::submit,
        onCancel = onDone,
        onConfirmEmptying = viewModel::confirmEmptying,
        onDismissEmptying = viewModel::dismissEmptying,
        modifier = modifier
    )
}

/**
 * Пересчёт и утилизация (PLAN H3 №9). Две вкладки, а не два экрана: человек приходит сюда с
 * одним наблюдением — «в коробке не столько, сколько записано», — а объясняет его по-разному.
 *
 * Пересчёт называет **новое количество целиком**, а не разницу: человек считает то, что видит, а
 * вычитание за него делает учёт (PLAN E1). Причины и заметки здесь нет: отчёт об утилизации —
 * только число (PLAN C1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageAmountScreen(
    state: PackageAmountUiState,
    onEdit: (PackageAmountPresentationDTO) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onConfirmEmptying: () -> Unit,
    onDismissEmptying: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.amount_title)) },
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
        val pack = state.pack
        when {
            state.isGone -> EmptyState(
                text = stringResource(R.string.pack_gone),
                modifier = Modifier.padding(padding)
            )
            pack == null -> LoadingState(Modifier.padding(padding))
            else -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PrimaryTabRow(selectedTabIndex = state.form.change.ordinal) {
                    for (change in AmountChange.entries) {
                        Tab(
                            selected = state.form.change == change,
                            onClick = { onEdit(state.form.copy(change = change)) },
                            text = { Text(stringResource(change.title)) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        )
                    }
                }
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.amount_now,
                            pack.effective.amount,
                            pack.effective.unit.name
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedTextField(
                        value = state.form.amount,
                        onValueChange = { onEdit(state.form.copy(amount = it)) },
                        label = { Text(stringResource(state.form.change.label)) },
                        supportingText = { Text(stringResource(state.form.change.explained)) },
                        isError = state.error != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                        onClick = onSubmit,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    ) { Text(stringResource(R.string.amount_record)) }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
    if (state.asksToEmpty) {
        AlertDialog(
            onDismissRequest = onDismissEmptying,
            title = { Text(stringResource(R.string.amount_zero_title)) },
            text = { Text(stringResource(R.string.amount_zero_explained)) },
            confirmButton = {
                TextButton(onClick = onConfirmEmptying) {
                    Text(stringResource(R.string.amount_zero_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissEmptying) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@get:StringRes
private val AmountChange.title: Int
    get() = when (this) {
        AmountChange.RECOUNT -> R.string.amount_tab_recount
        AmountChange.DISPOSAL -> R.string.amount_tab_disposal
    }

@get:StringRes
private val AmountChange.label: Int
    get() = when (this) {
        AmountChange.RECOUNT -> R.string.amount_recounted
        AmountChange.DISPOSAL -> R.string.amount_disposed
    }

/** Чем пересчёт отличается от утилизации, сказано словами: числа у них выглядят одинаково. */
@get:StringRes
private val AmountChange.explained: Int
    get() = when (this) {
        AmountChange.RECOUNT -> R.string.amount_recount_explained
        AmountChange.DISPOSAL -> R.string.amount_disposal_explained
    }

/** Текст причины — её свойство: экран не подбирает слова сам. */
@Composable
private fun PackageAmountError.message(): String = when (this) {
    is PackageAmountError.Amount -> stringResource(reason.text)
    PackageAmountError.NothingToDispose -> stringResource(R.string.amount_nothing_to_dispose)
    PackageAmountError.MoreThanThereIs -> stringResource(R.string.amount_more_than_there_is)
    PackageAmountError.Gone -> stringResource(R.string.pack_gone)
    PackageAmountError.Busy -> stringResource(R.string.pack_busy)
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
