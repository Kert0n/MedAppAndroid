package com.kert0n.medapp.domain.notification

import com.kert0n.medapp.domain.value.Attempts
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Обязательство сказать человеку (PLAN D8) — сущность: тождество — [key], состояние меняют
 * переходы, и `copy()` у неё нет. Отложенное напоминание — то же обязательство с другим сроком,
 * а не другое.
 *
 * **Все вопросы о себе обязательство отвечает само:** наступило ли, к какому моменту будить, что
 * делать после показа и после неуспеха, можно ли забыть. Хранение только пишет его целиком и
 * читает: правило, записанное запросом, разъезжается с тем, о чём оно говорит (C1 «Правила
 * обязательства — на обязательстве»).
 *
 * Обязательство переживает перезагрузку, смену расписания и отказ в разрешении: `AlarmManager` и
 * `NotificationManager` памятью не являются и в этой роли не используются.
 */
class Reminder(
    val key: NotificationKey,
    val target: NotificationTarget,
    dueAt: Instant,
    state: State = State.DUE,
    shownAt: Instant? = null,
    notBefore: Instant? = null,
    attempts: Attempts = Attempts.none
) {

    /** Момент, к которому обещано сказать. Логический: система точной доставки не обещает. */
    var dueAt: Instant = dueAt
        private set

    var state: State = state
        private set

    /**
     * Когда сказали в **последний** раз. Это и есть журнал показов, и от него считается срок
     * хранения: отложенное говорят снова, и забывать его по первому показу значило бы уничтожить
     * живое обязательство.
     */
    var shownAt: Instant? = shownAt
        private set

    /** Раньше этого срока обязательство не трогают: задержка повтора после сбоя (PLAN E3, D8). */
    var notBefore: Instant? = null
        private set

    /** Только вход задержки: смысла сам по себе не несёт. */
    var attempts: Attempts = attempts
        private set

    init {
        this.notBefore = notBefore
    }

    val kind: NotificationKind get() = key.kind
    val channel: NotificationChannel get() = kind.channel
    val delivery: NoticeDelivery get() = kind.delivery
    val exact: Boolean get() = kind.exact
    val actions: List<NotificationAction> get() = kind.actions

    /** Раньше этого момента говорить рано: и срок события, и задержка повтора. */
    private val readyAt: Instant get() = notBefore?.takeIf { it.isAfter(dueAt) } ?: dueAt

    /** Наступило и ещё не сказано. Отозванное и сказанное не наступают. */
    fun isDue(now: Instant): Boolean = state == State.DUE && !now.isBefore(readyAt)

    /**
     * К какому моменту просить систему разбудить процесс; `null` — просить не надо.
     *
     * Наступившее и несказанное будильника **не просит**: сказать ему мешает не время, а разрешение
     * или сбой, о котором уже назначен срок. Будильник на прошедший момент система исполняет
     * немедленно — проход снова не покажет и снова поставит его на прошлое, и устройство будет
     * будиться без конца.
     */
    fun wakeAt(now: Instant, zone: ZoneId): Instant? =
        readyAt.takeIf { state == State.DUE && it.isAfter(now) && withinItsDay(it, zone) }

    /**
     * Говорить ли **сейчас**: наступило, не сказано — и свой день ещё не прошёл (PLAN C1 «В шторку —
     * только в свой день»). За пределом обязательство не снимается: оно остаётся [isDue] и ждёт
     * полки дня, а в шторку не идёт. Иначе снятый запрет вываливает в шторку все пропуски за месяц
     * вперемешку с сегодняшним приёмом.
     *
     * День — дата [dueAt] в зоне устройства [zone]: о чём обещали сказать сегодня, сказать стоит до
     * полуночи. Задержка повтора (`notBefore`) день не продлевает — повод от неё не молодеет.
     */
    fun speaksAt(now: Instant, zone: ZoneId): Boolean = isDue(now) && withinItsDay(now, zone)

    private fun withinItsDay(moment: Instant, zone: ZoneId): Boolean =
        !kind.saysWithinItsDay || moment.isBefore(dueAt.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant())

    /**
     * Карточка в шторке есть, а основания у неё нет: о нём говорили, а сейчас оно не
     * сказанное-и-наступившее — отозвано или отложено после показа. Карточка — факт о мире,
     * отдельный от «сказали», и гасят её, пока она не погашена (C1 «Карточка без основания
     * гасится, пока не погашена»).
     */
    val cardIsUp: Boolean get() = shownAt != null && state != State.SHOWN

    /**
     * Сказали, а основание ушло, пока система показывала: карточка есть, сказанным это не
     * считается — состояние не трогается, только журнал показов. Что дальше, решает [cardIsUp].
     */
    fun noticedAt(at: Instant) {
        shownAt = at
    }

    /** Наступило, а сказать нечем: этого ждёт лист приёмов при запуске (PLAN H3 №29). */
    fun awaitsAttention(now: Instant): Boolean = isDue(now)

    /** Экрану — проекция: что обещано, кому и на когда; переходов у неё нет. */
    fun projection(): PendingNotice = PendingNotice(key, target, dueAt)

    /**
     * Человек отложил: срок другой, повод тот же. `plannedAt` пункта и граница `MISSED` не
     * двигаются (PLAN D8) — сдвигается только обещание сказать, и задержка повтора с ним не спорит.
     */
    fun defer(until: Instant) {
        check(state != State.WITHDRAWN) { "отозванное напоминание не откладывают" }
        dueAt = until
        notBefore = null
        attempts = Attempts.none
        state = State.DUE
    }

    /** Сказали. Запись делается **после** показа: падение между ними оставляет обязательство `DUE`. */
    fun deliveredAt(at: Instant) {
        check(state == State.DUE) { "показывают то, что наступило" }
        state = State.SHOWN
        shownAt = at
        notBefore = null
        attempts = Attempts.none
    }

    /**
     * Сказать не удалось — сбой базы или системы. Повод жив, и мы вернёмся, но не сейчас же:
     * задержка растёт с попытками и упирается в [MAX_BACKOFF].
     */
    fun failedAt(now: Instant) {
        check(state == State.DUE) { "повторяют то, что наступило" }
        attempts = attempts.next()
        notBefore = now.plus(backoff(attempts))
    }

    /** Повода больше нет: погасить и забыть. Гасит показанное владелец доставки, после фиксации. */
    fun withdraw() {
        state = State.WITHDRAWN
    }

    /** Отозванное, но так и не сказанное: можно вернуть, если повод вернулся. */
    fun revivable(): Boolean = state == State.WITHDRAWN && shownAt == null

    /** Повод вернулся раньше, чем мы успели забыть: обязательство живо снова. */
    fun reviveAt(dueAt: Instant) {
        check(revivable()) { "воскрешают только отозванное и несказанное" }
        this.dueAt = dueAt
        notBefore = null
        attempts = Attempts.none
        state = State.DUE
    }

    /**
     * Пора забыть. Сказанное давно ни на что не влияет; отозванное своё уже отгасило; наступившее,
     * но так и не сказанное, старше срока хранения — тоже: сказать о событии месячной давности
     * нечего, а таблица иначе растёт всю жизнь установки.
     */
    fun forgettable(now: Instant): Boolean = when (state) {
        State.WITHDRAWN -> true
        State.SHOWN -> shownAt?.isBefore(now.minus(RETENTION)) ?: true
        State.DUE -> dueAt.isBefore(now.minus(RETENTION))
    }

    override fun equals(other: Any?): Boolean = this === other || (other is Reminder && key == other.key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "Reminder($key, $state, $dueAt)"

    /** Ещё должны сказать; сказали; повода больше нет. Поведение различает все три. */
    enum class State { DUE, SHOWN, WITHDRAWN }

    companion object {
        /** Сколько помним обязательство после последнего показа или наступившего срока. */
        val RETENTION: Duration = Duration.ofDays(30)

        /**
         * Задержка повтора после сбоя. Форма та же, что у операции очереди (E3), а константы свои:
         * речь о показе, а не о проводе, и общий код здесь был бы ложным родством.
         */
        private val INITIAL_BACKOFF: Duration = Duration.ofMinutes(1)
        private val MAX_BACKOFF: Duration = Duration.ofHours(1)
        private const val MAX_BACKOFF_STEPS = 6

        private fun backoff(attempts: Attempts): Duration =
            INITIAL_BACKOFF.multipliedBy(1L shl minOf(attempts.count - 1, MAX_BACKOFF_STEPS).coerceAtLeast(0))
                .coerceAtMost(MAX_BACKOFF)
    }
}
