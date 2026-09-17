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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.pack.PackageTransferRefusal
import com.kert0n.medapp.presentation.pack.PackageTransferUiState
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.theme.LocalAccents
import kotlin.uuid.Uuid

/**
 * Перенос упаковки (PLAN H3 №11). Первым — список, куда можно положить; нынешняя полка в нём не
 * предлагается, общая подписана.
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
            !state.isLoaded -> LoadingState(Modifier.padding(padding))
            state.isGone -> EmptyState(text = stringResource(R.string.pack_gone), modifier = Modifier.padding(padding))
            state.places.isEmpty() -> EmptyState(text = stringResource(R.string.transfer_nowhere), modifier = Modifier.padding(padding))
            else -> Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Перенос может лишить другого участника доступа: сервер сохранит его бронь,
                // только если он видит целевую полку (PLAN E6). Кто её видит, знает он, а не мы,
                // поэтому предупреждение общее — и стоит **до** выбора, а не после переноса.
                if (state.hasClaimsOfOthers) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = LocalAccents.current.pending,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            stringResource(R.string.transfer_claims_of_others),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalAccents.current.pending
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(state.places, key = { it.id }) { place ->
                        val chosen = state.chosen == place.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .selectable(selected = chosen, onClick = { onChoose(place.id) })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = chosen, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(place.name, style = MaterialTheme.typography.bodyLarge)
                                // Место хранения и «общая» — разные сведения, и одно другое не заслоняет:
                                // общая полка подписана всегда — коробка на ней станет видна другим.
                                place.location?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (place.isShared) {
                                    Text(stringResource(R.string.transfer_shared), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    // Погашена, пока место не выбрано: здесь гасить честно — нажимать некуда не
                    // потому, что форма неверна, а потому, что человек ещё ничего не назвал.
                    enabled = state.chosen != null,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).defaultMinSize(minHeight = 48.dp)
                ) { Text(stringResource(R.string.pack_action_transfer)) }
            }
        }
    }
}

/** Текст причины — её свойство: экран не подбирает слова сам. */
@get:StringRes
private val PackageTransferRefusal.text: Int
    get() = when (this) {
        PackageTransferRefusal.PACKAGE_GONE -> R.string.pack_gone
        PackageTransferRefusal.PACKAGE_BUSY -> R.string.transfer_busy
        PackageTransferRefusal.ORIGIN_BUSY -> R.string.transfer_origin_busy
        PackageTransferRefusal.TARGET_GONE -> R.string.transfer_target_gone
        PackageTransferRefusal.TARGET_BUSY -> R.string.transfer_target_busy
    }
