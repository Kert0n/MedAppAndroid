package com.kert0n.medapp.ui.bootstrap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.bootstrap.AppStartState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState

/**
 * Экран первичной настройки (PLAN H3 №1): пока приложение не настроено, человек видит его, а не
 * пустой список. [AppStartState.Ready] сюда не приходит — в этом состоянии показывают приложение.
 */
@Composable
fun SetupScreen(
    state: AppStartState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        AppStartState.Checking -> LoadingState(modifier)
        is AppStartState.Setup -> ErrorMessage(state.reason, modifier, onRetry)
        // Повтора нет: ключ не откроется оттого, что нажали ещё раз. Что делать дальше, решает
        // человек, и приложение сначала говорит ему, что цело, а что нет (PLAN G2).
        AppStartState.KeyLost -> ErrorMessage(stringResource(R.string.setup_key_lost), modifier)
        AppStartState.Ready -> Unit
    }
}
