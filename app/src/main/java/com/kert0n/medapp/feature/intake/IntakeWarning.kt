package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Quantity

/**
 * Вопрос, который сценарий приёма задаёт человеку **до** записи: ничего не записано, пока он не
 * ответит, а ответ — тот же вызов с `acknowledged = true` (PLAN D6). Вопросов может быть несколько,
 * и задаются они разом. Отказ, который подтверждением не снимается, — не вопрос, а `Rejected`.
 */
sealed interface IntakeWarning {

    /**
     * Коробка [name] просрочена к дню приёма: годна была до [expiry]. Приложение не запрещает —
     * решает человек, — но и молча не пишет (ТЗ 4.1.1.5.5, решение владельца 2026-09-23).
     */
    data class Expired(val name: String, val expiry: ExpiryDate) : IntakeWarning

    /** Приём больше свободного любому — заденет моё выделение или чужие брони (PLAN D4). */
    data class TouchesReserved(val free: Quantity) : IntakeWarning
}
