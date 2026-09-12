package com.kert0n.medapp.feature.packs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.feature.medkits.MedKitRemoval
import com.kert0n.medapp.storage.pack.PackageQuery
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import kotlin.uuid.Uuid

/**
 * Содержимое аптечки (PLAN H3 №4) и все лекарства сразу (№5). Экран один, областей две: аптечка
 * названа или не названа.
 *
 * Поиск, фильтр и сортировка стоят рядом и не мешают друг другу: порядок нажатий результат не
 * меняет (PLAN H4). Пустых состояний два, и они разные: «здесь пока ничего нет» зовёт завести
 * упаковку, «ничего не нашлось» — сбросить запрос. Спутать их значит предложить человеку не то.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedKitContentsScreen(
    medKitId: Uuid?,
    onBack: () -> Unit,
    onOpen: (Uuid) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = {},
    viewModel: MedKitContentsViewModel = hiltViewModel()
) {
    LaunchedEffect(medKitId) { viewModel.open(medKitId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
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
                actions = {
                    // У всех лекарств сразу хозяина нет: править и убирать там нечего.
                    if (!state.everywhere) MedKitMenu(onEdit = onEdit, onRemove = viewModel::askToRemove)
                }
            )
        },
        floatingActionButton = {
            if (!state.everywhere && !state.packs.isNullOrEmpty()) {
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
                value = state.query.text,
                onValueChange = viewModel::search,
                label = { Text(stringResource(R.string.search_medicines)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                },
                trailingIcon = {
                    if (state.query.text.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
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
            Narrowing(state, viewModel)
            val packs = state.packs
            when {
                packs == null -> LoadingState()
                packs.isEmpty() && state.isNarrowed -> EmptyState(
                    text = stringResource(R.string.pack_nothing_found),
                    actionText = stringResource(R.string.search_reset),
                    onAction = viewModel::reset
                )
                packs.isEmpty() && state.everywhere ->
                    EmptyState(text = stringResource(R.string.pack_none_anywhere))
                packs.isEmpty() -> EmptyState(
                    text = stringResource(R.string.pack_none_here),
                    actionText = stringResource(R.string.pack_add),
                    onAction = onAdd
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(packs, key = { it.id }) { pkg ->
                        PackageCard(
                            pkg = pkg,
                            onOpen = { onOpen(pkg.id) },
                            medKitName = state.medKitNames[pkg.medKitId],
                            today = state.today
                        )
                    }
                }
            }
        }
    }
    state.removing?.let { Removal(state, it, viewModel, onRemoved = onBack) }
}

/** Что можно сделать с самой аптечкой: править её сведения и убрать её целиком (PLAN H3). */
@Composable
private fun MedKitMenu(onEdit: () -> Unit, onRemove: () -> Unit) {
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
 * Разговор об удалении аптечки (PLAN H3). Пустую достаточно подтвердить; у непустой человек
 * выбирает судьбу лекарств, и оба пути названы последствиями, а не словом «удалить».
 */
@Composable
private fun Removal(
    state: MedKitContentsViewModel.State,
    step: MedKitContentsViewModel.Removing,
    viewModel: MedKitContentsViewModel,
    onRemoved: () -> Unit
) {
    val name = state.medKit?.name.orEmpty()
    when (step) {
        MedKitContentsViewModel.Removing.Asking -> AlertDialog(
            onDismissRequest = viewModel::dismissRemoval,
            title = { Text(stringResource(R.string.med_kit_remove_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val inside = state.packs?.size ?: 0
                    if (inside == 0) {
                        Text(stringResource(R.string.med_kit_remove_empty))
                    } else {
                        Text(stringResource(R.string.med_kit_remove_with_packages, inside))
                        if (state.others.isEmpty()) {
                            Text(
                                stringResource(R.string.med_kit_remove_nowhere),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            TextButton(
                                onClick = viewModel::pickTarget,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            ) { Text(stringResource(R.string.med_kit_remove_transfer)) }
                        }
                        Text(
                            stringResource(R.string.med_kit_remove_consequences),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.remove(onRemoved = onRemoved) }) {
                    Text(
                        stringResource(
                            if (state.packs.isNullOrEmpty()) R.string.action_remove
                            else R.string.med_kit_remove_with_drugs
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemoval) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )

        is MedKitContentsViewModel.Removing.PickingTarget -> AlertDialog(
            onDismissRequest = viewModel::dismissRemoval,
            title = { Text(stringResource(R.string.med_kit_remove_target_title)) },
            text = {
                Column {
                    for (kit in state.others) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .selectable(
                                    selected = step.target == kit.id,
                                    onClick = { viewModel.chooseTarget(kit.id) }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = step.target == kit.id, onClick = null)
                            Text(kit.name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = step.target != null,
                    onClick = { viewModel.remove(transferTo = step.target, onRemoved = onRemoved) }
                ) { Text(stringResource(R.string.med_kit_remove_and_transfer)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemoval) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )

        is MedKitContentsViewModel.Removing.Refused -> AlertDialog(
            onDismissRequest = viewModel::dismissRemoval,
            title = { Text(stringResource(R.string.med_kit_remove_title, name)) },
            text = { Text(stringResource(step.reason.text)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRemoval) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** Текст отказа — его свойство: экран не подбирает слова сам. */
private val MedKitRemoval.Outcome.text: Int
    get() = when (this) {
        MedKitRemoval.Outcome.NEEDS_NETWORK -> R.string.med_kit_remove_needs_network
        MedKitRemoval.Outcome.TARGET_GONE -> R.string.med_kit_remove_target_gone
        MedKitRemoval.Outcome.TARGET_IS_THE_SAME -> R.string.med_kit_remove_nowhere
        else -> R.string.med_kit_remove_gone
    }

/**
 * Чем сузить список. Фильтр ровно один, и нажатие на выбранный его снимает: иначе выйти из
 * «просроченных» можно было бы только через другой фильтр (PLAN H4).
 */
@Composable
private fun Narrowing(
    state: MedKitContentsViewModel.State,
    viewModel: MedKitContentsViewModel
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Chip(
            text = stringResource(R.string.filter_expired),
            selected = state.query.filter == PackageQuery.Filter.Expired,
            onClick = { viewModel.filter(PackageQuery.Filter.Expired) },
            onUnselect = { viewModel.filter(null) }
        )
        val soon = PackageQuery.Filter.ExpiringWithin(ExpiryDate.SOON_DAYS)
        Chip(
            text = stringResource(R.string.filter_expiring),
            selected = state.query.filter == soon,
            onClick = { viewModel.filter(soon) },
            onUnselect = { viewModel.filter(null) }
        )
        Chip(
            text = stringResource(R.string.filter_on_course),
            selected = state.query.filter == PackageQuery.Filter.OnCourse,
            onClick = { viewModel.filter(PackageQuery.Filter.OnCourse) },
            onUnselect = { viewModel.filter(null) }
        )
        val category = state.query.filter as? PackageQuery.Filter.OfCategory
        ChoosingChip(
            text = category?.category ?: stringResource(R.string.filter_category),
            selected = category != null,
            options = state.categories,
            optionText = { it },
            onPick = { viewModel.filter(PackageQuery.Filter.OfCategory(it)) },
            onUnselect = { viewModel.filter(null) }
        )
        val form = state.query.filter as? PackageQuery.Filter.OfForm
        ChoosingChip(
            text = state.forms.firstOrNull { it.id == form?.formId }?.name
                ?: stringResource(R.string.filter_form),
            selected = form != null,
            options = state.forms,
            optionText = { it.name },
            onPick = { viewModel.filter(PackageQuery.Filter.OfForm(it.id)) },
            onUnselect = { viewModel.filter(null) }
        )
        val sorts = PackageQuery.Sort.entries
        val named = sorts.associateWith { stringResource(it.text) }
        ChoosingChip(
            text = stringResource(R.string.sort_by, named.getValue(state.query.sort)),
            selected = state.query.sort != PackageQuery.Sort.NAME,
            options = sorts,
            optionText = { named.getValue(it) },
            onPick = viewModel::sort,
            onUnselect = { viewModel.sort(PackageQuery.Sort.NAME) }
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, onUnselect: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { if (selected) onUnselect() else onClick() },
        label = { Text(text) },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
    )
}

/** Значение выбирается из того, что в этой области действительно есть. */
@Composable
private fun <T> ChoosingChip(
    text: String,
    selected: Boolean,
    options: List<T>,
    optionText: (T) -> String,
    onPick: (T) -> Unit,
    onUnselect: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column {
        FilterChip(
            selected = selected,
            onClick = { if (selected) onUnselect() else open = true },
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

private val PackageQuery.Sort.text: Int
    get() = when (this) {
        PackageQuery.Sort.NAME -> R.string.sort_name
        PackageQuery.Sort.EXPIRY -> R.string.sort_expiry
        PackageQuery.Sort.ADDED_AT -> R.string.sort_added
        PackageQuery.Sort.QUANTITY -> R.string.sort_quantity
    }
