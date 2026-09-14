package com.kert0n.medapp.domain.notification

import java.time.Instant

/**
 * Будильник к моменту события — действие, которое исполняет система (PLAN D8). Точно — если она
 * разрешила; иначе примерно, и [canBeExact] говорит об этом честно.
 */
interface ReminderAlarms {

    val canBeExact: Boolean

    suspend fun schedule(key: NotificationKey, at: Instant)

    suspend fun cancel(key: NotificationKey)
}
