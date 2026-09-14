package com.kert0n.medapp.domain.notification

import java.time.Instant

/**
 * Обязательство сказать человеку (PLAN D8) — сущность: тождество — [key], состояние меняют
 * переходы, и `copy()` у неё нет. Отложенное напоминание — то же обязательство с другим сроком,
 * а не другое.
 *
 * Обязательство переживает перезагрузку, смену расписания и отказ в разрешении: `AlarmManager` и
 * `NotificationManager` памятью не являются и в этой роли не используются. Поэтому на вопросы «на
 * какой момент отложено», «что случилось, но не доставлено» и «что показано без повода» отвечает
 * эта вещь, а не система.
 */
class Reminder(
    val key: NotificationKey,
    val target: NotificationTarget,
    dueAt: Instant,
    state: State = State.DUE,
    shownAt: Instant? = null
) {

    /** Момент, к которому обещано сказать. Логический: система точной доставки не обещает. */
    var dueAt: Instant = dueAt
        private set

    var state: State = state
        private set

    /** Когда сказали впервые. Это и есть журнал показов: второй раз момент не двигается. */
    var shownAt: Instant? = shownAt
        private set

    val kind: NotificationKind get() = key.kind
    val channel: NotificationChannel get() = kind.channel
    val delivery: NoticeDelivery get() = kind.delivery
    val exact: Boolean get() = kind.exact
    val actions: List<NotificationAction> get() = kind.actions

    /** Наступило и ещё не сказано. Отозванное не наступает никогда. */
    fun isDue(now: Instant): Boolean = state == State.DUE && !now.isBefore(dueAt)

    /**
     * Человек отложил: срок другой, повод тот же. `plannedAt` пункта и граница `MISSED` не
     * двигаются (PLAN D8) — сдвигается только обещание сказать.
     */
    fun defer(until: Instant) {
        check(state != State.WITHDRAWN) { "отозванное напоминание не откладывают" }
        dueAt = until
        state = State.DUE
    }

    /** Сказали. Запись делается **после** показа: падение между ними оставляет обязательство `DUE`. */
    fun deliveredAt(at: Instant) {
        check(state == State.DUE) { "показывают то, что наступило" }
        state = State.SHOWN
        if (shownAt == null) shownAt = at
    }

    /** Повода больше нет: погасить и забыть. Гасит показанное владелец доставки, после фиксации. */
    fun withdraw() {
        state = State.WITHDRAWN
    }

    override fun equals(other: Any?): Boolean = this === other || (other is Reminder && key == other.key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "Reminder($key, $state, $dueAt)"

    /** Ещё должны сказать; сказали; повода больше нет. Поведение различает все три. */
    enum class State { DUE, SHOWN, WITHDRAWN }
}
