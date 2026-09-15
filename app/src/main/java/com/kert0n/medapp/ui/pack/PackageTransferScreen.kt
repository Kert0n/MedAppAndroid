package com.kert0n.medapp.ui.pack

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.pack.PackageTransferUiState
import com.kert0n.medapp.presentation.pack.PackageTransferViewModel
import com.kert0n.medapp.presentation.pack.TransferRefusal
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import kotlin.uuid.Uuid

/** Где экран берёт состояние и куда уходит, перенеся. */
@Composable
fun PackageTransferRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PackageTransferViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isDone) { if (state.isDone) onDone() }
    PackageTransferScreen(
        state = state,
        onChoose = viewModel::choose,
        onTransfer = viewModel::transfer,
        onBack = onDone,
        modifier = modifier
    )
}

/**
 * Перенос упаковки (PLAN H3 №11). Первым — список, куда можно положить; нынешняя полка в нём не
 * предлагается.
 *
 * Переносить некуда — это не пустой список, а рассказ: человеку говорят, что второй аптечки у
 * него пока нет, а не показывают пустоту, в которой он ищет, что нажать.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageTransferScreen(
    state: PackageTransferUiState,
    onChoose: (Uuid) -> Unit,
    onTransfer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_title)) },
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
        when {
            state.isGone -> EmptyState(
                text = stringResource(R.string.pack_gone),
                modifier = Modifier.padding(padding)
            )
            state.isLoading -> LoadingState(Modifier.padding(padding))
            state.places.isEmpty() -> EmptyState(
                text = stringResource(R.string.transfer_nowhere),
                modifier = Modifier.padding(padding)
            )
            else -> Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(state.places, key = { it.id }) { place ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .selectable(
                                    selected = state.chosen == place.id,
                                    onClick = { onChoose(place.id) }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = state.chosen == place.id, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(place.name, style = MaterialTheme.typography.bodyLarge)
                                val note = place.location
                                    ?: if (place.isShared) stringResource(R.string.transfer_shared) else null
                                note?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                state.refusal?.let {
                    Text(
                        stringResource(it.text),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                Button(
                    onClick = onTransfer,
                    // Погашена, пока место не выбрано: тут гасить честно — нажимать некуда не
                    // потому, что форма неверна, а потому, что человек ещё ничего не назвал.
                    enabled = state.chosen != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .defaultMinSize(minHeight = 48.dp)
                ) { Text(stringResource(R.string.transfer_action)) }
            }
        }
    }
}

/** Текст причины — её свойство: экран не подбирает слова сам. */
@get:StringRes
private val TransferRefusal.text: Int
    get() = when (this) {
        TransferRefusal.PACKAGE_GONE -> R.string.pack_gone
        TransferRefusal.PACKAGE_BUSY -> R.string.pack_busy
        TransferRefusal.ORIGIN_BUSY -> R.string.transfer_origin_busy
        TransferRefusal.TARGET_GONE -> R.string.transfer_target_gone
        TransferRefusal.TARGET_BUSY -> R.string.transfer_target_busy
    }
