package com.kert0n.medapp.presentation.intake

import com.kert0n.medapp.feature.intake.IntakeWarning
import com.kert0n.medapp.presentation.value.toPresentationDTO

/**
 * Вопрос сценария — словами экрана: свободное — величиной, срок — как на коробке (PLAN D6).
 */
fun IntakeWarning.toPresentationDTO(): IntakeQuestionPresentationDTO = when (this) {
    is IntakeWarning.Expired -> IntakeQuestionPresentationDTO.Expired(name, expiry.toPresentationDTO())
    is IntakeWarning.TouchesReserved -> IntakeQuestionPresentationDTO.TouchesReserved(free.toPresentationDTO())
}
