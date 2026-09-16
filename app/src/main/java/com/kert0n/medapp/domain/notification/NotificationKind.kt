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
    /** Очередь ждёт решения человека: отвергнутое или нечитаемое — одно обязательство на всю очередь. */
    SYNC_ATTENTION(NotificationChannel.SYNC),

    /**
     * «Принял» из шторки записать не смог — нужно решение человека (PLAN C1 «Принял» из шторки):
     * коробки больше нет, лечение сменило источник, пачки в пункте не было. Нажатие ведёт на
     * карточку пункта, где он решает. Последним — чтобы номера прежних видов в шторке не сдвинулись.
     */
    INTAKE_DECISION(NotificationChannel.INTAKES);

    /**
     * Говорится ли только в свой день (PLAN C1 «В шторку — только в свой день»). Новость дня назавтра
     * врёт или тонет; а решить очередь и узнать о чужом сокращении обеспечения нужно и через день.
     */
    val saysWithinItsDay: Boolean get() = this != SYNC_ATTENTION && this != COVERAGE_SHORT

    /** Точный будильник нужен только напоминанию о приёме: остальное — календарные события дня. */
    val exact: Boolean get() = this == INTAKE_DUE

    /**
     * Системным уведомлением или внутри приложения — свойство вида, а не решение вызывающего.
     * В приложении говорятся два: последний день годности и **пропуск** — неотвеченный пункт
     * прошлого дня приходит попапом при входе, а не в шторку (PLAN C1 «Попап пропущенного»).
     */
    val delivery: NoticeDelivery
        get() = if (this == EXPIRY_TODAY || this == INTAKE_MISSED) NoticeDelivery.IN_APP_BANNER else NoticeDelivery.SYSTEM

    /**
     * Что можно сделать прямо с карточки: отвечают только напоминанию о приёме. Все три действия
     * делаются без экрана; «Принял», которому записать не удалось, заводит [INTAKE_DECISION] (C1).
     */
    val actions: List<NotificationAction>
        get() = if (this == INTAKE_DUE) listOf(NotificationAction.TAKE, NotificationAction.SKIP, NotificationAction.SNOOZE) else emptyList()
}

/**
 * Канал — то, что человек выключает по отдельности в системных настройках (PLAN D8). Пять каналов
 * со своей важностью; строковые идентификаторы и названия — у платформы. Канал без вида, который
 * на нём что-то показывает, не заводится: человек видит его в настройках как обман.
 */
enum class NotificationChannel(val importance: Importance) {
    INTAKES(Importance.HIGH),
    EXPIRY(Importance.DEFAULT),
    COVERAGE(Importance.DEFAULT),
    DIGEST(Importance.LOW),
    SYNC(Importance.DEFAULT);

    enum class Importance { HIGH, DEFAULT, LOW }
}

/** Как доставляется: системным уведомлением или баннером внутри приложения (PLAN D8). */
enum class NoticeDelivery { SYSTEM, IN_APP_BANNER }

/**
 * Что можно сделать прямо с уведомления — **без экрана**, приёмником (PLAN D8, C1). «Принял» пишет
 * тем же сценарием, что экран: вопросов у планового приёма нет, а не вышло — приходит «нужно ваше
 * решение», и уже оно открывает приложение (поправка владельца 2026-09-16).
 */
enum class NotificationAction {
    TAKE,
    SKIP,
    SNOOZE
}
