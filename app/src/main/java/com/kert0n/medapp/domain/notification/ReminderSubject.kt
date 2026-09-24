package com.kert0n.medapp.domain.notification

import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Dose
import java.time.LocalDate

/**
 * О чём говорит обязательство в миг показа — то, что читается по его цели и из чего шторка
 * собирает текст (PLAN D8). Не нашлось — повода больше нет, и показывать нечего
 * ([Delivery.SUBJECT_GONE]).
 */
sealed interface ReminderSubject {

    data class Intake(val course: String, val dose: Dose, val pack: String?) : ReminderSubject

    data class Expiry(val pack: String, val expiresOn: ExpiryDate) : ReminderSubject

    data class Coverage(val course: String, val coveredUntil: LocalDate?) : ReminderSubject

    data class DayPlan(val date: LocalDate) : ReminderSubject

    data object SyncStatus : ReminderSubject
}
