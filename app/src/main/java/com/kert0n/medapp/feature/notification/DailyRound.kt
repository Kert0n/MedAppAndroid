package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.feature.course.CourseUpkeep
import java.time.Clock
import javax.inject.Inject

/**
 * Проход дня (PLAN D8): календарь в порядке, будильники на ближайшие 36 часов переставлены,
 * сказано всё, что наступило сегодня — сроки, нехватка, пропуски, — и сводка, если есть о чём.
 * Зовут его ежедневная задача, загрузка устройства и вход в приложение; повтор безопасен:
 * показанное журнал не повторяет, будильники переставляются на то же.
 */
class DailyRound @Inject constructor(
    private val upkeep: CourseUpkeep,
    private val planning: NotificationPlanning,
    private val delivery: NotificationDelivery,
    private val clock: Clock
) {

    suspend fun run(): Report {
        val now = clock.instant()
        val today = now.atZone(clock.zone).toLocalDate()
        val kept = upkeep.keepUp()
        val reminders = planning.remindersDue(now)
        delivery.arm(reminders)
        val events = planning.expiryDue(today, now).filter { it.delivery == NoticeDelivery.SYSTEM } +
            planning.coverageDue(now) +
            planning.missed(kept.missedIntakes, now)
        var shown = delivery.deliver(events)
        planning.digest(today, clock.zone, events.size, now)?.let { shown += delivery.deliver(listOf(it)) }
        return Report(missed = kept.missed, reminders = reminders.size, shown = shown)
    }

    data class Report(val missed: Int, val reminders: Int, val shown: Int)
}
