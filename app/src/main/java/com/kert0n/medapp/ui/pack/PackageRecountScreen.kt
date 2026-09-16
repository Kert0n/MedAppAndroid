package com.kert0n.medapp.ui.pack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.pack.PackageRecountError
import com.kert0n.medapp.presentation.pack.PackageRecountPresentationDTO
import com.kert0n.medapp.presentation.pack.PackageRecountUiState
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.Form
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.text

/**
 * Пересчёт (PLAN H3 №9): одно число и один вопрос. Человек называет то, что видит **целиком**, а
 * вычитание делает учёт — так и написано под полем, а не подразумевается. Причины и заметки
 * здесь нет: истории у коробки нет (D7). Ноль не принимается — для него есть «выбросить».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageRecountScreen(
    state: PackageRecountUiState,
    onEdit: (PackageRecountPresentationDTO) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pack_recount)) },
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
            state.isGone -> EmptyState(text = stringResource(R.string.pack_gone), modifier = Modifier.padding(padding))
            pack == null -> LoadingState(Modifier.padding(padding))
            else -> Form(
                modifier = Modifier.padding(padding),
                actions = {
                    state.error?.let {
                        Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    // Кнопка не гаснет: погашенная не объясняет, чего не хватает (H3 №3).
                    Button(
                        onClick = onSubmit,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    ) { Text(stringResource(R.string.recount_record)) }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            ) {
                Text(
                    stringResource(R.string.recount_now, pack.effective.amount, pack.effective.unit.name),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = state.form.amount,
                    onValueChange = { onEdit(state.form.copy(amount = it)) },
                    label = { Text(stringResource(R.string.recount_seen)) },
                    supportingText = { Text(stringResource(R.string.recount_explained)) },
                    isError = state.error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Текст причины — её свойство: экран не подбирает слова сам. */
@Composable
private fun PackageRecountError.message(): String = when (this) {
    is PackageRecountError.Amount -> stringResource(reason.text)
    PackageRecountError.Zero -> stringResource(R.string.recount_zero)
    PackageRecountError.Gone -> stringResource(R.string.pack_gone)
    PackageRecountError.Busy -> stringResource(R.string.recount_busy)
}
