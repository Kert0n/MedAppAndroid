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
    SYNC_ATTENTION(NotificationChannel.SYNC);

    /**
     * Говорится ли только в свой день (PLAN C1 «В шторку — только в свой день»). Новость дня назавтра
     * врёт или тонет; а решить очередь и узнать о чужом сокращении обеспечения нужно и через день.
     */
    val saysWithinItsDay: Boolean get() = this != SYNC_ATTENTION && this != COVERAGE_SHORT

    /** Точный будильник нужен только напоминанию о приёме: остальное — календарные события дня. */
    val exact: Boolean get() = this == INTAKE_DUE

    /**
     * Системным уведомлением или баннером внутри приложения — свойство вида, а не решение
     * вызывающего: баннер бывает только у последнего дня годности (PLAN D8).
     */
    val delivery: NoticeDelivery
        get() = if (this == EXPIRY_TODAY) NoticeDelivery.IN_APP_BANNER else NoticeDelivery.SYSTEM

    /**
     * Что можно сделать прямо с карточки: отвечают только напоминанию о приёме. «Принял» требует
     * экрана при просрочке и отменённом курсе, поэтому он — намерение открыть приложение с целью
     * и действием, а не приёмник (C1); куда вести — решает оболочка (U5).
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
 * Что можно сделать прямо с уведомления. «Пропустить» и «Отложить» обходятся без экрана;
 * «Принял» открывает приложение — записать приём молча нельзя, когда есть о чём предупредить
 * (PLAN D8, C1).
 */
enum class NotificationAction {
    TAKE,
    SKIP,
    SNOOZE;

    /** Без экрана: делается приёмником, не открывая приложение. */
    val handledInBackground: Boolean get() = this != TAKE
}
