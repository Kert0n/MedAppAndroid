package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.value.Attempts
import com.kert0n.medapp.network.server.RawResponse
import java.time.Instant
import java.util.Objects
import kotlin.uuid.Uuid

/**
 * Запись очереди: что предстоит доставить, чем это уже стало запросом и чем кончилось.
 *
 * `sequence` выдаёт база — номер принадлежит ей, а не команде, поэтому в самих командах его нет
 * (PLAN E2). Зависимости — множество: порядок между ними ничего не значит, и повтор тоже, а
 * список потребовал бы проверки уникальности вместо типа. [answer] — ответ сервера, записанный
 * до того, как его удалось применить: он есть ровно у [SyncOperationStatus.ANSWERED].
 * [notBefore] — раньше этого срока операцию не трогают: задержка повтора и `Retry-After` живут
 * здесь, а не в памяти прохода, и переживают его, процесс и второй `drain`.
 *
 * [outcomeUnknown] — замороженный запрос уже уходил, и чем кончилось, неизвестно: обрыв после
 * отправки, 5xx, неразборчивый ответ, смерть процесса в `SENDING`. Факт о **запросе**: живёт,
 * пока жив он, и умирает вместе с ним при переподготовке. По нему расход решает, что значит 404
 * (PLAN E3). [attempts] — только вход задержки и смысла не несёт.
 *
 * [refusalReason] — почему сервер делать не будет: значение, а не текст журнала, и есть оно ровно
 * у [SyncOperationStatus.REFUSED] (PLAN E2). Экран берёт по нему слова, а [lastError] остаётся
 * журналу.
 *
 * Состояние отправки — [state], и меняется оно только его переходами (C1 «Переходы операции — у
 * типа»): [taken], [resent], [answered], [deferred], [closed], [retried], [reprepared] отдают
 * операцию после перехода либо `null`, если из нынешнего статуса он невозможен. Тождество —
 * команда, номер и зависимости — переходом не меняется.
 */
class SyncOperation(
    val id: Uuid,
    val command: SyncCommand,
    val sequence: Long,
    val createdAt: Instant,
    val payloadVersion: Int,
    val prepared: PreparedRequest? = null,
    val groupId: Uuid? = null,
    dependsOn: Set<Uuid> = emptySet(),
    val status: SyncOperationStatus = SyncOperationStatus.PENDING,
    val attempts: Attempts = Attempts.none,
    val lastError: String? = null,
    val lastTriedAt: Instant? = null,
    val answer: RawResponse? = null,
    val notBefore: Instant? = null,
    val outcomeUnknown: Boolean = false,
    val refusalReason: RefusalReason? = null
) {
    /** Своя копия: множество, оставшееся у вызывающего, меняло бы порядок отправки очереди. */
    val dependsOn: Set<Uuid> = dependsOn.toSet()

    /** Состояние отправки; его инварианты — ответ ⇔ `ANSWERED`, причина ⇔ `REFUSED` — держит оно само. */
    val state: SyncOperationState = SyncOperationState(
        status, attempts, lastError, lastTriedAt, answer, notBefore, outcomeUnknown, refusalReason, hasRequest = prepared != null
    )

    init {
        require(sequence >= 0) { "номер в очереди не бывает отрицательным: $sequence" }
        require(payloadVersion >= 1) { "версия payload начинается с единицы" }
        require(id !in dependsOn) { "операция не зависит от себя самой" }
    }

    /** Ответ записан и ещё не применён: закрывается из него, сервер о нём больше не спрашивают. */
    val awaitsApplication: Boolean get() = state.awaitsApplication

    /** Запрос заморожен по свежему состоянию и уходит: только у ещё не подготовленной. */
    fun taken(request: PreparedRequest): SyncOperation? = state.frozen()?.let { with(it, request) }

    /** Замороженный запрос уходит снова; застали в отправке — исход прошлого полёта неизвестен. */
    fun resent(): SyncOperation? = state.resent()?.let { with(it, prepared) }

    fun answered(answer: RawResponse, at: Instant): SyncOperation? = state.answered(answer, at)?.let { with(it, prepared) }

    fun deferred(reason: String, at: Instant, notBefore: Instant): SyncOperation? =
        state.deferred(reason, at, notBefore)?.let { with(it, prepared) }

    fun closed(close: Settlement.Transition.Close, at: Instant): SyncOperation? = state.closed(close, at)?.let { with(it, prepared) }

    fun retried(reason: String, at: Instant, attempted: Boolean, outcomeUnknown: Boolean, notBefore: Instant?): SyncOperation? =
        state.retried(reason, at, attempted, outcomeUnknown, notBefore)?.let { with(it, prepared) }

    /** Запрос сброшен и готовится заново под тем же номером. */
    fun reprepared(reason: String, at: Instant, notBefore: Instant?): SyncOperation? =
        state.reprepared(reason, at, notBefore)?.let { with(it, null) }

    private fun with(state: SyncOperationState, prepared: PreparedRequest?): SyncOperation = SyncOperation(
        id, command, sequence, createdAt, payloadVersion, prepared, groupId, dependsOn,
        state.status, state.attempts, state.lastError, state.lastTriedAt, state.answer, state.notBefore,
        state.outcomeUnknown, state.refusalReason
    )

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SyncOperation &&
                id == other.id &&
                command == other.command &&
                sequence == other.sequence &&
                createdAt == other.createdAt &&
                payloadVersion == other.payloadVersion &&
                prepared == other.prepared &&
                groupId == other.groupId &&
                dependsOn == other.dependsOn &&
                status == other.status &&
                attempts == other.attempts &&
                lastError == other.lastError &&
                lastTriedAt == other.lastTriedAt &&
                answer == other.answer &&
                notBefore == other.notBefore &&
                outcomeUnknown == other.outcomeUnknown &&
                refusalReason == other.refusalReason
            )

    override fun hashCode(): Int = Objects.hash(
        id, command, sequence, createdAt, payloadVersion, prepared, groupId, dependsOn,
        status, attempts, lastError, lastTriedAt, answer, notBefore, outcomeUnknown, refusalReason
    )

    override fun toString(): String =
        "SyncOperation(id=$id, sequence=$sequence, status=$status, command=$command)"
}
