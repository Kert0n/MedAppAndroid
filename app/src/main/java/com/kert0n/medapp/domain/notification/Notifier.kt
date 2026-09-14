package com.kert0n.medapp.domain.notification

/**
 * Показать человеку — действие продукта, названное доменом и исполненное платформой (PLAN D8, H1).
 * Как оно выглядит и на каком канале звучит, домен не знает.
 */
interface Notifier {

    /** Показано или нет: без разрешения на уведомления показ невозможен, и журнал об этом не пишется. */
    suspend fun show(reminder: Reminder): Boolean

    suspend fun dismiss(key: NotificationKey)
}
