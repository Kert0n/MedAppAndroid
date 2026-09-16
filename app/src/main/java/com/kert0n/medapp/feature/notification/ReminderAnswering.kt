package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.Reminder
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
    private val transactions: com.kert0n.medapp.queue.Transactions,
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
     *
     * Откладывать нечего — обязательства нет или его сняли — отвечаем [Response.Done]: называть
     * срок, которого не записали, значит соврать человеку и оставить карточку висеть.
     */
    suspend fun snooze(intakeId: Uuid): Response {
        val at = clock.instant().plus(Duration.ofMinutes(settings.current().snoozeMinutes.toLong()))
        val key = NotificationKey.intake(intakeId, NotificationKind.INTAKE_DUE)
        // Читаем и пишем одной транзакцией: пока человек жал кнопку, лечение могли отменить, и
        // отсрочка, положенная поверх снятого, воскресила бы его (F5).
        val deferred = transactions.run {
            val reminder = reminders.find(key)?.takeIf { it.state != Reminder.State.WITHDRAWN }
            reminder?.also {
                it.defer(at)
                reminders.saveAll(listOf(it))
            } != null
        }
        // Обещания больше нет или его сняли — откладывать нечего, и называть срок человеку незачем:
        // карточка просто уходит.
        return if (deferred) Response.Snoozed(at) else Response.Done
    }

    /**
     * «Понятно» у пропуска на полке дня (PLAN C1 «Полка»): человек прочёл, что доза пропущена, и
     * напоминать о пропуске больше незачем. Снимается только обещание сказать о пропуске — пункт
     * остаётся пропуском, и «Принял» за него по-прежнему законен.
     */
    suspend fun acknowledge(intakeId: Uuid) {
        withdrawal.withdrawKeys(listOf(NotificationKey.intake(intakeId, NotificationKind.INTAKE_MISSED)))
    }

    /** Чем кончилось для шторки: карточка гасится либо отложена до названного момента. */
    sealed interface Response {
        data object Done : Response
        data class Snoozed(val at: Instant) : Response
    }
}
