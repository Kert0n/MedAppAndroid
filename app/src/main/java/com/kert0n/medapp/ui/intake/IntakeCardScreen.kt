package com.kert0n.medapp.ui.intake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.intake.IntakeCardError
import com.kert0n.medapp.presentation.intake.IntakeCardPresentationDTO
import com.kert0n.medapp.presentation.intake.IntakeCardUiState
import com.kert0n.medapp.presentation.intake.IntakeQuestionPresentationDTO
import com.kert0n.medapp.ui.PickerField
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.DateField
import com.kert0n.medapp.ui.ErrorMessage
import com.kert0n.medapp.ui.Form
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.TIME
import com.kert0n.medapp.ui.text
import com.kert0n.medapp.ui.words
import java.time.LocalTime

/**
 * Карточка пункта плана (PLAN H3 №18). Быстрый ответ живёт в строке дня; сюда человек приходит,
 * когда хочет иначе — другой дозой, другим моментом — и когда сценарий о чём-то спросил.
 *
 * Отвеченный пункт карточка **показывает**, а не спрашивает: ответ на приём даётся один раз, и
 * второй кнопки для него нет. Пропущенный — исключение: доза уехала вперёд, и подтвердить её
 * позже законно (D6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeCardScreen(
    state: IntakeCardUiState,
    onEdit: (IntakeCardPresentationDTO) -> Unit,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onAcknowledge: () -> Unit,
    onDismissQuestions: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifEmpty { stringResource(R.string.intake_card_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> LoadingState(Modifier.fillMaxSize())
                // Пункта больше нет — сказано словами: пустая карточка читается как поломка.
                state.isGone -> ErrorMessage(stringResource(R.string.intake_card_gone), Modifier.fillMaxSize())
                else -> Card(state, onEdit, onConfirm, onDecline, onBack)
            }
        }
    }
    if (state.questions.isNotEmpty()) {
        Questions(state.questions, onAcknowledge = onAcknowledge, onDismiss = onDismissQuestions)
    }
}

@Composable
private fun Card(
    state: IntakeCardUiState,
    onEdit: (IntakeCardPresentationDTO) -> Unit,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onBack: () -> Unit
) = Form(
    actions = {
        state.error?.let { Text(it.words(), color = MaterialTheme.colorScheme.error) }
        if (state.canAnswer) {
            Button(onClick = onConfirm, enabled = !state.isWriting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.intake_confirm))
            }
            // Отказ — решение, а не молчание: лечение считает эту дозу пропущенной (PLAN D6).
            TextButton(onClick = onDecline, enabled = !state.isWriting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.intake_decline))
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    },
    fields = {
        // Что назначено: по этому человек и узнаёт пункт, к которому пришёл.
        Text(state.plannedWords(), style = MaterialTheme.typography.bodyLarge)
        state.answer?.let { Text(state.answerWords(it), color = MaterialTheme.colorScheme.primary) }

        // Из какой коробки взяли: любая из источников лечения законна, а чужая пункт не закрывает
        // и здесь не предлагается (H3 №18, PLAN D5). У отвеченного пункта выбирать уже нечего —
        // он говорит, откуда взяли на самом деле.
        if (state.canAnswer) {
            PickerField(
                label = stringResource(R.string.intake_source),
                selected = state.sources.firstOrNull { it.id == state.packageId },
                options = state.sources,
                optionText = { it.name },
                onPick = { onEdit(state.form.copy(packageId = it.id)) },
                emptyText = stringResource(R.string.intake_no_sources)
            )
        } else {
            state.packageName?.let { Text(stringResource(R.string.intake_from_package, it)) }
        }

        OutlinedTextField(
            value = state.form.amount,
            onValueChange = { onEdit(state.form.copy(amount = it)) },
            label = { Text(stringResource(R.string.intake_amount)) },
            supportingText = state.unit?.let { { Text(it.name) } },
            isError = state.error is IntakeCardError.Amount,
            enabled = state.canAnswer,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DateField(
            label = stringResource(R.string.intake_taken_on),
            value = state.form.on,
            onPick = { onEdit(state.form.copy(on = it)) }
        )
        TimeField(
            label = stringResource(R.string.intake_taken_at),
            value = state.form.at,
            onPick = { onEdit(state.form.copy(at = it)) }
        )
    }
)

/**
 * Время приёма — часами в диалоге, а не строкой: выбранное уже время, и состояния «25:70» у него
 * не бывает (тем же доводом, что времена расписания в форме лечения).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: LocalTime?, onPick: (LocalTime) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value?.format(TIME).orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { picking = true }) { Text(stringResource(R.string.intake_pick_time)) }
    if (picking) {
        val picker = rememberTimePickerState(
            initialHour = value?.hour ?: 9,
            initialMinute = value?.minute ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(
                    onClick = {
                        picking = false
                        onPick(LocalTime.of(picker.hour, picker.minute))
                    }
                ) { Text(stringResource(R.string.action_choose)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

/**
 * Вопросы — **одним** диалогом со списком: человек отвечает на всё разом, потому что решение у
 * него одно — принимать или нет (H3 №18). Отмена не пишет ничего.
 */
@Composable
private fun Questions(
    questions: List<IntakeQuestionPresentationDTO>,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.intake_questions_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                questions.forEach { Text("• " + it.words()) }
            }
        },
        confirmButton = { TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.intake_confirm_anyway)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun IntakeQuestionPresentationDTO.words(): String = when (this) {
    is IntakeQuestionPresentationDTO.Expired -> stringResource(R.string.intake_question_expired, DAY.format(on))
    is IntakeQuestionPresentationDTO.TouchesReserved ->
        stringResource(R.string.intake_question_touches_reserved, free.words())
}

/** Что назначено на этот пункт: день, время и доза — одной строкой. */
@Composable
private fun IntakeCardUiState.plannedWords(): String = stringResource(
    R.string.intake_planned,
    plannedOn?.format(DAY).orEmpty(),
    plannedAt?.format(TIME).orEmpty(),
    plannedAmount?.words().orEmpty()
)

@Composable
private fun IntakeCardUiState.answerWords(answer: IntakeCardUiState.Answer): String = when (answer) {
    IntakeCardUiState.Answer.TAKEN -> stringResource(R.string.intake_state_taken, answeredAt?.format(TIME).orEmpty())
    IntakeCardUiState.Answer.MISSED -> stringResource(R.string.intake_state_missed)
    IntakeCardUiState.Answer.CANCELLED -> stringResource(R.string.intake_state_cancelled)
}

@Composable
private fun IntakeCardError.words(): String = when (this) {
    is IntakeCardError.Amount -> stringResource(error.text)
    is IntakeCardError.Rejected -> stringResource(reason.text)
}
