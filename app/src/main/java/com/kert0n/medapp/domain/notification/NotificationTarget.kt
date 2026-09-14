package com.kert0n.medapp.domain.notification

import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Куда ведёт уведомление. Только идентификаторы и дата: в `PendingIntent` и маршруты едет то же,
 * что и в навигацию, — ни объектов, ни ключей (PLAN G3, H3).
 */
sealed interface NotificationTarget {
    data class Intake(val intakeId: Uuid) : NotificationTarget
    data class PackageCard(val packageId: Uuid) : NotificationTarget
    data class CourseSources(val courseId: Uuid) : NotificationTarget
    data class DayPlan(val date: LocalDate) : NotificationTarget

    /** Экран состояния синхронизации (H3 №28): данных не несёт — очередь одна. */
    data object SyncStatus : NotificationTarget
}
