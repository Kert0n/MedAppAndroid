package com.kert0n.medapp.ui.medkit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.medkit.MedKitPresentationDTO
import com.kert0n.medapp.presentation.pack.MedKitContentsUiState
import com.kert0n.medapp.presentation.pack.Narrowing
import com.kert0n.medapp.presentation.pack.Ordering
import com.kert0n.medapp.presentation.pack.RemovalRefusal
import com.kert0n.medapp.presentation.pack.RemovalStep
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.NavigationRow
import com.kert0n.medapp.ui.SearchField
import com.kert0n.medapp.ui.pack.PackageCard
import kotlin.uuid.Uuid

/**
 * Содержимое полки и все лекарства — **один экран с разной областью** (PLAN H3 №4, №5): полка
 * названа или не названа, и всё остальное у них общее.
 *
 * **Первым — поиск**: сюда приходят искать. Ниже — чем сузить, ещё ниже — что нашлось.
 *
 * **Сужение снимается нажатием, порядок — нет.** Сужения может не быть вовсе, и выйти из
 * «просроченных» иначе нечем; порядок есть всегда, и снимать его не во что — чип сортировки
 * открывает список, а не сбрасывает выбранное.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedKitContentsScreen(
    state: MedKitContentsUiState,
    onSearch: (String) -> Unit,
    onNarrow: (Narrowing?) -> Unit,
    onOrder: (Ordering) -> Unit,
    onReset: () -> Unit,
    onOpen: (Uuid) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onAskToRemove: () -> Unit,
    onPickTarget: () -> Unit,
    onDismissRemoval: () -> Unit,
    onRemove: (Uuid?) -> Unit,
    onLeave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    // Пока полка не прочитана, у экрана нет имени: назвать его «Всеми
                    // лекарствами» значило бы на миг показать не ту область.
                    Text(
                        state.medKit?.name
                            ?: if (state.isEverywhere) stringResource(R.string.contents_everywhere_title) else ""
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                // Меню полки — только у названной: у «всех лекарств» править и убирать нечего.
                actions = {
                    state.medKit?.let {
                        // «Поделиться» или «Пригласить» — по тому же признаку, по которому решает
                        // сам экран: уехала ли полка. Участники тут ни при чём — в опубликованную
                        // полку зовут и тогда, когда в ней пока один человек.
                        ShelfMenu(
                            isShared = it.isPublished,
                            onEdit = onEdit, onShare = onShare, onRemove = onAskToRemove
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            // На экране всех лекарств класть некуда: у коробки одно место, и выбрать его здесь
            // не из чего. У пустой полки кнопка одна — та, что в самом рассказе.
            if (state.isLoaded && !state.isEverywhere && !state.isAreaEmpty && !state.isShelfGone) {
                FloatingActionButton(onClick = onAdd) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.contents_add)
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                // Первое чтение базы ещё не пришло: говорить «пусто» рано — это была бы неправда.
                !state.isLoaded -> LoadingState()
                // Полки нет — ни поиска, ни «завести»: класть некуда, и сказано это словами.
                state.isShelfGone -> EmptyState(stringResource(R.string.contents_shelf_gone))
                // Искать и сужать нечего: ни поля, ни чипов — они бы обещали содержимое.
                state.isAreaEmpty && !state.isNarrowed -> if (state.isEverywhere) {
                    EmptyState(stringResource(R.string.contents_empty_everywhere))
                } else {
                    EmptyState(
                        text = stringResource(R.string.contents_empty_here),
                        actionText = stringResource(R.string.contents_add),
                        onAction = onAdd
                    )
                }
                else -> Found(state, onSearch, onNarrow, onOrder, onReset, onOpen)
            }
        }
    }

    if (state.removing != null) {
        RemovalDialog(state, onPickTarget, onDismissRemoval, onRemove, onLeave)
    }
}

/** Поиск, чем сузить и что нашлось — вместе, потому что запрос и его ответ друг без друга лгут. */
@Composable
private fun Found(
    state: MedKitContentsUiState,
    onSearch: (String) -> Unit,
    onNarrow: (Narrowing?) -> Unit,
    onOrder: (Ordering) -> Unit,
    onReset: () -> Unit,
    onOpen: (Uuid) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        SearchField(
            value = state.text,
            onValueChange = onSearch,
            label = stringResource(
                if (state.isEverywhere) R.string.contents_search_everywhere
                else R.string.contents_search_here
            )
        )

        Narrowings(state, onNarrow, onOrder)

        // Ничего не нашлось — это не «пусто»: запрос и сужение остаются на месте, иначе
        // сбрасывать было бы нечего.
        if (state.packages.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.contents_nothing_found),
                actionText = stringResource(R.string.contents_reset),
                onAction = onReset
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.packages, key = { it.id }) { pkg ->
                PackageCard(
                    pkg = pkg,
                    today = state.today,
                    onOpen = { onOpen(pkg.id) },
                    placeName = state.placeNames[pkg.medKitId]
                )
            }
        }
    }
}

