package com.kert0n.medapp.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kert0n.medapp.domain.notification.NotificationOpening
import com.kert0n.medapp.app.navigation.MedAppShell
import com.kert0n.medapp.platform.notifications.NotificationTargetExtras
import com.kert0n.medapp.presentation.bootstrap.AppStartState
import com.kert0n.medapp.presentation.bootstrap.AppStartViewModel
import com.kert0n.medapp.ui.bootstrap.SetupScreen

/**
 * Корень приложения: пока оно не настроено, виден экран настройки и ничего больше — до успешной
 * регистрации пустой список притворялся бы работающим приложением (PLAN C3).
 *
 * Оболочка стоит за этим решением, а не внутри него: пять мест не знают, как устройство получило
 * учётную запись, и проверяются без неё.
 */
@Composable
fun MedAppApp(
    opening: NotificationOpening? = null,
    onOpened: () -> Unit = {},
    viewModel: AppStartViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (state) {
        AppStartState.Ready -> MedAppShell(opening = opening, onOpened = onOpened)
        else -> SetupScreen(state, onRetry = viewModel::retry)
    }
}
