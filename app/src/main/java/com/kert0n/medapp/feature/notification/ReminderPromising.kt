package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationSettingsSource
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.notification.ReminderStorageRepository
import javax.inject.Inject

/**
 * Обещать сказать (PLAN D8). Узкий шаг для всех, кто заводит обязательства: календарь — на свои
 * пункты, сверка — на то, что следует из состояния.
 *
 * Правило одно, и живёт оно здесь, а не у каждого вызывающего:
 *
 * - чего не обещали — заводим;
 * - что уже обещано — **не трогаем**: иначе плановый срок лёг бы поверх отложенного человеком;
 * - что отозвали, но так и не сказали, — **воскрешаем**: повод вернулся раньше, чем мы успели
 *   забыть, и гасить его непоказанным было бы потерей;
 * - о приёме при выключенных напоминаниях — **не обещаем**, откуда бы ни пришло: человек просил
 *   молчать, и пункт нового курса не должен напоминать до ближайшей сверки. Снимать обещанное
 *   ранее — дело сверки, у неё оба направления (PLAN D8).
 *
 * Читает и пишет **одной транзакцией**: прочитанное действительно ровно в её пределах (F5).
 * Вложенность безопасна, поэтому шаг верен и внутри транзакции того, кто завёл повод, — и тогда
 * обязательство откатывается вместе с ним.
 */
class ReminderPromising @Inject constructor(
    private val reminders: ReminderStorageRepository,
    private val settings: NotificationSettingsSource,
    private val transactions: Transactions
) {

    suspend fun promise(reminders: Collection<Reminder>) {
        val wanted = allowed(reminders)
        if (wanted.isEmpty()) return
        transactions.run {
            val known = this.reminders.findAll(wanted.map { it.key }).associateBy { it.key }
            this.reminders.saveAll(
                wanted.mapNotNull { fresh ->
                    val existing = known[fresh.key] ?: return@mapNotNull fresh
                    existing.takeIf { it.revivable() }?.apply { reviveAt(fresh.dueAt) }
                }
            )
        }
    }

    /** Настройки спрашиваются, только когда среди желаемого есть напоминание о приёме. */
    private suspend fun allowed(wanted: Collection<Reminder>): Collection<Reminder> {
        if (wanted.none { it.kind == NotificationKind.INTAKE_DUE }) return wanted
        if (settings.current().intakeRemindersEnabled) return wanted
        return wanted.filterNot { it.kind == NotificationKind.INTAKE_DUE }
    }
}
