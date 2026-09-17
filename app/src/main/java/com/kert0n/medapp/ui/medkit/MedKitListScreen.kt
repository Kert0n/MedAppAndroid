package com.kert0n.medapp.ui.medkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.SearchEntry
import kotlin.uuid.Uuid

/**
 * Список аптечек (PLAN H3 №2). Пока ни одной не заведено, экран не притворяется списком: он
 * говорит, что аптечек нет, и предлагает завести первую.
 *
 * Поиск отсюда ведёт ко всем лекарствам сразу: ища лекарство, человек не помнит, в какой оно
 * аптечке, — это одно чтение в двух областях, и вторая область здесь.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedKitListScreen(
    state: ScreenState<List<MedKitPresentationDTO>>,
    onOpen: (Uuid) -> Unit,
    onAdd: () -> Unit,
    onJoin: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val medKits = (state as? ScreenState.Ready)?.value
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.med_kits_title)) },
                // «Присоединиться» стоит и при пустом списке: человек, которого позвали, своей
                // аптечки может не иметь вовсе, и заводить её ему незачем.
                actions = {
                    IconButton(onClick = onJoin) {
                        Icon(
                            painterResource(R.drawable.ic_group_add),
                            contentDescription = stringResource(R.string.med_kit_joining_menu)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            // У пустого списка кнопка одна — та, что в самом рассказе: две кнопки «завести»
            // рядом человеку выбирать не из чего.
            if (!medKits.isNullOrEmpty()) {
                FloatingActionButton(onClick = onAdd) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.med_kits_add)
                    )
                }
            }
        }
    ) { padding ->
        when {
            // Первое чтение базы ещё не пришло: говорить «пусто» рано — это была бы неправда.
            medKits == null -> LoadingState(Modifier.padding(padding))
            medKits.isEmpty() -> EmptyState(
                text = stringResource(R.string.med_kits_empty),
                modifier = Modifier.padding(padding),
                actionText = stringResource(R.string.med_kits_add),
                onAction = onAdd
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                // Снизу больше: под кнопкой «завести» иначе прячется последняя карточка.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SearchEntry(
                        hint = stringResource(R.string.search_medicines_everywhere),
                        onClick = onSearch
                    )
                }
                items(medKits, key = { it.id }) { kit -> MedKitCard(kit, onOpen = { onOpen(kit.id) }) }
            }
        }
    }
}
