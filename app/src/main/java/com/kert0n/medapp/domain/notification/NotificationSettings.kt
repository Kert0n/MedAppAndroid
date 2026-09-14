package com.kert0n.medapp.domain.notification

import java.time.LocalTime

/**
 * Что человек решил об уведомлениях (PLAN D8). Умолчания — здесь; где они хранятся и как
 * меняются — забота платформы за портом [NotificationSettingsSource].
 */
data class NotificationSettings(
    val intakeRemindersEnabled: Boolean = true,
    val snoozeMinutes: Int = 15,
    val expirySourceRemindersEnabled: Boolean = true,
    val coverageThresholdDays: Long = 3,
    val digestEnabled: Boolean = true,
    val digestAt: LocalTime = LocalTime.of(9, 0),
    val remoteChangeEnabled: Boolean = true
) {
    init {
        require(snoozeMinutes > 0) { "отложить можно только вперёд" }
        require(coverageThresholdDays >= 0) { "порог обеспечения не бывает отрицательным" }
    }

    companion object {
        val DEFAULT = NotificationSettings()
    }
}

/** Откуда берутся настройки уведомлений: домен спрашивает, платформа отвечает. */
interface NotificationSettingsSource {
    suspend fun current(): NotificationSettings
}
