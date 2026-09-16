package com.kert0n.medapp.ui.operation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.operation.SyncStatusUiState
import com.kert0n.medapp.presentation.operation.OutstandingOperationPresentationDTO
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.TIME
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Состояние синхронизации (PLAN H3 №28). Первым — идёт ли заход и когда лёг снимок: человек
 * пришёл сюда спросить «доехало ли», и ответ на это стоит выше списка.
 *
 * Строки трёх видов, и они различимы: ждущая говорит только, когда придём снова; отвергнутая
 * называет причину и даёт два действия; нечитаемую остаётся разобрать. Связи нет — это «связи
 * нет», а не ошибка: очередь цела, ей просто некуда ехать.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    state: SyncStatusUiState,
    onRefresh: () -> Unit,
    onRecount: (Uuid) -> Unit,
    onDismiss: (Uuid) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_status)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isRunning) {
                        Icon(
                            painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.sync_refresh)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.isRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
            Headline(state, zone)
            when {
                !state.isLoaded -> LoadingState()
                state.isEmpty -> EmptyState(stringResource(R.string.sync_all_delivered))
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        Trouble(row, onRecount = { onRecount(it) }, onDismiss = { onDismiss(row.id) }, zone = zone)
                    }
                }
            }
        }
    }
}

/** Ответ на главный вопрос — до списка: доехало ли, и когда мы в последний раз это знали. */
@Composable
private fun Headline(state: SyncStatusUiState, zone: ZoneId) {
    val text = when {
        state.isRunning -> stringResource(R.string.sync_running)
        state.isOffline -> stringResource(R.string.sync_offline)
        state.refreshedAt != null ->
            stringResource(R.string.sync_refreshed_at, TIME.format(state.refreshedAt.atZone(zone).toLocalTime()))
        else -> stringResource(R.string.sync_never)
    }
    val icon = when {
        state.isOffline -> R.drawable.ic_sync_problem
        state.isRunning -> R.drawable.ic_sync
        else -> R.drawable.ic_cloud_done
    }
    val tint =
        if (state.isOffline) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/**
 * Строка очереди. Называется **вещью**, а не командой: «расход из „Нурофена“», а не `Consume`.
 * Нечитаемой имени взять негде — оно лежит внутри неё.
 */
@Composable
private fun Trouble(
    row: OutstandingOperationPresentationDTO,
    onRecount: (Uuid) -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId
) {
    val bad = row.trouble != OutstandingOperationPresentationDTO.Trouble.WAITING
    val tint = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (bad) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(row.title(), style = MaterialTheme.typography.titleMedium, color = tint)
            }
            Text(row.explained(zone), style = MaterialTheme.typography.bodyMedium)
            // Действия столбцом: два слова в ряд делят ширину и обрезаются (PLAN H3 «Дизайн»).
            row.recountable?.let { pack ->
                TextButton(onClick = { onRecount(pack) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_calculate), contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sync_recount), modifier = Modifier.weight(1f))
                }
            }
            if (bad) {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sync_dismiss), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** О чём шла речь — словами человека и с именем вещи, если оно известно. */
@Composable
private fun OutstandingOperationPresentationDTO.title(): String {
    val what = stringResource(about.text)
    return subject?.let { stringResource(R.string.sync_about_named, what, it) } ?: what
}

/** Что с ней не так: причина отказа, срок повтора или «нечем прочитать». */
@Composable
private fun OutstandingOperationPresentationDTO.explained(zone: ZoneId): String = when (trouble) {
    OutstandingOperationPresentationDTO.Trouble.WAITING -> retryAt
        ?.let { stringResource(R.string.sync_retry_at, TIME.format(it.atZone(zone).toLocalTime())) }
        ?: stringResource(R.string.sync_waiting)
    OutstandingOperationPresentationDTO.Trouble.REFUSED -> stringResource(reason?.text ?: R.string.sync_refused)
    OutstandingOperationPresentationDTO.Trouble.UNREADABLE -> stringResource(R.string.sync_unreadable)
}

@get:StringRes
private val OutstandingOperationPresentationDTO.About.text: Int
    get() = when (this) {
        OutstandingOperationPresentationDTO.About.PACKAGE_CREATED -> R.string.sync_about_package_created
        OutstandingOperationPresentationDTO.About.PACKAGE_CHANGED -> R.string.sync_about_package_changed
        OutstandingOperationPresentationDTO.About.PACKAGE_MOVED -> R.string.sync_about_package_moved
        OutstandingOperationPresentationDTO.About.PACKAGE_REMOVED -> R.string.sync_about_package_removed
        OutstandingOperationPresentationDTO.About.INTAKE -> R.string.sync_about_intake
        OutstandingOperationPresentationDTO.About.CLAIM -> R.string.sync_about_claim
        OutstandingOperationPresentationDTO.About.MED_KIT -> R.string.sync_about_med_kit
        OutstandingOperationPresentationDTO.About.UNKNOWN -> R.string.sync_about_unknown
    }

@get:StringRes
private val OutstandingOperationPresentationDTO.Reason.text: Int
    get() = when (this) {
        OutstandingOperationPresentationDTO.Reason.INVALID -> R.string.sync_reason_invalid
        OutstandingOperationPresentationDTO.Reason.NOT_ENOUGH -> R.string.sync_reason_not_enough
        OutstandingOperationPresentationDTO.Reason.UNIT_CHANGED -> R.string.sync_reason_unit_changed
        OutstandingOperationPresentationDTO.Reason.STALE -> R.string.sync_reason_stale
        OutstandingOperationPresentationDTO.Reason.CONFLICT -> R.string.sync_reason_conflict
        OutstandingOperationPresentationDTO.Reason.SUPERSEDED -> R.string.sync_reason_superseded
        OutstandingOperationPresentationDTO.Reason.UNREADABLE -> R.string.sync_unreadable
    }

