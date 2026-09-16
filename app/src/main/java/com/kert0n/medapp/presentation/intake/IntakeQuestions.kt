package com.kert0n.medapp.presentation.intake

import com.kert0n.medapp.feature.intake.IntakeWarning
import com.kert0n.medapp.presentation.value.toPresentationDTO

/**
 * Вопрос сценария — словами экрана: свободное — величиной (PLAN D6). Спрашивает только разовый
 * приём — о занятом; просрочка не вопрос (PLAN C1 «Просроченная пачка»).
 */
fun IntakeWarning.toPresentationDTO(): IntakeQuestionPresentationDTO = when (this) {
    is IntakeWarning.TouchesReserved -> IntakeQuestionPresentationDTO.TouchesReserved(free.toPresentationDTO())
}
