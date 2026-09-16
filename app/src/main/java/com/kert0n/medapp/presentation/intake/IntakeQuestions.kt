package com.kert0n.medapp.presentation.intake

import com.kert0n.medapp.feature.intake.IntakeWarning
import com.kert0n.medapp.presentation.value.toPresentationDTO

/**
 * Вопрос сценария — словами экрана: последний годный день коробки, свободное — величиной.
 *
 * Место одно на оба экрана приёма: спрашивает не экран, а сценарий, и спрашивает он об одном и том
 * же, принимают ли дозу по плану или мимо него (PLAN D6).
 */
fun IntakeWarning.toPresentationDTO(): IntakeQuestionPresentationDTO = when (this) {
    is IntakeWarning.Expired -> IntakeQuestionPresentationDTO.Expired(expiresOn.lastDay)
    is IntakeWarning.TouchesReserved -> IntakeQuestionPresentationDTO.TouchesReserved(free.toPresentationDTO())
}
