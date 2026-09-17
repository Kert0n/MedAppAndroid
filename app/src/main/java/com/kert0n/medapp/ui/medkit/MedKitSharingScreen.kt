package com.kert0n.medapp.ui.medkit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.medkit.InvitationPresentationDTO
import com.kert0n.medapp.presentation.medkit.MedKitSharingRefusal
import com.kert0n.medapp.presentation.medkit.MedKitSharingUiState
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.ConsequenceCard
import com.kert0n.medapp.ui.NavigationRow
import com.kert0n.medapp.ui.SecretOnScreen
import com.kert0n.medapp.ui.TIME
import com.kert0n.medapp.ui.copySecret
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
    onShowFullScreen: () -> Unit,
    onHideFullScreen: () -> Unit,
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
            is MedKitSharingUiState.Shared ->
                Shared(state, onAsk, onDismissAsking, onInvite, onShowFullScreen, onHideFullScreen, inside)
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
        ConsequenceCard(
            icon = R.drawable.ic_public,
            title = R.string.med_kit_sharing_becomes_shared,
            items = R.array.med_kit_sharing_becomes_shared_items,
            tint = MaterialTheme.colorScheme.tertiary
        )
        ConsequenceCard(
            icon = R.drawable.ic_lock,
            title = R.string.med_kit_sharing_stays_yours,
            items = R.array.med_kit_sharing_stays_yours_items,
            tint = MaterialTheme.colorScheme.primary
        )
        Warning(stringResource(R.string.med_kit_sharing_irreversible))
        state.refusal?.let { Refusal(it) }
        Button(
            onClick = onAsk,
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        ) {
            Icon(painterResource(R.drawable.ic_public), contentDescription = null)
            Spacer(Modifier.size(8.dp))
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
    onShowFullScreen: () -> Unit,
    onHideFullScreen: () -> Unit,
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
        state.invitation?.let { Code(it, onShowFullScreen, onInvite = onAsk) }
        state.refusal?.let { Refusal(it) }
        if (state.invitation == null) {
            Button(
                onClick = onAsk,
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) {
                Icon(painterResource(R.drawable.ic_key), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.med_kit_sharing_invite))
            }
        }
        if (state.isWorking) Waiting()
    }
    if (state.isFullScreen && state.invitation != null) {
        FullScreenCode(state.invitation, onInvite = onAsk, onHide = onHideFullScreen)
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
private fun Code(invitation: InvitationPresentationDTO, onShowFullScreen: () -> Unit, onInvite: () -> Unit) {
    // Ключ на экране — секрет: пока он виден, снимок экрана и показ в недавних запрещены (G3).
    SecretOnScreen()
    // Код ложится в буфер сам, как только выдан (ТЗ 4.1.1.2, C1 «Текстовый код»): человек его
    // пересылает, а не переписывает. Ключ — повод: новый код копируется, а поворот экрана нет.
    val context = LocalContext.current
    val label = stringResource(R.string.med_kit_sharing_clipboard_label)
    LaunchedEffect(invitation.key) { context.copySecret(label, invitation.key.value) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Pattern(
                invitation.key.value,
                Modifier.size(180.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onShowFullScreen)
            )
            // Сам код — в рамке и крупно: его читают вслух и сверяют по одному знаку.
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        invitation.key.value,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // Значок копирования — настоящая кнопка: нарисованный просто так, он обещает
                    // нажатие и не отвечает на него. Код уже в буфере, но человек, потерявший его
                    // там, кладёт заново отсюда.
                    IconButton(onClick = { context.copySecret(label, invitation.key.value) }) {
                        Icon(
                            painterResource(R.drawable.ic_content_copy),
                            contentDescription = stringResource(R.string.med_kit_sharing_copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Marker(
                icon = R.drawable.ic_check_circle,
                text = stringResource(R.string.med_kit_sharing_copied),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Marker(
                icon = R.drawable.ic_schedule,
                text = stringResource(R.string.med_kit_sharing_expires_around, TIME.format(invitation.expiresAround)),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Два действия **столбцом, а не рядом**: рядом они делят ширину, и на крупном шрифте
            // подписи обрезаются (замечание владельца 2026-09-17, третий случай того же класса).
            // Показ уводит на весь экран — это строка со стрелкой; обновление случается здесь —
            // это кнопка.
            NavigationRow(
                icon = R.drawable.ic_fullscreen,
                text = stringResource(R.string.med_kit_sharing_show_full_screen),
                onClick = onShowFullScreen,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onInvite,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.med_kit_sharing_invite_again))
            }
        }
    }
}

/** Значок и слова рядом: цвет уточняет сказанное, а не заменяет его (PLAN H3). */
@Composable
private fun Marker(@DrawableRes icon: Int, text: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** Предупреждение — тот же значок и слова, только цветом беды (PLAN H3). */
@Composable
private fun Warning(text: String) = Marker(R.drawable.ic_warning, text, MaterialTheme.colorScheme.error)

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

/**
 * Узор ключа. Подложка белая при любой теме и не зависит от палитры: узор читает камера чужого
 * телефона, а тёмный распознаватели теряют. Сглаживание выключено — размытая граница модуля
 * читается хуже резкой.
 */
@Composable
private fun Pattern(code: String, modifier: Modifier = Modifier) {
    Image(
        bitmap = rememberInvitationPattern(code),
        contentDescription = stringResource(R.string.med_kit_sharing_qr),
        filterQuality = FilterQuality.None,
        modifier = modifier
            .background(Color.White)
            .padding(8.dp)
    )
}

/**
 * Экран 21 — код во весь экран. Это **состояние экрана 20**, а не маршрут: ключ секрет, а ключ
 * маршрута ложится в сохранённую стопку (PLAN G3, C1 «Ключ приглашения не бывает маршрутом»).
 * Оттого он и не переживает смерть процесса — человек видит «обновить код», и это честно.
 */
@Composable
private fun FullScreenCode(
    invitation: InvitationPresentationDTO,
    onInvite: () -> Unit,
    onHide: () -> Unit
) = Dialog(onDismissRequest = onHide, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    SecretOnScreen()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Pattern(invitation.key.value, Modifier.fillMaxWidth(0.85f).aspectRatio(1f))
            Text(invitation.key.value, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.med_kit_sharing_expires_around, TIME.format(invitation.expiresAround)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onInvite) { Text(stringResource(R.string.med_kit_sharing_invite_again)) }
            TextButton(onClick = onHide) { Text(stringResource(R.string.action_close)) }
        }
    }
}
