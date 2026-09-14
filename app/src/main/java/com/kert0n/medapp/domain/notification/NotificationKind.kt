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
    DAILY_DIGEST(NotificationChannel.DIGEST),
    SYNC_ATTENTION(NotificationChannel.SYNC);

    /** Точный будильник нужен только напоминанию о приёме: остальное — календарные события дня. */
    val exact: Boolean get() = this == INTAKE_DUE
}

/**
 * Канал — то, что человек выключает по отдельности в системных настройках (PLAN D8). Пять каналов
 * с своей важностью; строковые идентификаторы и названия — у платформы.
 */
enum class NotificationChannel(val importance: Importance) {
    INTAKES(Importance.HIGH),
    EXPIRY(Importance.DEFAULT),
    COVERAGE(Importance.DEFAULT),
    DIGEST(Importance.LOW),
    SYNC(Importance.LOW);

    enum class Importance { HIGH, DEFAULT, LOW }
}

/** Как доставляется: системным уведомлением или баннером внутри приложения (PLAN D8). */
enum class NoticeDelivery { SYSTEM, IN_APP_BANNER }

/** Что можно сделать прямо с уведомления. */
enum class NotificationAction { TAKE, SKIP, SNOOZE, OPEN }
