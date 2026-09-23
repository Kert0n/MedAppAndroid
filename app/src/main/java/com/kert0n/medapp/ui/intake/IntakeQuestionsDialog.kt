package com.kert0n.medapp.ui.intake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.intake.IntakeQuestionPresentationDTO
import com.kert0n.medapp.ui.words

/**
 * Вопросы перед записью приёма — **одним** диалогом со списком: решение у человека одно, принимать
 * или нет, и спрашивать по разу за каждую беду значило бы спрашивать одно и то же (PLAN H3 №18).
 *
 * Диалог один на оба экрана приёма: спрашивает не экран, а сценарий, и спрашивает он об одном и том
 * же, по плану принимают дозу или мимо него. «Отмена» не пишет ничего.
 */
@Composable
fun IntakeQuestionsDialog(
    questions: List<IntakeQuestionPresentationDTO>,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.intake_questions_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                questions.forEach { Text("\u2022 " + it.words()) }
            }
        },
        confirmButton = { TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.intake_confirm_anyway)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun IntakeQuestionPresentationDTO.words(): String = when (this) {
    is IntakeQuestionPresentationDTO.Expired -> stringResource(R.string.intake_question_expired, name, expiry.text)
    is IntakeQuestionPresentationDTO.TouchesReserved ->
        stringResource(R.string.intake_question_touches_reserved, free.words())
}
