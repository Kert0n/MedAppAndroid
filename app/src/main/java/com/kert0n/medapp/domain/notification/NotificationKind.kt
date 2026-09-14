package com.kert0n.medapp.domain.notification

/**
 * Чем вызвано уведомление (PLAN D8). Каждый вид живёт на своём канале, и этапы одного события —
 * за три дня, за день, в день — разные виды: у них разные ключи, и один не заменяет другой.
 */
enum class NotificationKind(val channel: NotificationChannel) {
    INTAKE_DUE(NotificationChannel.INTAKES),
    INTAKE_MISSED(NotificationChannel.INTAKES),
    EXPIRY_SOURCE_3D(NotificationChannel.EXPIRY),
    EXPIRY_SOURCE_1D(NotificationChannel.EXPIRY),
    EXPIRY_TODAY(NotificationChannel.EXPIRY),
    COVERAGE_SHORT(NotificationChannel.COVERAGE),
    COVERAGE_3D(NotificationChannel.COVERAGE),
    COVERAGE_END(NotificationChannel.COVERAGE),
    DAILY_DIGEST(NotificationChannel.DIGEST);

    /** Точный будильник нужен только напоминанию о приёме: остальное — календарные события дня. */
    val exact: Boolean get() = this == INTAKE_DUE

    /**
     * Системным уведомлением или баннером внутри приложения — свойство вида, а не решение
     * вызывающего: баннер бывает только у последнего дня годности (PLAN D8).
     */
    val delivery: NoticeDelivery
        get() = if (this == EXPIRY_TODAY) NoticeDelivery.IN_APP_BANNER else NoticeDelivery.SYSTEM

    /**
     * Что можно сделать прямо с карточки: отвечают только напоминанию о приёме. «Принял» здесь
     * нет — он требует экрана при просрочке и отменённом курсе, а запустить его из приёмника
     * платформа с Android 12 не даёт (C1). Он вернётся вместе с экраном предупреждения в U5.
     */
    val actions: List<NotificationAction>
        get() = if (this == INTAKE_DUE) listOf(NotificationAction.SKIP, NotificationAction.SNOOZE) else emptyList()
}

/**
 * Канал — то, что человек выключает по отдельности в системных настройках (PLAN D8). Четыре канала
 * со своей важностью; строковые идентификаторы и названия — у платформы. Канал синхронизации
 * заводится в B18 вместе с `SYNC_ATTENTION`: канал, который никогда ничего не показывает, человек
 * видит в настройках как обман.
 */
enum class NotificationChannel(val importance: Importance) {
    INTAKES(Importance.HIGH),
    EXPIRY(Importance.DEFAULT),
    COVERAGE(Importance.DEFAULT),
    DIGEST(Importance.LOW);

    enum class Importance { HIGH, DEFAULT, LOW }
}

/** Как доставляется: системным уведомлением или баннером внутри приложения (PLAN D8). */
enum class NoticeDelivery { SYSTEM, IN_APP_BANNER }

/** Что можно сделать прямо с уведомления, не открывая приложение. */
enum class NotificationAction { SKIP, SNOOZE }
