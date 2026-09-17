package com.kert0n.medapp.ui.course

import com.kert0n.medapp.ui.NavigationRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kert0n.medapp.R
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.presentation.course.CourseCoveragePresentationDTO
import com.kert0n.medapp.presentation.course.CourseEstimatePresentationDTO
import com.kert0n.medapp.presentation.course.CourseSourcePresentationDTO
import com.kert0n.medapp.presentation.course.CourseSourcesMessage
import com.kert0n.medapp.presentation.course.CourseSourcesUiState
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.course.ShortagePresentationDTO
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.presentation.value.toPresentationDTO
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * Источники лечения (PLAN H3 №16): стек коробок в порядке расходования. Порядок меняется
 * перетаскиванием за ручку и теми же двумя действиями у экранного чтеца — жест ему недоступен.
 *
 * **Правка местная, записывает её «Сохранить»**: ползунок двигают пальцем, и предел под ним
 * экран считает сам — ждать базу между движениями нельзя. Пока не записано, сводка обеспечения
 * говорит о прежнем составе и признаётся в этом. Отвязка у идущего лечения спрашивается.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSourcesScreen(
    state: CourseSourcesUiState,
    onMove: (Int, Int) -> Unit,
    onAllocate: (Uuid, Int) -> Unit,
    onDetach: (Uuid) -> Unit,
    onConfirmDetach: () -> Unit,
    onDismissDetach: () -> Unit,
    onDismissMessage: () -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.title ?: stringResource(R.string.course_sources)) },
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Исход записи — выше ветвления: отвязав последний источник, человек остаётся с
            // пустым списком, и сообщение пропало бы вместе с ним (PLAN H3 №16).
            state.message?.let { message ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Слова переносятся, а «Понятно» остаётся на экране: строка исхода длинная,
                    // и на 360 dp кнопку вытолкнуло бы за край.
                    Text(message.words(), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_got_it)) }
                }
            }
            when {
                state.isLoading -> LoadingState()
                state.isGone -> ErrorMessage(text = stringResource(R.string.course_missing))
                state.sources.isEmpty() -> EmptyState(
                    text = stringResource(R.string.course_sources_empty),
                    actionText = stringResource(R.string.course_sources_add).takeUnless { state.isFinished },
                    onAction = onAdd.takeUnless { state.isFinished }
                )
                else -> Sources(state, onMove, onAllocate, onDetach, onAdd, onSave)
            }
        }
    }
    state.asksToDetach?.let {
        DetachDialog(onConfirm = onConfirmDetach, onDismiss = onDismissDetach)
    }
}

