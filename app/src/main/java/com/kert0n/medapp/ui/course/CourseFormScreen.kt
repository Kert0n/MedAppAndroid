package com.kert0n.medapp.ui.course

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.course.Prescription
import com.kert0n.medapp.presentation.course.CourseFormError
import com.kert0n.medapp.presentation.course.CourseFormPresentationDTO
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.presentation.course.suggestingUnit
import com.kert0n.medapp.presentation.course.withForm
import com.kert0n.medapp.ui.DateField
import com.kert0n.medapp.ui.Form
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.PickerField
import com.kert0n.medapp.ui.QuantityField
import com.kert0n.medapp.ui.text
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Редактор лечения (PLAN H3 №15). Обязательно одно — название: черновик с одной заметкой —
 * «записал у врача, куплю завтра» — сохраняется (D5). Остальное — назначение по частям: доза с
 * единицей, форма, первый день, дни недели, времена, всего приёмов; «дата конца» из ТЗ здесь
 * читается строкой «последний приём», а не вводится (C1 «Курс резиновый»).
 *
 * **Кнопка не гаснет.** Погашенная не объясняет, чего не хватает; нажал — форма называет поле и
 * причину, а ввод отказ снимает. Удаление черновика спрашивается до сценария: в нём может лежать
 * единственная запись назначения от врача (H3).
 *
 * Уход с формы тоже спрашивает — но только у черновика, записанного **ради источников**: номер
 * понадобился коробкам, а сохранять его человек не просил. Спрашивают все три выхода — стрелка,
 * «Отмена» и системное «назад», — иначе вопрос обходился бы стороной.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseFormScreen(
    state: CourseFormUiState,
    onEdit: (CourseFormPresentationDTO) -> Unit,
    onSave: () -> Unit,
    onAskToDiscard: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Оставить черновик, записанный ради источников, и уйти. */
    onKeep: () -> Unit = {},
    /** Закрыть вопрос об уходе, оставшись на форме. */
    onDismissLeaving: () -> Unit = {},
    /** Путь к источникам: у нового черновика он сперва записывает набранное (H3 №15). */
    onSources: (() -> Unit)? = null,
    /** Начать лечение; `null` — у идущего лечения начинать уже нечего. */
    onStart: (() -> Unit)? = null
) {
    val editing = state as? CourseFormUiState.Editing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (editing?.mode) {
                                CourseFormUiState.Mode.DRAFT -> R.string.course_edit
                                CourseFormUiState.Mode.RUNNING -> R.string.course_change
                                else -> R.string.course_new
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { if (editing?.mode == CourseFormUiState.Mode.DRAFT) DiscardAction(onAskToDiscard) }
            )
        }
    ) { padding ->
        when (state) {
            CourseFormUiState.Loading -> LoadingState(Modifier.padding(padding))
            // Черновика нет — это отказ, а не пустая форма: заполненную человек сохранил бы и
            // не понял, куда делась его правка.
            CourseFormUiState.Gone -> ErrorMessage(text = stringResource(R.string.course_gone), modifier = Modifier.padding(padding))
            is CourseFormUiState.Editing ->
                Fields(state, onEdit, onSave, onBack, onSources, onStart, Modifier.padding(padding))
        }
    }
    if (editing?.asksToDiscard == true) DiscardDialog(onConfirm = onConfirmDiscard, onDismiss = onDismissDiscard)
    if (editing?.asksToLeave == true) {
        LeavingDialog(onKeep = onKeep, onDiscard = onConfirmDiscard, onDismiss = onDismissLeaving)
    }
    // Системное «назад» — такой же выход, как стрелка: вопрос задаёт форма, а не оболочка.
    BackHandler(enabled = editing?.mode == CourseFormUiState.Mode.UNASKED_DRAFT, onBack = onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Fields(
    state: CourseFormUiState.Editing,
    onEdit: (CourseFormPresentationDTO) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onSources: (() -> Unit)?,
    onStart: (() -> Unit)?,
    modifier: Modifier
) {
    val form = state.form
    val field = state.error?.field
    Form(
        modifier = modifier,
        actions = {
            state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) { Text(stringResource(R.string.action_save)) }
            onStart?.let {
                FilledTonalButton(
                    onClick = it,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                ) { Text(stringResource(R.string.course_start_treatment)) }
            }
            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    ) {
        OutlinedTextField(
            value = form.title,
            onValueChange = { onEdit(form.copy(title = it)) },
            label = { Text(stringResource(R.string.course_title)) },
            singleLine = true,
            isError = field == CourseFormError.Field.TITLE,
            // «обязательно» — пока название пусто: у заполненного поля подпись читается как
            // требование поправить, а поверх своего отказа — как неверная причина.
            supportingText = { if (form.title.isBlank()) Text(stringResource(R.string.field_required)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.note,
            onValueChange = { onEdit(form.copy(note = it)) },
            label = { Text(stringResource(R.string.course_note)) },
            minLines = 2,
            isError = field == CourseFormError.Field.NOTE,
            modifier = Modifier.fillMaxWidth()
        )
        // Форма выпуска — первой: она подсказывает единицу, а обратный порядок ничего не решает.
        PickerField(
            label = stringResource(R.string.course_form),
            selected = state.forms.firstOrNull { it.id == form.form?.id },
            options = state.forms,
            optionText = { it.name },
            onPick = { onEdit(form.withForm(it, state.units)) },
            isError = field == CourseFormError.Field.FORM,
            emptyText = stringResource(R.string.pack_no_forms)
        )
        QuantityField(
            amount = form.doseAmount,
            unit = form.unit,
            units = state.units,
            onAmount = { onEdit(form.copy(doseAmount = it)) },
            onUnit = { onEdit(form.copy(unit = it)) },
            amountLabel = stringResource(R.string.course_dose),
            unitLabel = stringResource(R.string.course_unit),
            amountError = field == CourseFormError.Field.DOSE,
            unitError = field == CourseFormError.Field.UNIT,
            emptyUnits = stringResource(R.string.pack_no_units)
        )
        // Сколько всего приёмов — до источников: делить между коробками нечего, пока не сказано,
        // сколько приёмов делить (просьба владельца 2026-09-16).
        OutlinedTextField(
            value = form.totalDoses,
            onValueChange = { onEdit(form.copy(totalDoses = it)) },
            label = { Text(stringResource(R.string.course_total_doses)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = field == CourseFormError.Field.TOTAL_DOSES,
            supportingText = state.expectedEnd?.let { { Text(stringResource(R.string.course_expected_end, it.format(DAY))) } },
            modifier = Modifier.fillMaxWidth()
        )
        // Источники — сразу за дозой и числом приёмов: раньше них подключать нечего, а дальше в
        // списке полей они бы потерялись (решение владельца 2026-09-16).
        onSources?.let {
            OutlinedButton(
                onClick = it,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) { Text(stringResource(R.string.course_sources_open)) }
        }
        DateField(
            label = stringResource(R.string.course_start),
            value = form.start,
            onPick = { onEdit(form.copy(start = it)) }
        )
        Text(stringResource(R.string.course_days), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (day in DayOfWeek.entries) {
                FilterChip(
                    selected = day in form.days,
                    onClick = { onEdit(form.copy(days = if (day in form.days) form.days - day else form.days + day)) },
                    label = { Text(day.short()) }
                )
            }
        }
        Text(stringResource(R.string.course_times), style = MaterialTheme.typography.labelLarge)
        TimesField(times = form.times, onTimes = { onEdit(form.copy(times = it)) })
    }
}

/**
 * Времена приёма: выбранные — плашками с крестиком, новое — часами в диалоге. Печатать время
 * строкой незачем: выбранное уже время, и состояния «25:70» у него не бывает.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TimesField(times: List<LocalTime>, onTimes: (List<LocalTime>) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (time in times) {
            InputChip(
                selected = false,
                onClick = { onTimes(times - time) },
                label = { Text(time.toString()) },
                trailingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.course_remove_time, time.toString())
                    )
                }
            )
        }
        AssistChip(onClick = { picking = true }, label = { Text(stringResource(R.string.course_add_time)) })
    }
    if (picking) {
        val picker = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(
                    onClick = {
                        picking = false
                        onTimes((times + LocalTime.of(picker.hour, picker.minute)).distinct().sorted())
                    }
                ) { Text(stringResource(R.string.action_choose)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

/**
 * Удаление черновика — значок в панели, как у карточки коробки: за меню прячут **выбор**, а одно
 * действие за ним стоит человеку лишнего нажатия (просьба владельца 2026-09-16). Случайного
 * нажатия бояться нечего — удаление спрашивает (H3, список подтверждений).
 */
@Composable
private fun DiscardAction(onAskToDiscard: () -> Unit) {
    IconButton(onClick = onAskToDiscard) {
        Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.course_discard))
    }
}

/**
 * Уход с записанным черновиком: оставить или удалить. Третий ответ — закрыть вопрос и остаться на
 * форме; удаление стоит вторым, потому что уносит запись врача, а «Оставить» ничего не теряет.
 */
@Composable
private fun LeavingDialog(onKeep: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_leave_title)) },
        text = { Text(stringResource(R.string.course_leave_explained)) },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.course_leave_keep)) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.course_leave_discard)) } }
    )
}

