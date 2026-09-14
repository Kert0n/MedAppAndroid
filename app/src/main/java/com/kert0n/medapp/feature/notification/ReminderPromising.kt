package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.Reminder
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
 *   забыть, и гасить его непоказанным было бы потерей.
 *
 * Зовётся внутри транзакции, которая завела повод: обязательство откатывается вместе с ним (F5).
 */
class ReminderPromising @Inject constructor(
    private val reminders: ReminderStorageRepository
) {

    suspend fun promise(wanted: Collection<Reminder>) {
        if (wanted.isEmpty()) return
        val known = reminders.findAll(wanted.map { it.key }).associateBy { it.key }
        reminders.saveAll(
            wanted.mapNotNull { fresh ->
                val existing = known[fresh.key] ?: return@mapNotNull fresh
                existing.takeIf { it.revivable() }?.apply { reviveAt(fresh.dueAt) }
            }
        )
    }
}