/** Чем сузить и в каком порядке. Прокручивается вбок: на 360 dp чипы в строку не влезают. */
@Composable
private fun Narrowings(
    state: MedKitContentsUiState,
    onNarrow: (Narrowing?) -> Unit,
    onOrder: (Ordering) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Simple(R.string.narrow_expired, Narrowing.Expired, state.narrowing, onNarrow)
        Simple(R.string.narrow_expiring_soon, Narrowing.ExpiringSoon, state.narrowing, onNarrow)
        Simple(R.string.narrow_on_course, Narrowing.OnCourse, state.narrowing, onNarrow)
        Simple(R.string.narrow_has_free, Narrowing.HasFree, state.narrowing, onNarrow)

        val category = state.narrowing as? Narrowing.OfCategory
        Choice(
            label = category?.category ?: stringResource(R.string.narrow_category),
            selected = category != null,
            options = state.categories,
            optionText = { it },
            onClear = { onNarrow(null) },
            onPick = { onNarrow(Narrowing.OfCategory(it)) }
        )

        val form = state.narrowing as? Narrowing.OfForm
        Choice(
            label = form?.form?.name ?: stringResource(R.string.narrow_form),
            selected = form != null,
            options = state.forms,
            optionText = { it.name },
            onClear = { onNarrow(null) },
            onPick = { onNarrow(Narrowing.OfForm(it)) }
        )

        // Порядок — не сужение: снимать его не во что, и нажатие всегда открывает список.
        Choice(
            label = stringResource(R.string.order_label, stringResource(state.ordering.label)),
            selected = false,
            options = Ordering.entries,
            optionText = { stringResource(it.label) },
            onClear = null,
            onPick = onOrder
        )
    }
}

/** Сужение без выбора: оно либо наложено, либо нет, и нажатие переключает его. */
@Composable
private fun Simple(
    label: Int,
    narrowing: Narrowing,
    current: Narrowing?,
    onNarrow: (Narrowing?) -> Unit
) {
    val selected = current == narrowing
    FilterChip(
        selected = selected,
        onClick = { onNarrow(if (selected) null else narrowing) },
        label = { Text(stringResource(label)) }
    )
}

/**
 * Сужение или порядок с выбором из списка. Выбранное сужение нажатием **снимается**, а не
 * открывает список заново: иначе выйти из него было бы нечем ([onClear] = `null` у порядка).
 */
@Composable
private fun <T> Choice(
    label: String,
    selected: Boolean,
    options: List<T>,
    optionText: @Composable (T) -> String,
    onClear: (() -> Unit)?,
    onPick: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { if (selected && onClear != null) onClear() else open = true },
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    painterResource(if (selected) R.drawable.ic_close else R.drawable.ic_expand_more),
                    contentDescription = null
                )
            }
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
 * Меню полки: править, поделиться и убрать. У общей полки «поделиться» уже случилось — там зовут,
 * и пункт называется тем, что человек сделает (PLAN H3 №20).
 */
@Composable
private fun ShelfMenu(isShared: Boolean, onEdit: () -> Unit, onShare: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painterResource(R.drawable.ic_more),
            contentDescription = stringResource(R.string.contents_menu)
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.contents_edit)) },
            onClick = {
                open = false
                onEdit()
            }
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (isShared) R.string.med_kit_sharing_invite_menu else R.string.med_kit_sharing_menu
                    )
                )
            },
            onClick = {
                open = false
                onShare()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.contents_remove)) },
            onClick = {
                open = false
                onRemove()
            }
        )
    }
}

/**
 * Уборка полки — **разговор, а не кнопка** (PLAN H3 №4). Пустая спрашивается одним
 * подтверждением; непустая называет, сколько в ней коробок, и каждая судьба стоит строкой:
 * значок, что произойдёт, и **последствие** под ним. Слова «удалить» здесь нет — человек убирает
 * полку, а не строку в базе.
 *
 * **Судьбы — строки списка, а не кнопки**, и это два замечания владельца разом. Текстовой кнопкой
 * выбор читался набором заголовков, и нажать на него человек не догадывался (2026-09-16, оттуда
 * же `NavigationRow`). Колонка кнопок в слоте `AlertDialog` — это один ряд без прокрутки, и на
 * крупном шрифте нижнее действие обрезалось вместе с «Отменой» (2026-09-17). А последствие выбора
 * жило только в плане: человек выбирал между двумя похожими фразами, не зная, чем они
 * различаются.
 */
