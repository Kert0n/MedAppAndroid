package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.feature.intake.IntakeDeclining
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Ответ на напоминание из шторки — тем же путём, что с экрана (PLAN D8, C1): «Пропустить» —
 * `IntakeDeclining`, «Отложить» — сдвиг срока обязательства на `snoozeMinutes`.
 *
 * «Принял» здесь нет. Он требует экрана всякий раз, когда есть о чём предупредить — просрочка,
 * отменённый курс, затронутые брони, — а запустить экран из приёмника уведомления платформа с
 * Android 12 не даёт. Записывать молча, не показав предупреждения, нельзя: предупреждение
 * действием из шторки не обходится. Кнопка вернётся вместе с экраном в U5.
 */
class ReminderAnswering @Inject constructor(
    private val declining: IntakeDeclining,
    private val reminders: ReminderStorageRepository,
    private val withdrawal: ReminderWithdrawal,
    private val settings: NotificationSettingsSource,
    private val clock: Clock
) {

    /** Отказ человека: пункт становится пропуском, и напоминать о нём больше нечего. */
    suspend fun skip(intakeId: Uuid): Response = when (declining.decline(intakeId, clock.instant())) {
        IntakeDeclining.Outcome.DECLINED, IntakeDeclining.Outcome.ALREADY_ANSWERED -> Response.Done
        // Курса или пункта больше нет — напоминать не о чем, и говорить человеку нечего.
        IntakeDeclining.Outcome.EPISODE_CLOSED, IntakeDeclining.Outcome.GONE -> {
            withdrawal.withdraw(intakeId)
            Response.Done
        }
    }

    /**
     * Сдвигается срок **обязательства** — не `plannedAt` и не граница `MISSED` (PLAN D8). Сдвиг
     * лежит в таблице, поэтому переживает и проход дня, и перезагрузку: будильник — исполнитель
     * сохранённого срока, а не его хранилище.
     */
    suspend fun snooze(intakeId: Uuid): Response {
        val at = clock.instant().plus(Duration.ofMinutes(settings.current().snoozeMinutes.toLong()))
        val key = NotificationKey.intake(intakeId, NotificationKind.INTAKE_DUE)
        reminders.find(key)?.let { reminder ->
            reminder.defer(at)
            reminders.saveAll(listOf(reminder))
        }
        return Response.Snoozed(at)
    }

    /** Чем кончилось для шторки: карточка гасится либо отложена до названного момента. */
    sealed interface Response {
        data object Done : Response
        data class Snoozed(val at: Instant) : Response
    }
}