@Composable
private fun Sources(
    state: CourseSourcesUiState,
    onMove: (Int, Int) -> Unit,
    onAllocate: (Uuid, Int) -> Unit,
    onDetach: (Uuid) -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var shift by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    val count = state.sources.size
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            // Первым — обеспечение: ради него сюда и приходят (PLAN H3 №16).
            item(key = "supply") { Supply(state, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item(key = "order") {
                IconLine(
                    R.drawable.ic_swap_vert,
                    stringResource(R.string.course_sources_order),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            itemsIndexed(state.sources, key = { _, source -> source.packageId }) { index, source ->
                val held = dragging == index
                SourceRow(
                    source = source,
                    dose = state.dose,
                    isFinished = state.isFinished,
                    onAllocate = onAllocate,
                    onDetach = { onDetach(source.packageId) },
                    onMoveUp = if (index > 0) { { onMove(index, index - 1) } } else null,
                    onMoveDown = if (index < count - 1) { { onMove(index, index + 1) } } else null,
                    held = held,
                    modifier = Modifier
                        .zIndex(if (held) 1f else 0f)
                        .graphicsLayer { translationY = if (held) shift else 0f }
                        .onSizeChanged { if (it.height > 0) rowHeight = it.height },
                    handleModifier = Modifier.pointerInput(index, count, rowHeight) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = index; shift = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                shift += amount.y
                            },
                            onDragEnd = {
                                // Куда строка уехала: шаг — высота соседней строки, и она же
                                // говорит, через сколько соседей человек её перенёс.
                                val moved = if (rowHeight > 0) (shift / rowHeight).roundToInt() else 0
                                val target = (index + moved).coerceIn(0, count - 1)
                                dragging = null
                                shift = 0f
                                if (target != index) onMove(index, target)
                            },
                            onDragCancel = { dragging = null; shift = 0f }
                        )
                    }
                )
            }
            // Подключить — строка в конце списка, а не вторая кнопка рядом с «Сохранить»: две кнопки
            // разного веса подряд читались как выбор между ними.
            if (!state.isFinished) {
                item(key = "add") {
                    NavigationRow(
                        icon = R.drawable.ic_add,
                        text = stringResource(R.string.course_sources_add),
                        onClick = onAdd
                    )
                }
            }
        }
        if (!state.isFinished) {
            HorizontalDivider()
            Button(
                onClick = onSave,
                enabled = !state.isWriting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .defaultMinSize(minHeight = 48.dp)
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

/**
 * Обеспечение одной карточкой, и каждая строка подписана: «Записано» — чем лечение обеспечено
 * сейчас, «С правкой» — что даст собранный на экране состав. Вторая есть **только** пока правка не
 * записана: два одинаковых числа без подписи человек не различит (PLAN H3 №16). У черновика
 * записанного нет — есть «По составу».
 */
@Composable
private fun Supply(state: CourseSourcesUiState, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconLine(R.drawable.ic_medication, stringResource(R.string.course_sources_supply), emphasis = true)
            if (!state.isDraft || state.coverage != null) {
                Labeled(R.drawable.ic_check_circle, stringResource(R.string.course_sources_recorded)) {
                    Coverage(state.coverage, isDraft = state.isDraft)
                }
            } else {
                Coverage(null, isDraft = true)
            }
            val estimate = state.estimate
            if (estimate != null && (state.hasUnsavedChanges || state.coverage == null)) {
                Labeled(
                    R.drawable.ic_edit_note,
                    stringResource(if (state.hasUnsavedChanges) R.string.course_sources_with_edit else R.string.course_sources_by_composition)
                ) { Estimate(estimate) }
            }
        }
    }
}

/** Подпись со значком и под ней то, что она подписывает. */
@Composable
private fun Labeled(icon: Int, label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IconLine(icon, label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.padding(start = 24.dp)) { content() }
    }
}

/** Значок и слова одной строкой: значок уточняет, слова говорят. */
@Composable
private fun IconLine(
    icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    style: androidx.compose.ui.text.TextStyle = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = style, color = color)
    }
}

