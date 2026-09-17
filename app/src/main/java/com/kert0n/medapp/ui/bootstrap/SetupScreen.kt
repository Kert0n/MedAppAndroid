package com.kert0n.medapp.ui.bootstrap

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.bootstrap.AppStartState
import com.kert0n.medapp.ui.ConsequenceCard
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState

/**
 * Экран первичной настройки (PLAN H3 №1): пока приложение не настроено, человек видит его, а не
 * пустой список. [AppStartState.Ready] сюда не приходит — в этом состоянии показывают
 * приложение.
 */
@Composable
fun SetupScreen(
    state: AppStartState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onStartOver: () -> Unit = {}
) {
    when (state) {
        AppStartState.Checking -> LoadingState(modifier)
        is AppStartState.Setup -> ErrorMessage(state.reason, modifier, onRetry)
        AppStartState.KeyLost -> KeyLost(onStartOver, modifier)
        AppStartState.Ready -> Unit
    }
}

/**
 * Ключ утрачен: повтора нет — ключ не откроется оттого, что нажали ещё раз. Первым — что
 * случилось, потом что останется и что пропадёт (PLAN G2), и только потом кнопка; она спрашивает
 * подтверждение, и только оно зовёт сценарий: вторая учётка поверх местных данных — решение
 * человека, а не приложения.
 */
@Composable
private fun KeyLost(onStartOver: () -> Unit, modifier: Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.setup_key_lost), style = MaterialTheme.typography.bodyLarge)
        ConsequenceCard(
            icon = R.drawable.ic_lock,
            title = R.string.setup_key_lost_stays,
            items = R.array.setup_key_lost_stays_items,
            tint = MaterialTheme.colorScheme.primary
        )
        ConsequenceCard(
            icon = R.drawable.ic_warning,
            title = R.string.setup_key_lost_goes,
            items = R.array.setup_key_lost_goes_items,
            tint = MaterialTheme.colorScheme.error
        )
        Button(
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        ) { Text(stringResource(R.string.setup_start_over)) }
    }
    if (!confirming) return
    AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.setup_start_over_question)) },
        text = { Text(stringResource(R.string.setup_start_over_warning)) },
        confirmButton = {
            TextButton(
                onClick = {
                    confirming = false
                    onStartOver()
                }
            ) { Text(stringResource(R.string.setup_start_over)) }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}
