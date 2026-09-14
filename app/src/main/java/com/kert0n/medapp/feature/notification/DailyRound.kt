package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.feature.course.CourseUpkeep
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import java.time.Clock
import javax.inject.Inject

/**
 * Проход дня (PLAN D8): календарь в порядке, и обязательства сверены — заведено всё, что наступило
 * сегодня, снято всё, у чего исчез повод. Показывать и будить проход не умеет: это дело
 * [ReminderOutbox], и он проснётся сам — сигнал о изменившейся таблице приходит после коммита.
 *
 * Зовут проход ежедневная задача, загрузка устройства и вход в приложение; повтор безопасен:
 * обещанное заводится один раз, а сказанное второй раз не говорится.
 */
class DailyRound @Inject constructor(
    private val upkeep: CourseUpkeep,
    private val planning: NotificationPlanning,
    private val reminders: ReminderStorageRepository,
    private val clock: Clock
) {

    suspend fun run(): Report {
        val now = clock.instant()
        val today = now.atZone(clock.zone).toLocalDate()
        val kept = upkeep.keepUp()
        val due = planning.remindersDue(now)
        val events = planning.expiryDue(today, now) +
            planning.coverageDue(now) +
            planning.missed(kept.missedIntakes, now)
        val digest = planning.digest(today, clock.zone, events.size, now)
        val raised = due + events + listOfNotNull(digest)
        reminders.raiseAll(raised)
        return Report(missed = kept.missed, reminders = due.size, raised = raised.size)
    }

    /** Сколько пунктов пропущено, сколько напоминаний в окне и сколько обязательств заведено. */
    data class Report(val missed: Int, val reminders: Int, val raised: Int)
}
