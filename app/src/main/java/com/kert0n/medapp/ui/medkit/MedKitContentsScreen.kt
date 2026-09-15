package com.kert0n.medapp.ui.medkit

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.pack.MedKitContentsUiState
import com.kert0n.medapp.presentation.pack.MedKitContentsViewModel
import com.kert0n.medapp.presentation.pack.Narrowing
import com.kert0n.medapp.presentation.pack.Ordering
import com.kert0n.medapp.presentation.pack.RemovalRefusal
import com.kert0n.medapp.presentation.pack.RemovalStep
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.pack.PackageCard
import kotlin.uuid.Uuid

/** Где экран берёт состояние и куда ведёт. */
@Composable
fun MedKitContentsRoute(
    onBack: () -> Unit,
    onOpen: (Uuid) -> Unit,
    onAdd: (Uuid) -> Unit,
    onEdit: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MedKitContentsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isRemoved) { if (state.isRemoved) onBack() }
    MedKitContentsScreen(
        state = state,
        onBack = onBack,
        onOpen = onOpen,
        onAdd = { state.medKit?.id?.let(onAdd) },
        onEdit = { state.medKit?.id?.let(onEdit) },
        onSearch = viewModel::search,
        onNarrow = viewModel::narrow,
        onOrder = viewModel::order,
        onReset = viewModel::reset,
        onAskToRemove = viewModel::askToRemove,
        onPickTarget = viewModel::pickTarget,
        onRemove = viewModel::remove,
        onDismissRemoval = viewModel::dismissRemoval,
        modifier = modifier
    )
}

/**
 * Содержимое аптечки (PLAN H3 №4) и все лекарства сразу (№5). Экран один, областей две.
 *
 * Поиск, фильтр и сортировка стоят рядом и не мешают друг другу: порядок нажатий результат не
 * меняет (PLAN H4). Пустых состояний два, и они разные: «здесь пока ничего нет» зовёт завести
 * упаковку, «ничего не нашлось» — сбросить запрос. Спутать их значит предложить человеку не то.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedKitContentsScreen(
    state: MedKitContentsUiState,
    onBack: () -> Unit,
    onOpen: (Uuid) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onSearch: (String) -> Unit,
    onNarrow: (Narrowing?) -> Unit,
    onOrder: (Ordering) -> Unit,
    onReset: () -> Unit,
    onAskToRemove: () -> Unit,
    onPickTarget: () -> Unit,
    onRemove: (Uuid?) -> Unit,
    onDismissRemoval: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(state.medKit?.name ?: stringResource(R.string.all_medicines_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                // У всех лекарств сразу хозяина нет: править и убирать там нечего.
                actions = { if (!state.isEverywhere) ShelfMenu(onEdit, onAskToRemove) }
            )
        },
        floatingActionButton = {
            if (!state.isEverywhere && state.packages.isNotEmpty()) {
                FloatingActionButton(onClick = onAdd) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.pack_add)
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.text,
                onValueChange = onSearch,
                label = {
                    Text(
                        stringResource(
                            if (state.isEverywhere) R.string.search_everywhere else R.string.search_here
                        )
                    )
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (state.text.isNotEmpty()) {
                        IconButton(onClick = { onSearch("") }) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.search_clear)
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            NarrowingRow(state, onNarrow, onOrder)
            when {
                !state.isLoaded -> LoadingState()
                state.packages.isEmpty() && state.isNarrowed -> EmptyState(
                    text = stringResource(R.string.pack_nothing_found),
                    actionText = stringResource(R.string.search_reset),
                    onAction = onReset
                )
                state.packages.isEmpty() && state.isEverywhere ->
                    EmptyState(text = stringResource(R.string.pack_none_anywhere))
                state.packages.isEmpty() -> EmptyState(
                    text = stringResource(R.string.pack_none_here),
                    actionText = stringResource(R.string.pack_add),
                    onAction = onAdd
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.packages, key = { it.id }) { pack ->
                        PackageCard(
                            pack = pack,
                            today = state.today,
                            onOpen = { onOpen(pack.id) },
                            medKitName = state.placeNames[pack.medKitId]
                        )
                    }
                }
            }
        }
    }
    state.removing?.let { Removal(state, it, onPickTarget, onRemove, onDismissRemoval) }
}

/** Что можно сделать с самой аптечкой: править её сведения и убрать её целиком (PLAN H3). */
@Composable
private fun ShelfMenu(onEdit: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painterResource(R.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.med_kit_menu)
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.med_kit_edit)) },
            onClick = {
                open = false
                onEdit()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.med_kit_remove)) },
            onClick = {
                open = false
                onRemove()
            }
        )
    }
}

/**
 * Чем сузить. Фильтр ровно один, и нажатие на выбранный его снимает: иначе выйти из
 * «просроченных» можно было бы только через другой фильтр (PLAN H4).
 */
