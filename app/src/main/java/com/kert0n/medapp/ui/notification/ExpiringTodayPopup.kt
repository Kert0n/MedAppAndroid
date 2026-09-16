package com.kert0n.medapp.ui.notification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.presentation.notification.ExpiringTodayUiState
import com.kert0n.medapp.presentation.pack.PackagePresentationDTO
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.words
import kotlin.uuid.Uuid

/**
 * «Сегодня истекает срок годности» — попап при входе (PLAN D8, H3 «Уведомления на экране»).
 *
 * Новость о вещи, а не о приёме: строкой в списке она теряется, и человек узнаёт о ней, когда
 * коробка уже просрочена. Закрывается **только крестиком** — нажатие на карточку ведёт на карточку
 * коробки, и возврат оттуда попап не обрывает: это тот же разговор (решение владельца 2026-09-16).
 *
 * Окно модальное, поэтому **где** его показывать, решает оболочка: только на местах. Над карточкой
 * коробки оно не давало бы с ней ничего сделать (PLAN C1 «Попап вне мест»).
 *
 * Кнопки «понятно» у него нет: крестик и есть ответ. Подтверждать новость нечем.
 */
@Composable
fun ExpiringTodayPopup(
    state: ExpiringTodayUiState,
    onOpenPackage: (Uuid) -> Unit,
    onDismiss: (Set<NotificationKey>) -> Unit
) {
    if (state.isEmpty) return
    AlertDialog(
        // Мимо попапа не закрывают: новость говорится один раз за день, и терять её случайным
        // нажатием мимо диалога нельзя.
        onDismissRequest = {},
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.expiry_today_title), modifier = Modifier.weight(1f))
                // Крестик уносит с собой ключи **этого** попапа: сказанным становится ровно то,
                // что человек прочёл (C1 «Действие — по показанному»).
                IconButton(onClick = { onDismiss(state.told) }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.expiry_today_close)
                    )
                }
            }
        },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.boxes, key = { it.id }) { box -> ExpiringCard(box, onOpen = { onOpenPackage(box.id) }) }
            }
        },
        // Действие у попапа одно — уйти к коробке, и оно на самих карточках.
        confirmButton = {}
    )
}

/** Коробка карточкой — той же, что на выборе источника: один вид на все списки коробок. */
@Composable
private fun ExpiringCard(box: PackagePresentationDTO, onOpen: () -> Unit) {
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_expiring_soon), contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(box.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        box.quantity.words(),
                        box.expiresOn?.let { stringResource(R.string.expiry_today_until, DAY.format(it.lastDay)) }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