@Composable
private fun RemovalDialog(
    state: MedKitContentsUiState,
    onPickTarget: () -> Unit,
    onDismiss: () -> Unit,
    onRemove: (Uuid?) -> Unit,
    onLeave: () -> Unit
) {
    val medKit = state.medKit ?: return
    if (state.removing == RemovalStep.PICKING_TARGET) {
        TargetDialog(state.others, state.removalRefusal, onDismiss, onRemove)
        return
    }
    val count = medKit.contents.packages
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.med_kit_remove_title, medKit.name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (count == 0) {
                        stringResource(R.string.med_kit_remove_empty)
                    } else {
                        pluralStringResource(R.plurals.med_kit_remove_contents, count, count)
                    }
                )
                // Переносить некуда — так и сказано: человек иначе ищет пропавшую кнопку.
                if (count > 0 && state.others.isEmpty()) {
                    Text(stringResource(R.string.med_kit_remove_nowhere))
                }
                // Лечения не отменяются никогда: теряются только источники с этой полки, а
                // история остаётся (PLAN C1 «Курсы при выходе»). Сказать это нужно **до**
                // решения — после него человек уже ничего не выбирает.
                if (state.affectedCourses.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.med_kit_remove_affects_courses,
                            state.affectedCourses.joinToString(", ")
                        ),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                state.removalRefusal?.let {
                    Text(stringResource(it.text), color = MaterialTheme.colorScheme.error)
                }
                // Действие на месте — строка без стрелки: оно никуда не уводит. Перенос уводит к
                // выбору полки, и стрелка у него честная.
                Fate(
                    icon = R.drawable.ic_delete,
                    text = stringResource(
                        if (count == 0) R.string.contents_remove else R.string.med_kit_remove_throw_away
                    ),
                    supporting = stringResource(
                        if (count == 0) R.string.med_kit_remove_empty_consequence
                        else R.string.med_kit_remove_throw_away_consequence
                    ),
                    color = MaterialTheme.colorScheme.error,
                    onClick = { onRemove(null) }
                )
                if (count > 0 && state.others.isNotEmpty()) {
                    NavigationRow(
                        icon = R.drawable.ic_drive_file_move,
                        text = stringResource(R.string.med_kit_remove_move),
                        supporting = stringResource(R.string.med_kit_remove_move_consequence),
                        onClick = onPickTarget
                    )
                }
                // Выйти можно только из общей: из местной выходить некуда — остальных нет (E6).
                if (medKit.isShared) {
                    Fate(
                        icon = R.drawable.ic_logout,
                        text = stringResource(R.string.med_kit_leave),
                        supporting = stringResource(R.string.med_kit_leave_consequence),
                        color = MaterialTheme.colorScheme.onSurface,
                        onClick = onLeave
                    )
                }
            }
        },
        // Кнопкой остаётся одна «Отмена»: она и есть «ничего не делать», и ряд из неё одной не
        // разъезжается ни при каком шрифте.
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Судьба коробок, которая случится **здесь**: значок, что произойдёт, и последствие под ним.
 * Стрелки нет — строка не уводит, и стрелка ей соврала бы (`NavigationRow`). Нажимается строка
 * целиком, и на крупном шрифте она переносится, а не обрезается.
 */
@Composable
private fun Fate(
    @DrawableRes icon: Int,
    text: String,
    supporting: String,
    color: Color,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(text, color = color) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}

/**
 * Куда перенести лекарства. Пока полка не выбрана, переносить нечего — и кнопка молчит. Отказ
 * цели показывается здесь же: другую полку выбирают там, где выбирали эту.
 */
@Composable
private fun TargetDialog(
    others: List<MedKitPresentationDTO>,
    refusal: RemovalRefusal?,
    onDismiss: () -> Unit,
    onRemove: (Uuid?) -> Unit
) {
    // Выбор переживает поворот: перечитывать список полок человеку заново незачем.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.med_kit_remove_target_title)) },
        text = {
            Column {
                for (other in others) {
                    val chosen = picked == other.id.toString()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = chosen, onClick = { picked = other.id.toString() })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(selected = chosen, onClick = null)
                        Text(other.name)
                    }
                }
                refusal?.let { Text(stringResource(it.text), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { picked?.let { onRemove(Uuid.parse(it)) } },
                enabled = picked != null
            ) { Text(stringResource(R.string.med_kit_remove_move)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Как называется порядок: слова принадлежат экрану, а не перечислению. */
private val Ordering.label: Int
    get() = when (this) {
        Ordering.NAME -> R.string.order_name
        Ordering.EXPIRY -> R.string.order_expiry
        Ordering.ADDED_AT -> R.string.order_added_at
        Ordering.QUANTITY -> R.string.order_quantity
    }

/** Почему полка не убралась — словами причины, а не кодом исхода. */
private val RemovalRefusal.text: Int
    get() = when (this) {
        RemovalRefusal.BUSY -> R.string.med_kit_removal_busy
        RemovalRefusal.TARGET_GONE -> R.string.med_kit_removal_target_gone
        RemovalRefusal.TARGET_BUSY -> R.string.med_kit_removal_target_busy
        RemovalRefusal.CONTENTS_BUSY -> R.string.med_kit_removal_contents_busy
        RemovalRefusal.NOT_SHARED -> R.string.med_kit_removal_not_shared
    }
