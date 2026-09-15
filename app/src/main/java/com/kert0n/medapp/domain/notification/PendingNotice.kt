package com.kert0n.medapp.domain.notification

import java.time.Instant

/**
 * Невыполненное обязательство, как его видит экран (PLAN D8, H3 №29): что обещано, кому и на
 * какой момент. Проекция, а не сущность — переходов у неё нет, наружу сущность не уходит (F5);
 * строит её сама сущность ([Reminder.projection]). Лист приёмов без пушей читает по ней, что не
 * смогли сказать; баннеры дня — что показать в приложении.
 */
data class PendingNotice(val key: NotificationKey, val target: NotificationTarget, val dueAt: Instant) {
    val kind: NotificationKind get() = key.kind
}