@Composable
private fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_discard_title)) },
        text = { Text(stringResource(R.string.course_discard_explained)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.course_discard)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Слова отказа подбирает экран; предел длины берётся у записи эпизода, а не повторяется числом. */
@Composable
private fun CourseFormError.message(): String = when (this) {
    CourseFormError.Input.TITLE_EMPTY -> stringResource(R.string.course_title_empty)
    CourseFormError.Input.TITLE_TOO_LONG ->
        pluralStringResource(R.plurals.course_title_too_long, CourseRecord.TITLE_MAX_LENGTH, CourseRecord.TITLE_MAX_LENGTH)
    CourseFormError.Input.NOTE_TOO_LONG ->
        pluralStringResource(R.plurals.course_note_too_long, CourseRecord.NOTE_MAX_LENGTH, CourseRecord.NOTE_MAX_LENGTH)
    CourseFormError.Input.UNIT_MISSING -> stringResource(R.string.course_unit_missing)
    CourseFormError.Input.DOSE_IS_ZERO -> stringResource(R.string.course_dose_is_zero)
    CourseFormError.Input.FORM_UNKNOWN -> stringResource(R.string.course_form_unknown)
    CourseFormError.Input.START_MISSING -> stringResource(R.string.course_start_missing)
    CourseFormError.Input.DAYS_EMPTY -> stringResource(R.string.course_days_empty)
    CourseFormError.Input.TIMES_EMPTY -> stringResource(R.string.course_times_empty)
    CourseFormError.Input.TOTAL_DOSES_INVALID -> stringResource(R.string.course_total_doses_invalid)
    is CourseFormError.Dose -> stringResource(error.text)
    is CourseFormError.PackageTaken ->
        name?.let { stringResource(R.string.course_source_taken_named, it) }
            ?: stringResource(R.string.course_source_taken)
    is CourseFormError.PackageUnusable ->
        name?.let { stringResource(R.string.course_source_unusable_named, it) }
            ?: stringResource(R.string.course_source_unusable)
    is CourseFormError.Rejected -> reason.words()
    CourseFormError.Finished -> stringResource(R.string.course_finished)
    CourseFormError.Stale -> stringResource(R.string.course_stale)
}

/**
 * Отказ сценария — словами по месту: чего не хватает, чтобы начать, или что нельзя под пачками.
 * Потолок числа доз называется числом из `Prescription`, а не повторяет его в строке.
 */
@Composable
internal fun CourseRejected.Reason.words(): String = when (this) {
    CourseRejected.Reason.SCHEDULE_MISSING -> stringResource(R.string.course_rejected_schedule_missing)
    CourseRejected.Reason.DOSE_MISSING -> stringResource(R.string.course_rejected_dose_missing)
    CourseRejected.Reason.FORM_MISSING -> stringResource(R.string.course_rejected_form_missing)
    CourseRejected.Reason.TOTAL_DOSES_MISSING -> stringResource(R.string.course_rejected_total_doses_missing)
    CourseRejected.Reason.TOTAL_DOSES_TOO_MANY -> pluralStringResource(
        R.plurals.course_rejected_total_doses_too_many,
        Prescription.MAX_TOTAL_DOSES,
        Prescription.MAX_TOTAL_DOSES
    )
    CourseRejected.Reason.UNIT_MISMATCH -> stringResource(R.string.course_rejected_unit_mismatch)
    CourseRejected.Reason.FORM_MISMATCH -> stringResource(R.string.course_rejected_form_mismatch)
    CourseRejected.Reason.SCHEDULE_IN_PAST -> stringResource(R.string.course_rejected_schedule_in_past)
    CourseRejected.Reason.FORM_UNKNOWN -> stringResource(R.string.course_form_unknown)
    CourseRejected.Reason.ALREADY_ATTACHED -> stringResource(R.string.course_rejected_already_attached)
    CourseRejected.Reason.PACKAGE_UNUSABLE -> stringResource(R.string.course_rejected_package_unusable)
}
