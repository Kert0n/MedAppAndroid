package com.kert0n.medapp.domain.notification

import java.time.Instant

/**
 * Что показать — величина, которую собирает планирование и исполняет платформа (PLAN D8).
 * [dueAt] — логический момент события, а не обещание системы доставить в него. Точность
 * ([exact]) принадлежит виду: точный будильник нужен только напоминанию о приёме.
 */
data class PlannedNotification(
    val key: NotificationKey,
    val dueAt: Instant,
    val target: NotificationTarget,
    val delivery: NoticeDelivery,
    val actions: List<NotificationAction> = emptyList()
) {
    val kind: NotificationKind get() = key.kind
    val channel: NotificationChannel get() = kind.channel
    val exact: Boolean get() = kind.exact
}
