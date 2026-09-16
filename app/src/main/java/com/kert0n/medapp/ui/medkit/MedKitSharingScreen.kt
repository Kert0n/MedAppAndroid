package com.kert0n.medapp.ui.medkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.medkit.InvitationPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitSharingRefusal
import com.kert0n.medapp.presentation.medkit.MedKitSharingUiState
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.TIME
import com.kert0n.medapp.ui.text

/**
 * Поделиться аптечкой (PLAN H3 №20). Экран один, лиц у него два, и выбирает между ними сама
 * полка: местную делают общей, в общую зовут.
 *
 * **Первым — цена решения, а не кнопка.** Что станет общим и что останется у человека, он читает
 * до того, как решит: доступ необратим и не отзывается (PLAN E5), и узнавать об этом после
 * нажатия поздно.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedKitSharingScreen(
    state: MedKitSharingUiState,
    onAsk: () -> Unit,
    onDismissAsking: () -> Unit,
    onPublish: () -> Unit,
    onInvite: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.med_kit_sharing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        val inside = Modifier.padding(padding)
        when (state) {
            MedKitSharingUiState.Loading -> LoadingState(inside)
            MedKitSharingUiState.Gone -> EmptyState(stringResource(R.string.med_kit_gone), inside)
            is MedKitSharingUiState.OnItsWay -> EmptyState(stringResource(R.string.med_kit_sharing_on_its_way), inside)
            is MedKitSharingUiState.Deciding -> Deciding(state, onAsk, onDismissAsking, onPublish, inside)
            is MedKitSharingUiState.Shared -> Shared(state, onAsk, onDismissAsking, onInvite, inside)
        }
    }
}

/** Местная полка: сперва цена решения, потом само решение. */
@Composable
private fun Deciding(
    state: MedKitSharingUiState.Deciding,
    onAsk: () -> Unit,
    onDismissAsking: () -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        What(R.string.med_kit_sharing_becomes_shared, R.string.med_kit_sharing_becomes_shared_list)
        What(R.string.med_kit_sharing_stays_yours, R.string.med_kit_sharing_stays_yours_list)
        Warning(stringResource(R.string.med_kit_sharing_irreversible))
        state.refusal?.let { Refusal(it) }
        Button(
            onClick = onAsk,
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        ) {
            Text(stringResource(R.string.med_kit_sharing_publish))
        }
        if (state.isWorking) Waiting()
    }
    if (state.isAsking) {
        Confirmation(
            title = stringResource(R.string.med_kit_sharing_publish_title, state.name),
            text = stringResource(R.string.med_kit_sharing_irreversible),
            action = stringResource(R.string.med_kit_sharing_publish),
            onConfirm = onPublish,
            onDismiss = onDismissAsking
        )
    }
}

/** Общая полка: ключ, если его уже выдали, и кнопка позвать. */
@Composable
private fun Shared(
    state: MedKitSharingUiState.Shared,
    onAsk: () -> Unit,
    onDismissAsking: () -> Unit,
    onInvite: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.med_kit_sharing_shared, state.name),
            style = MaterialTheme.typography.bodyLarge
        )
        state.invitation?.let { Code(it) }
        state.refusal?.let { Refusal(it) }
        Button(
            onClick = onAsk,
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        ) {
            Text(
                stringResource(
                    if (state.invitation == null) R.string.med_kit_sharing_invite
                    else R.string.med_kit_sharing_invite_again
                )
            )
        }
        if (state.isWorking) Waiting()
    }
    if (state.isAsking) {
        Confirmation(
            title = stringResource(R.string.med_kit_sharing_invite_title, state.name),
            text = stringResource(R.string.med_kit_sharing_invite_explained),
            action = stringResource(R.string.med_kit_sharing_invite),
            onConfirm = onInvite,
            onDismiss = onDismissAsking
        )
    }
}

/**
 * Выданный ключ. Срок — **оценка**, а не обещание: сервер его не называет, и ключ может уйти из
 * его кэша раньше (PLAN B6, C1 «Приглашение»).
 */
@Composable
private fun Code(invitation: InvitationPresentationDTO) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                invitation.key.value,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.med_kit_sharing_expires_around, TIME.format(invitation.expiresAround)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Заголовок и список под ним: что уезжает и что остаётся. */
@Composable
private fun What(title: Int, list: Int) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(list), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Предупреждение: значок и слова, цвет — уточнение, а не сообщение (PLAN H3). */
@Composable
private fun Warning(text: String, @DrawableRes icon: Int = R.drawable.ic_warning) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** Почему не вышло. Слова — свойство причины, экран их не выбирает. */
@Composable
private fun Refusal(refusal: MedKitSharingRefusal) = when (refusal) {
    MedKitSharingRefusal.Busy -> Warning(stringResource(R.string.med_kit_sharing_busy))
    MedKitSharingRefusal.NotShared -> Warning(stringResource(R.string.med_kit_sharing_not_shared))
    is MedKitSharingRefusal.Unavailable -> Warning(stringResource(refusal.reason.text))
}

@Composable
private fun Waiting() = Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center
) {
    CircularProgressIndicator()
}

/** Спрашивается до сценария, а не после него (PLAN H3 «Подтверждения опасных действий»). */
@Composable
private fun Confirmation(
    title: String,
    text: String,
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
)
