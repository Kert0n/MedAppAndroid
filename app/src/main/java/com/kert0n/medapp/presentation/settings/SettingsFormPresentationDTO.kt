package com.kert0n.medapp.presentation.settings

import com.kert0n.medapp.feature.settings.SyncIntervalStep
import java.time.LocalTime

/**
 * Что набрано в форме настроек (PLAN H3 №27). Минуты и дни — строки: пока человек печатает,
 * пустое поле и «-» — законные промежуточные состояния, и домен о них знать не должен (PLAN H1).
 * Переключатели, время и интервал промежуточных состояний не имеют и лежат готовыми; интервал —
 * минутами одной из ступеней [SYNC_INTERVAL_STEPS], потому что экран величины очереди не видит.
 */
data class SettingsFormPresentationDTO(
    val intakeReminders: Boolean = true,
    val snoozeMinutes: String = "",
    val expirySourceReminders: Boolean = true,
    val coverageThresholdDays: String = "",
    val digest: Boolean = true,
    val digestAt: LocalTime = LocalTime.of(9, 0),
    val remoteChange: Boolean = true,
    val syncIntervalMinutes: Long = SyncIntervalStep.HOUR.minutes
) {
    companion object {
        /** Из чего выбирает человек — те же ступени, что у сценария, словами экрана. */
        val SYNC_INTERVAL_STEPS: List<Long> = SyncIntervalStep.entries.map { it.minutes }
    }
}