@Composable
private fun SourceRow(
    source: CourseSourcePresentationDTO,
    dose: QuantityPresentationDTO?,
    isFinished: Boolean,
    onAllocate: (Uuid, Int) -> Unit,
    onDetach: () -> Unit,
    /** `null` — двигать некуда: действия нет, и чтец не докладывает об успехе, которого не было. */
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    held: Boolean,
    modifier: Modifier,
    handleModifier: Modifier
) {
    val up = stringResource(R.string.course_source_move_up)
    val down = stringResource(R.string.course_source_move_down)
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            // Приглушена и отключённая коробка, и уходящая: обе ничего не дают.
            .alpha(if (source.fault == null && source.leaving == null) 1f else 0.6f)
            .semantics {
                customActions = listOfNotNull(
                    onMoveUp?.let { move -> CustomAccessibilityAction(up) { move(); true } },
                    onMoveDown?.let { move -> CustomAccessibilityAction(down) { move(); true } }
                )
            },
        // Поднятая карточка видна тенью.
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (held) 8.dp else 1.dp)
    ) {
        Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_drag_handle),
                    contentDescription = stringResource(R.string.course_source_handle, source.name),
                    modifier = handleModifier.padding(12.dp)
                )
                Text(source.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!isFinished) {
                    IconButton(onClick = onDetach) {
                        Icon(painterResource(R.drawable.ic_link_off), contentDescription = stringResource(R.string.course_source_detach))
                    }
                } else {
                    Spacer(Modifier.width(16.dp))
                }
            }
            Column(Modifier.padding(start = 48.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                source.place()?.let { IconLine(R.drawable.ic_home_storage, it) }
                // «Свободно 0» у уходящей коробки сказало бы, что она пустая; пустой она не стала —
                // её просто не будет у человека. Об этом говорит строка причины.
                source.availableToMe?.takeIf { source.leaving == null }?.let {
                    Text(
                        stringResource(R.string.course_source_free, it.amount, it.unit.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Коробка, о судьбе которой решение принято, ничего не обеспечивает ещё до ответа
                // сервера (PLAN D4, E1). Приёмы из неё пропадают сразу, и человек читает почему —
                // иначе нехватка выглядит ошибкой приложения. Выделение при этом только
                // называется: двигать его у коробки, которой вот-вот не станет, некуда, а вернёт
                // её отказ полки вместе с прежним числом.
                val leaving = source.leaving()
                when {
                    leaving != null -> {
                        Text(leaving, color = MaterialTheme.colorScheme.error)
                        Text(source.allocation())
                    }
                    source.fault != null -> Text(source.fault.words(), color = MaterialTheme.colorScheme.error)
                    else -> {
                        Allocation(source, dose = dose, enabled = !isFinished, onAllocate = onAllocate)
                        Text(source.allocation(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * Что получится у собранного состава: «нужно 28 приёмов · обеспечено 9 · не хватает 19». Считается
 * на месте, без записи, поэтому дня нехватки здесь нет — его знает только записанное обеспечение,
 * разложенное по пунктам календаря (PLAN H3 №16).
 */
@Composable
private fun Estimate(estimate: CourseEstimatePresentationDTO, modifier: Modifier = Modifier) {
    val required = pluralStringResource(
        R.plurals.course_coverage_required,
        estimate.requiredDoses,
        estimate.requiredDoses
    )
    Column(modifier) {
        Text(
            stringResource(R.string.course_coverage_covered, required, estimate.coveredDoses),
            style = MaterialTheme.typography.bodyMedium
        )
        if (estimate.missingDoses > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    pluralStringResource(R.plurals.course_shortage, estimate.missingDoses, estimate.missingDoses),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Чем лечение обеспечено по записанному составу: «нужно 28 приёмов · обеспечено 9 · не хватает 19,
 * с 11.09.2026». У
 * черновика обеспечения нет — оно появится, когда лечение начнётся (PLAN H3 №16, B15). Нехватку
 * несут слова и значок, а не один цвет.
 */
@Composable
internal fun Coverage(
    coverage: CourseCoveragePresentationDTO?,
    isDraft: Boolean,
    modifier: Modifier = Modifier,
    onShortage: ((ShortagePresentationDTO) -> Unit)? = null
) {
    if (coverage == null) {
        if (isDraft) {
            Text(
                stringResource(R.string.course_coverage_after_start),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier
            )
        }
        return
    }
    val required = pluralStringResource(
        R.plurals.course_coverage_required,
        coverage.requiredDoses,
        coverage.requiredDoses
    )
    if (coverage.isFullyCovered) {
        val until = coverage.coveredUntilOn?.format(DAY)
        Text(
            if (until == null) required else stringResource(R.string.course_coverage_until, required, until),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier
        )
        return
    }
    Column(modifier) {
        Text(
            stringResource(R.string.course_coverage_covered, required, coverage.coveredDoses),
            style = MaterialTheme.typography.bodyMedium
        )
        val shortage = ShortagePresentationDTO(coverage.missingDoses, coverage.firstUncoveredOn)
        Shortage(shortage, onClick = onShortage?.let { { it(shortage) } })
    }
}

/**
 * Ползунок и поле — **одно число приёмов**: `Float` здесь только координата, а значение целое и
 * проходит ту же проверку, что ручной ввод (PLAN D5 «Почему выделение в дозах»). Во время
 * движения не зовётся ничего — запись уходит по отпусканию (C1 «Ползунок»).
 *
 * Предела нет — выделять нечего: у черновика без дозы и числа приёмов, у коробки, которая не
 * даёт ни одной целой дозы. Тогда сказано словами, а не показан ползунок, который не двигается.
 */
@Composable
private fun Allocation(
    source: CourseSourcePresentationDTO,
    dose: QuantityPresentationDTO?,
    enabled: Boolean,
    onAllocate: (Uuid, Int) -> Unit
) {
    val limit = source.maxDoses
    if (limit == null || limit == 0) {
        Text(
            when {
                limit == null -> stringResource(R.string.course_source_no_limit)
                // Доза берётся из одной коробки (C1), и здесь её не набрать: чтобы это не читалось
                // как ошибка приложения, сказано, какой именно дозы не хватает.
                dose != null ->
                    stringResource(R.string.course_source_gives_nothing_for, dose.amount, dose.unit.name)
                else -> stringResource(R.string.course_source_gives_nothing)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    // Пока палец на ползунке, число — человека; отпустил — снова то, что в правке. Набранное в
    // поле держать незачем: оно уезжает с каждой цифрой, а зажатое под предел приходит обратно.
    var held by remember(source.packageId) { mutableStateOf<Int?>(null) }
    val shown = held ?: source.allocatedDoses
    val doses = stringResource(R.string.course_source_doses, limit)
    val commit = {
        held?.let { onAllocate(source.packageId, it) }
        held = null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            value = shown.coerceIn(0, limit).toFloat(),
            onValueChange = { held = it.roundToInt() },
            onValueChangeFinished = commit,
            valueRange = 0f..limit.toFloat(),
            steps = (limit - 1).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = shown.toString(),
            // Число приёмов целое: в поле идут только цифры, а предел держит сам курс. Набранное
            // уезжает **сразу**: палец с поля на «Сохранить» фокус не уводит, и число, ждущее
            // ухода из поля, пропало бы молча (история Ирины).
            onValueChange = { typed ->
                held = null
                // Цифры — и только они; предел держит курс, а не длина набранного: «1000» у
                // лечения на тысячу приёмов это тысяча, а не сто (разбор #47).
                onAllocate(source.packageId, typed.filter(Char::isDigit).toIntOrNull() ?: 0)
            },
            // Не всплывающая подпись, а приставка: подпись над рамкой ломалась на узком экране и
            // наезжала на строку выделения. Для экранного чтеца поле названо целиком.
            suffix = { Text(stringResource(R.string.course_source_doses_of, limit)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(120.dp)
                .semantics { contentDescription = doses }
                // Ушёл из поля — значит, дописал: число уезжает так же, как отпущенный ползунок.
                .onFocusChanged { if (!it.isFocused && held != null) commit() }
        )
    }
}

/** Где коробка лежит и до каких пор годна — одной строкой; `null` — сказать нечего. */
@Composable
private fun CourseSourcePresentationDTO.place(): String? = listOfNotNull(
    medKitName,
    expiresOn?.let { stringResource(R.string.course_source_expires, it.toPresentationDTO().text) }
).joinToString(" · ").ifEmpty { null }

/**
 * Почему коробка сейчас ничего не даёт: решение о ней ещё едет серверу. Слова те же, что на
 * списках коробок, — одна беда называется в приложении одинаково.
 */
@Composable
private fun CourseSourcePresentationDTO.leaving(): String? = leaving?.let { stringResource(it) }

/** Та же беда теми же словами, что на списках коробок: пометка одна, и название у неё одно. */
private val CourseSourcePresentationDTO.leaving: Int?
    get() = when (status) {
        PackageStatus.REMOVING -> R.string.pack_row_removal
        PackageStatus.LOST -> R.string.pack_row_lost
        PackageStatus.ACTIVE, PackageStatus.CHANGING, null -> null
    }

/** Сколько приёмов выделено — и сколько это в единицах коробки: то же число привычной мерой. */
@Composable
private fun CourseSourcePresentationDTO.allocation(): String {
    val doses = pluralStringResource(R.plurals.course_source_allocated, allocatedDoses, allocatedDoses)
    return allocatedAmount?.let { "$doses · ${it.amount} ${it.unit.name}" } ?: doses
}

@Composable
private fun CourseSource.Fault.words(): String = stringResource(
    when (this) {
        CourseSource.Fault.UNIT_MISMATCH -> R.string.course_source_fault_unit
        CourseSource.Fault.FORM_MISMATCH -> R.string.course_source_fault_form
    }
)

/** Отказ сценария словами: человек читает, что случилось, и стек остаётся как был. */
@Composable
internal fun CourseSourcesMessage.words(): String = when (this) {
    is CourseSourcesMessage.Taken ->
        name?.let { stringResource(R.string.course_source_taken_named, it) }
            ?: stringResource(R.string.course_source_taken)
    is CourseSourcesMessage.Unusable ->
        name?.let { stringResource(R.string.course_source_unusable_named, it) }
            ?: stringResource(R.string.course_source_unusable)
    is CourseSourcesMessage.Refused -> stringResource(reason.text)
    is CourseSourcesMessage.BeyondLimit ->
        pluralStringResource(R.plurals.course_source_beyond_limit, limit, limit)
    CourseSourcesMessage.Finished -> stringResource(R.string.course_finished)
    CourseSourcesMessage.Stale -> stringResource(R.string.course_sources_stale)
}


@Composable
private fun DetachDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_source_detach_title)) },
        text = { Text(stringResource(R.string.course_source_detach_explained)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.course_source_detach)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
