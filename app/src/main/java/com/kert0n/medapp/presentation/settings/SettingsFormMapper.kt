package com.kert0n.medapp.presentation.settings

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.feature.settings.AppSettings
import com.kert0n.medapp.feature.settings.SyncIntervalStep
import com.kert0n.medapp.presentation.ParsedInput

/**
 * Разбор формы настроек: строки экрана — в величину, которую примет сценарий.
 *
 * Числа разбираются здесь, а правила о них — «отложить можно только вперёд», «порог не бывает
 * отрицательным» — остаются у [NotificationSettings]: разбор не повторяет `require`, а спрашивает
 * тип, приняв бы он это число, и называет поле по ответу. Интервал обмена вопросов не задаёт:
 * экран отдаёт минуты одной из ступеней, и ступень строит величину сама.
 */
fun SettingsFormPresentationDTO.parsed(): ParsedInput<AppSettings, SettingsFormError.Input> {
    val snooze = snoozeMinutes.trim().toIntOrNull()
        ?: return ParsedInput.Rejected(SettingsFormError.Input.SNOOZE_NOT_A_NUMBER)
    val threshold = coverageThresholdDays.trim().toLongOrNull()
        ?: return ParsedInput.Rejected(SettingsFormError.Input.THRESHOLD_NOT_A_NUMBER)
    if (attempt { NotificationSettings(snoozeMinutes = snooze) }.isFailure) {
        return ParsedInput.Rejected(SettingsFormError.Input.SNOOZE_NOT_FORWARD)
    }
    if (attempt { NotificationSettings(coverageThresholdDays = threshold) }.isFailure) {
        return ParsedInput.Rejected(SettingsFormError.Input.THRESHOLD_NEGATIVE)
    }
    return ParsedInput.Parsed(
        AppSettings(
            notifications = NotificationSettings(
                intakeRemindersEnabled = intakeReminders,
                snoozeMinutes = snooze,
                expirySourceRemindersEnabled = expirySourceReminders,
                coverageThresholdDays = threshold,
                digestEnabled = digest,
                digestAt = digestAt,
                remoteChangeEnabled = remoteChange
            ),
            syncInterval = SyncIntervalStep.entries.first { it.minutes == syncIntervalMinutes }.interval
        )
    )
}

/** Записанное — обратно в форму: человек правит то, что действует. */
fun AppSettings.toFormPresentationDTO(): SettingsFormPresentationDTO = SettingsFormPresentationDTO(
    intakeReminders = notifications.intakeRemindersEnabled,
    snoozeMinutes = notifications.snoozeMinutes.toString(),
    expirySourceReminders = notifications.expirySourceRemindersEnabled,
    coverageThresholdDays = notifications.coverageThresholdDays.toString(),
    digest = notifications.digestEnabled,
    digestAt = notifications.digestAt,
    remoteChange = notifications.remoteChangeEnabled,
    syncIntervalMinutes = SyncIntervalStep.closestTo(syncInterval).minutes
)
