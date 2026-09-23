package com.kert0n.medapp.ui.plan

import com.kert0n.medapp.ui.intake.IntakeQuestionsDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.presentation.plan.DayItemPresentationDTO
import com.kert0n.medapp.presentation.plan.MissedIntakesUiState
import kotlin.uuid.Uuid

/**
 * Попап пропущенного (PLAN C1): неотвеченные пункты прошлых дней при входе. Строки — те же
 * карточки, что на «Дне», с днём у времени; у каждой «Принял». Нажатие на строку ведёт на
 * карточку пункта — там можно выбрать другую коробку или время. Крестик — неотвеченное остаётся
 * пропусками; мимо окна не закрывают, как и попап срока.
 */
@Composable
fun MissedIntakesPopup(
    state: MissedIntakesUiState,
    onOpen: (DayItemPresentationDTO) -> Unit,
    onConfirm: (Uuid, MissedIntakesUiState.Planned) -> Unit,
    onDismiss: (Set<NotificationKey>) -> Unit,
    onDismissMessage: () -> Unit,
    onAcknowledge: () -> Unit,
    onDismissQuestion: () -> Unit
) {
    if (state.isEmpty) return
    // Строк нет, а ответ на последнюю не прочитан — окно уходит, а слова остаются.
    if (state.rows.isNotEmpty()) AlertDialog(
        onDismissRequest = {},
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.missed_title))
                    Text(
                        stringResource(R.string.missed_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Крестик и «Принял» уносят с собой **нарисованное**: ключи этого попапа и плановое
                // этой строки. Сценарий, читающий состояние заново, отвечал бы уже за другой экран
                // (C1 «Действие — по показанному»).
                IconButton(onClick = { onDismiss(state.told) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.missed_close))
                }
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.rows, key = { it.key }) { row ->
                    DayCard(row, onOpen, onConfirm = { id -> state.planned[id]?.let { onConfirm(id, it) } }, onDecline = {})
                }
            }
        },
        confirmButton = {}
    )
    state.message?.let {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            text = { Text(it.words()) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_got_it)) } }
        )
    }
    state.question?.let {
        IntakeQuestionsDialog(it.questions, onAcknowledge = onAcknowledge, onDismiss = onDismissQuestion)
    }
}
