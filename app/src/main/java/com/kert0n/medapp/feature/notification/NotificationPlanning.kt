package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationAction
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.PlannedNotification
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Что должно быть сказано человеку — сборка по всем агрегатам (PLAN D8): курс отвечает за свои
 * пункты, коробка — за срок, обеспечение — за нехватку, а «спросить каждого и сложить» — работа
 * сценария (H1). Здесь только вычисление: показ и журнал — у [NotificationDelivery].
 */
class NotificationPlanning @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val settings: NotificationSettingsSource
) {

    /**
     * Напоминания о приёмах на ближайшие [REMINDER_HORIZON]: будильники ставятся на них, а не на
     * всё 60-дневное окно календаря (C1). Выключенные напоминания — пустой список.
     */
    suspend fun remindersDue(now: Instant): List<PlannedNotification> {
        if (!settings.current().intakeRemindersEnabled) return emptyList()
        return intakes.plannedBefore(now.plus(REMINDER_HORIZON))
            .filter { !it.plannedAt.isBefore(now.minus(GRACE)) }
            .map { reminder(it) }
    }

    /** Напоминание об одном пункте — когда сработал его будильник; пункт уже отвечен — `null`. */
    suspend fun reminderFor(intakeId: Uuid): PlannedNotification? {
        if (!settings.current().intakeRemindersEnabled) return null
        val intake = intakes.find(intakeId) as? CourseIntake ?: return null
        if (intake.status != IntakeStatus.PLANNED) return null
        return reminder(intake)
    }

    private fun reminder(intake: CourseIntake) = PlannedNotification(
        key = NotificationKey.intake(intake.id, NotificationKind.INTAKE_DUE),
        dueAt = intake.plannedAt,
        target = NotificationTarget.Intake(intake.id),
        delivery = NoticeDelivery.SYSTEM,
        actions = listOf(NotificationAction.TAKE, NotificationAction.SKIP, NotificationAction.SNOOZE)
    )

    companion object {
        /** На сколько вперёд стоят будильники; перестраиваются ежедневным проходом, входом и загрузкой (C1). */
        val REMINDER_HORIZON: Duration = Duration.ofHours(36)

        /** Пункт, начавшийся только что, ещё напоминает; вчерашний неотвеченный — уже нет: о нём скажет сводка. */
        val GRACE: Duration = Duration.ofMinutes(30)
    }
}
