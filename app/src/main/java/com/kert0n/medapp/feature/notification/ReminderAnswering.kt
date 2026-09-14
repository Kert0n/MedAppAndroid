package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.ReminderAlarms
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.intake.IntakeDeclining
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Ответ на напоминание из шторки — тем же путём, что с экрана (PLAN D8, C1): «Принял» —
 * `IntakeConfirmation` плановой пачкой и плановой дозой, «Пропустить» — `IntakeDeclining`,
 * «Отложить» — тот же будильник через `snoozeMinutes`. Вопрос и отказ из шторки не обходятся:
 * человеку открывается приложение, и решает он там.
 */
class ReminderAnswering @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val confirmation: IntakeConfirmation,
    private val declining: IntakeDeclining,
    private val alarms: ReminderAlarms,
    private val settings: NotificationSettingsSource,
    private val clock: Clock
) {

    suspend fun take(intakeId: Uuid): Response {
        val intake = intakes.find(intakeId) as? CourseIntake ?: return Response.OpenApp
        val pkg = intake.plannedPackage ?: return Response.OpenApp
        return when (confirmation.confirm(intakeId, pkg.id, intake.plannedAmount, clock.instant())) {
            is IntakeConfirmation.Outcome.Confirmed -> Response.Done
            is IntakeConfirmation.Outcome.Warned, is IntakeConfirmation.Outcome.Rejected -> Response.OpenApp
        }
    }

    suspend fun skip(intakeId: Uuid): Response = when (declining.decline(intakeId, clock.instant())) {
        IntakeDeclining.Outcome.DECLINED, IntakeDeclining.Outcome.ALREADY_ANSWERED -> Response.Done
        IntakeDeclining.Outcome.EPISODE_CLOSED, IntakeDeclining.Outcome.GONE -> Response.OpenApp
    }

    /** Сдвигается только будильник — не `plannedAt` и не граница `MISSED` (PLAN D8). */
    suspend fun snooze(intakeId: Uuid): Response {
        val at = clock.instant().plus(Duration.ofMinutes(settings.current().snoozeMinutes.toLong()))
        alarms.schedule(NotificationKey.intake(intakeId, NotificationKind.INTAKE_DUE), at)
        return Response.Snoozed(at)
    }

    /** Чем кончилось для шторки: уведомление гасится, отложено до момента, либо открывается приложение. */
    sealed interface Response {
        data object Done : Response
        data class Snoozed(val at: Instant) : Response
        data object OpenApp : Response
    }
}
