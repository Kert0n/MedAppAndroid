package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.feature.intake.IntakeConfirmation
import com.kert0n.medapp.feature.intake.IntakeDeclining
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Ответ на напоминание из шторки — тем же путём, что с экрана (PLAN D8, C1): «Принял» —
 * `IntakeConfirmation` с тем, что записано в пункте; «Пропустить» — `IntakeDeclining`; «Отложить» —
 * сдвиг срока обязательства на `snoozeMinutes`. Приложение не открывается ни в одном случае.
 */
class ReminderAnswering @Inject constructor(
    private val declining: IntakeDeclining,
    private val confirmation: IntakeConfirmation,
    private val intakes: IntakeStorageRepository,
    private val promising: ReminderPromising,
    private val reminders: ReminderStorageRepository,
    private val withdrawal: ReminderWithdrawal,
    private val settings: NotificationSettingsSource,
    private val transactions: Transactions,
    private val clock: Clock
) {

    /**
     * «Принял» из шторки — **без экрана** (PLAN C1, поправка владельца 2026-09-16): принято то, что
     * записано в пункте, — плановая пачка и доза, — в момент нажатия, тем же сценарием, что быстрый
     * ответ на «Дне». Открыть приложение, ответить за человека и закрыть его выглядело как «ничего
     * не произошло».
     *
     * Не вышло — не пишется ничего, и молча это не проходит: заводится обязательство «нужно ваше
     * решение», нажатие на него ведёт на карточку пункта. Курс закрыт или пункта нет — решать нечего.
     *
     * Всё — **одной транзакцией** (F5, C1 «Ответ из шторки — одной транзакцией»): «не записалось»
     * верно только там, где прочитано. Отказ с карточки, пришедший между отказом записи и решением,
     * снял бы обязательства, а решение легло бы после и висело над отвеченным пунктом.
     */
    suspend fun take(intakeId: Uuid): Response = transactions.run {
        val intake = intakes.find(intakeId) as? CourseIntake ?: return@run Response.Done
        val planned = intake.plannedPackage
        val outcome = planned?.let { confirmation.confirm(intakeId, it.id, intake.plannedAmount, clock.instant()) }
        when {
            outcome is IntakeConfirmation.Outcome.Confirmed || outcome == IntakeConfirmation.Outcome.Gone -> Response.Done
            outcome is IntakeConfirmation.Outcome.Rejected && outcome.reason == IntakeRejected.Reason.EPISODE_CLOSED -> {
                withdrawal.withdraw(intakeId)
                Response.Done
            }
            else -> {
                promising.promise(listOf(Reminder(NotificationKey.intake(intakeId, NotificationKind.INTAKE_DECISION), NotificationTarget.Intake(intakeId), clock.instant())))
                Response.NeedsDecision
            }
        }
    }

    /** Отказ человека: пункт становится пропуском, и напоминать о нём больше нечего. */
    suspend fun skip(intakeId: Uuid): Response = transactions.run {
        when (declining.decline(intakeId, clock.instant())) {
            IntakeDeclining.Outcome.DECLINED, IntakeDeclining.Outcome.ALREADY_ANSWERED -> Response.Done
            // Курса или пункта больше нет — напоминать не о чем, и говорить человеку нечего.
            IntakeDeclining.Outcome.EPISODE_CLOSED, IntakeDeclining.Outcome.GONE -> {
                withdrawal.withdraw(intakeId)
                Response.Done
            }
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

    /** Чем кончилось для шторки: карточка гасится либо отложена до названного момента. */
    sealed interface Response {
        data object Done : Response
        data class Snoozed(val at: Instant) : Response

        /** «Принял» не записал: пришло «нужно ваше решение», и решает человек на карточке. */
        data object NeedsDecision : Response
    }
}