@Composable
private fun NarrowingRow(
    state: MedKitContentsUiState,
    onNarrow: (Narrowing?) -> Unit,
    onOrder: (Ordering) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Chip(stringResource(R.string.filter_expired), state.narrowing == Narrowing.Expired) {
            onNarrow(if (it) null else Narrowing.Expired)
        }
        Chip(stringResource(R.string.filter_expiring), state.narrowing == Narrowing.ExpiringSoon) {
            onNarrow(if (it) null else Narrowing.ExpiringSoon)
        }
        Chip(stringResource(R.string.filter_on_course), state.narrowing == Narrowing.OnCourse) {
            onNarrow(if (it) null else Narrowing.OnCourse)
        }
        Chip(stringResource(R.string.filter_has_free), state.narrowing == Narrowing.HasFree) {
            onNarrow(if (it) null else Narrowing.HasFree)
        }
        val category = state.narrowing as? Narrowing.OfCategory
        ChoosingChip(
            text = category?.category ?: stringResource(R.string.filter_category),
            selected = category != null,
            options = state.categories,
            optionText = { it },
            onPick = { onNarrow(Narrowing.OfCategory(it)) },
            onUnselect = { onNarrow(null) }
        )
        val form = state.narrowing as? Narrowing.OfForm
        ChoosingChip(
            text = form?.form?.name ?: stringResource(R.string.filter_form),
            selected = form != null,
            options = state.forms,
            optionText = { it.name },
            onPick = { onNarrow(Narrowing.OfForm(it)) },
            onUnselect = { onNarrow(null) }
        )
        val orderings = Ordering.entries
        val named = orderings.associateWith { stringResource(it.text) }
        ChoosingChip(
            text = stringResource(R.string.sort_by, named.getValue(state.ordering)),
            selected = state.ordering != Ordering.NAME,
            options = orderings,
            optionText = { named.getValue(it) },
            onPick = onOrder
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(selected) },
        label = { Text(text) },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
    )
}

/**
 * Значение выбирается из того, что в этой области действительно есть.
 *
 * [onUnselect] есть не у всякого выбора: снять можно сужение — его может не быть вовсе, — а
 * порядок есть всегда, и снимать его не во что. Без этого различия чип сортировки, показывающий
 * «по сроку», по нажатию возвращал порядок к названию вместо того, чтобы открыть список: чтобы
 * сменить «по сроку» на «по количеству», приходилось нажимать дважды.
 */
@Composable
private fun <T> ChoosingChip(
    text: String,
    selected: Boolean,
    options: List<T>,
    optionText: (T) -> String,
    onPick: (T) -> Unit,
    onUnselect: (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    Column {
        FilterChip(
            selected = selected,
            onClick = { if (selected && onUnselect != null) onUnselect() else open = true },
            label = { Text(text) },
            enabled = selected || options.isNotEmpty(),
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_keyboard_arrow_down), contentDescription = null)
            },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        onPick(option)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * Разговор об уборке аптечки (PLAN H3). Пустую достаточно подтвердить; у непустой человек
 * выбирает судьбу лекарств, и оба пути названы последствиями, а не словом «удалить».
 */
@Composable
private fun Removal(
    state: MedKitContentsUiState,
    step: RemovalStep,
    onPickTarget: () -> Unit,
    onRemove: (Uuid?) -> Unit,
    onDismiss: () -> Unit
) {
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val name = state.medKit?.name.orEmpty()
    when (step) {
        RemovalStep.ASKING -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.med_kit_remove_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val inside = state.medKit?.contents?.packages ?: 0
                    if (inside == 0) {
                        Text(stringResource(R.string.med_kit_remove_empty))
                    } else {
                        Text(pluralStringResource(R.plurals.med_kit_remove_with_packages, inside, inside))
                        if (state.others.isEmpty()) {
                            Text(
                                stringResource(R.string.med_kit_remove_nowhere),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            TextButton(
                                onClick = onPickTarget,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            ) { Text(stringResource(R.string.med_kit_remove_transfer)) }
                        }
                        Text(
                            stringResource(R.string.med_kit_remove_consequences),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.removalRefusal?.let {
                        Text(stringResource(it.text), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onRemove(null) }) {
                    Text(
                        stringResource(
                            if ((state.medKit?.contents?.packages ?: 0) == 0) R.string.action_remove
                            else R.string.med_kit_remove_with_drugs
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        )

        RemovalStep.PICKING_TARGET -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.med_kit_remove_target_title)) },
            text = {
                Column {
                    for (place in state.others) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .selectable(
                                    selected = target == place.id.toString(),
                                    onClick = { target = place.id.toString() }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = target == place.id.toString(), onClick = null)
                            Text(place.name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = target != null,
                    onClick = { target?.let { onRemove(Uuid.parse(it)) } }
                ) { Text(stringResource(R.string.med_kit_remove_and_transfer)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** Текст причины — её свойство: экран не подбирает слова сам. */
@get:StringRes
private val RemovalRefusal.text: Int
    get() = when (this) {
        RemovalRefusal.BUSY -> R.string.med_kit_busy
        RemovalRefusal.TARGET_GONE -> R.string.med_kit_remove_target_gone
        RemovalRefusal.TARGET_BUSY -> R.string.med_kit_remove_target_busy
        RemovalRefusal.CONTENTS_BUSY -> R.string.med_kit_remove_contents_busy
        RemovalRefusal.NOT_SHARED -> R.string.med_kit_remove_not_shared
    }

@get:StringRes
private val Ordering.text: Int
    get() = when (this) {
        Ordering.NAME -> R.string.sort_name
        Ordering.EXPIRY -> R.string.sort_expiry
        Ordering.ADDED_AT -> R.string.sort_added
        Ordering.QUANTITY -> R.string.sort_quantity
    }
