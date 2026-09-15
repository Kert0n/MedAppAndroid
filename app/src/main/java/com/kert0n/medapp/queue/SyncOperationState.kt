package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.value.Attempts
import com.kert0n.medapp.network.server.RawResponse
import java.time.Instant

/**
 * Состояние отправки операции — то, что у строки очереди меняется, пока команда, номер и
 * зависимости остаются теми же (PLAN E2, C1 «Переходы операции — у типа»). Переходы — здесь, и
 * только здесь: предусловие каждого — статус, из которого он возможен, а неприменимый переход —
 * `null`, не исключение: закрытая второй раз не закрывается, и следствий у второго закрытия нет.
 * Хранение читает строку в транзакции, зовёт переход и пишет то, что получило, одной дверью.
 *
 * [answer] — ответ сервера, записанный до применения: есть ровно у [SyncOperationStatus.ANSWERED],
 * закрытие и повтор его стирают. [outcomeUnknown] — замороженный запрос уходил, и чем кончилось,
 * неизвестно: факт о **запросе**, живёт, пока жив он ([hasRequest]), и умирает с ним при
 * переподготовке; повтор его не снимает. [attempts] — только вход задержки. [refusalReason] —
 * почему сервер делать не будет, ровно у [SyncOperationStatus.REFUSED]; [lastError] — журналу.
 * Состояние читается без словаря — оно в колонках, — поэтому нечитаемая командой строка
 * закрывается тем же переходом, что и читаемая.
 */
data class SyncOperationState(
    val status: SyncOperationStatus = SyncOperationStatus.PENDING,
    val attempts: Attempts = Attempts.none,
    val lastError: String? = null,
    val lastTriedAt: Instant? = null,
    val answer: RawResponse? = null,
    val notBefore: Instant? = null,
    val outcomeUnknown: Boolean = false,
    val refusalReason: RefusalReason? = null,
    val hasRequest: Boolean = false
) {

    init {
        require((answer != null) == (status == SyncOperationStatus.ANSWERED)) {
            "записанный ответ бывает ровно у операции, которая его получила и ещё не закрыта"
        }
        require(!outcomeUnknown || hasRequest) { "неизвестный исход бывает только у отправленного запроса" }
        require((refusalReason != null) == (status == SyncOperationStatus.REFUSED)) {
            "причина отказа есть ровно у отказанной операции: $status и $refusalReason друг другу не пара"
        }
    }

    /** Ответ записан и ещё не применён: сервер о нём больше не спрашивают. */
    val awaitsApplication: Boolean get() = status == SyncOperationStatus.ANSWERED

    private val open: Boolean get() = status == SyncOperationStatus.PENDING || status == SyncOperationStatus.SENDING

    /** Запрос заморожен по свежему состоянию и уходит в первый раз — только у ещё не подготовленной. */
    fun frozen(): SyncOperationState? =
        if (open && !hasRequest) copy(status = SyncOperationStatus.SENDING, hasRequest = true) else null

    /**
     * Замороженный запрос уходит снова. Застали в `SENDING` — прошлый полёт умер вместе с
     * процессом, и его исход неизвестен: факт остаётся у запроса.
     */
    fun resent(): SyncOperationState? =
        if (open && hasRequest) copy(status = SyncOperationStatus.SENDING, outcomeUnknown = outcomeUnknown || status == SyncOperationStatus.SENDING) else null

    /** Ответ записан до применения — только из отправки: полученное подтверждение не теряется. */
    fun answered(answer: RawResponse, at: Instant): SyncOperationState? =
        if (status == SyncOperationStatus.SENDING) copy(status = SyncOperationStatus.ANSWERED, answer = answer, lastTriedAt = at) else null

    /** Ответ есть, применить нечем: остаётся с ним, попытка считается — от неё растёт задержка. */
    fun deferred(reason: String, at: Instant, notBefore: Instant): SyncOperationState? =
        if (status == SyncOperationStatus.ANSWERED) copy(attempts = attempts.next(), lastError = reason, lastTriedAt = at, notBefore = notBefore) else null

    /**
     * Закрытие — одно на свою операцию и на зависимые ([Settlement.Effect.Cascade]): статус и
     * причина — из [close], журналу — она же строкой, ответ стирается, срока больше нет. Счёт попыток
     * закрытию двигать нечего (PLAN E2, E3).
     */
    fun closed(close: Settlement.Transition.Close, at: Instant): SyncOperationState? =
        if (status.isClosed) null else copy(
            status = close.status, refusalReason = close.refusalReason, lastError = close.refusalReason?.name,
            lastTriedAt = at, answer = null, notBefore = null
        )

    /**
     * Снова ждёт тем же запросом: попытка и неизвестный исход — как сказал [Delivery.Retry].
     * Неизвестный исход прилипает к запросу: раз неизвестный — неизвестный, пока запрос жив.
     */
    fun retried(reason: String, at: Instant, attempted: Boolean, outcomeUnknown: Boolean, notBefore: Instant?): SyncOperationState? =
        if (status.isClosed) null else copy(
            status = SyncOperationStatus.PENDING, lastError = reason, lastTriedAt = at,
            attempts = if (attempted) attempts.next() else attempts, answer = null, notBefore = notBefore,
            outcomeUnknown = this.outcomeUnknown || outcomeUnknown, refusalReason = null
        )

    /**
     * Запрос сброшен: версия устарела, и он готовится заново по свежему состоянию под тем же
     * номером. Не попытка — задержка от этого не растёт; факт «исход неизвестен» умирает вместе с
     * запросом (PLAN E3). Только у отправлявшейся или получившей ответ.
     */
    fun reprepared(reason: String, at: Instant, notBefore: Instant?): SyncOperationState? =
        if (status == SyncOperationStatus.SENDING || status == SyncOperationStatus.ANSWERED) copy(
            status = SyncOperationStatus.PENDING, lastError = reason, lastTriedAt = at, notBefore = notBefore,
            outcomeUnknown = false, hasRequest = false, answer = null
        ) else null
}
