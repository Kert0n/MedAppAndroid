package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.value.Attempts
import com.kert0n.medapp.queue.Settlement.Transition.Close
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Автомат состояний операции — у типа, а не в SQL (PLAN C1 «Переходы операции — у типа»): из
 * какого статуса какой переход возможен, что он меняет и чего не трогает. Пока правила стояли
 * шестью `WHERE status IN (…)` и у двенадцати вызывающих, копии расходились — `SUPERSEDED` в журнале
 * у утраты доступа, ромб зависимых дважды.
 */
class SyncOperationStateTest {

    private val at: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val answer = Receipt(200, "{}")

    /** Состояние в каждом статусе, соблюдающее инварианты; [withRequest] — запрос заморожен. */
    private fun state(status: SyncOperationStatus, withRequest: Boolean = status != SyncOperationStatus.PENDING) = SyncOperationState(
        status = status,
        answer = answer.takeIf { status == SyncOperationStatus.ANSWERED },
        refusalReason = RefusalReason.INVALID.takeIf { status == SyncOperationStatus.REFUSED },
        hasRequest = withRequest
    )

    private val transitions: Map<String, (SyncOperationState) -> SyncOperationState?> = linkedMapOf(
        "frozen" to { it.frozen() },
        "resent" to { it.resent() },
        "answered" to { it.answered(answer, at) },
        "deferred" to { it.deferred("нечем", at, at.plusSeconds(2)) },
        "closed" to { it.closed(Close.Applied, at) },
        "retried" to { it.retried("обрыв", at, attempted = true, outcomeUnknown = false, notBefore = null) },
        "reprepared" to { it.reprepared("устарело", at, null) }
    )

    /** Ровно те предусловия, что стояли в `WHERE` шести глаголов хранения. */
    @Test
    fun eachTransitionIsPossibleFromExactlyItsStatuses() {
        val allowed: Map<String, Set<SyncOperationStatus>> = mapOf(
            "frozen" to setOf(SyncOperationStatus.PENDING, SyncOperationStatus.SENDING),
            "resent" to setOf(SyncOperationStatus.PENDING, SyncOperationStatus.SENDING),
            "answered" to setOf(SyncOperationStatus.SENDING),
            "deferred" to setOf(SyncOperationStatus.ANSWERED),
            "closed" to setOf(SyncOperationStatus.PENDING, SyncOperationStatus.SENDING, SyncOperationStatus.ANSWERED),
            "retried" to setOf(SyncOperationStatus.PENDING, SyncOperationStatus.SENDING, SyncOperationStatus.ANSWERED),
            "reprepared" to setOf(SyncOperationStatus.SENDING, SyncOperationStatus.ANSWERED)
        )
        for ((name, transition) in transitions) {
            for (status in SyncOperationStatus.entries) {
                // «Заморозить» можно только без запроса, «отправить снова» — только с ним.
                val from = state(status, withRequest = name != "frozen" && (name == "resent" || status != SyncOperationStatus.PENDING))
                val possible = transition(from) != null
                assertEquals("$name из $status", status in allowed.getValue(name), possible)
            }
        }
    }

    @Test
    fun frozenNeedsNoRequestAndResentNeedsOne() {
        assertNull("замороженный второй раз не замораживается", state(SyncOperationStatus.PENDING, withRequest = true).frozen())
        assertNull("без запроса отправлять снова нечего", state(SyncOperationStatus.PENDING, withRequest = false).resent())
        assertTrue(state(SyncOperationStatus.PENDING, withRequest = false).frozen()!!.hasRequest)
    }

    @Test
    fun aClosedOperationIsNotClosedAgain() {
        val closed = state(SyncOperationStatus.SENDING).closed(Close.Refused(RefusalReason.INSUFFICIENT), at)!!
        assertEquals(SyncOperationStatus.REFUSED, closed.status)
        assertEquals(RefusalReason.INSUFFICIENT, closed.refusalReason)
        assertEquals("журналу — та же причина строкой", "INSUFFICIENT", closed.lastError)
        assertNull("закрытая второй раз не закрывается", closed.closed(Close.AccessLost, at))
        assertNull(closed.retried("x", at, attempted = false, outcomeUnknown = false, notBefore = null))
    }

    @Test
    fun aReasonExistsExactlyAtRefusal() {
        assertNull(state(SyncOperationStatus.SENDING).closed(Close.Applied, at)!!.refusalReason)
        val lost = state(SyncOperationStatus.SENDING).closed(Close.AccessLost, at)!!
        assertNull(lost.refusalReason)
        assertNull("утрата доступа причины не имеет — и в журнале тоже", lost.lastError)
        assertNotNull(state(SyncOperationStatus.SENDING).closed(Close.Refused(RefusalReason.SUPERSEDED), at)!!.refusalReason)
    }

    @Test
    fun anUnknownOutcomeSurvivesARetryAndDiesWithThePreparedRequest() {
        val unknown = state(SyncOperationStatus.SENDING).retried("ответ потерян", at, attempted = true, outcomeUnknown = true, notBefore = at.plusSeconds(2))!!
        assertTrue(unknown.outcomeUnknown)
        assertEquals(SyncOperationStatus.PENDING, unknown.status)
        assertEquals(Attempts(1), unknown.attempts)
        val again = unknown.resent()!!.retried("обрыв", at, attempted = false, outcomeUnknown = false, notBefore = null)!!
        assertTrue("раз неизвестный — неизвестный, пока запрос жив", again.outcomeUnknown)
        assertEquals("повтор без попытки задержку не растит", Attempts(1), again.attempts)
        val fresh = again.resent()!!.reprepared("устарело", at, null)!!
        assertFalse("переподготовка убивает запрос и факт о нём", fresh.outcomeUnknown)
        assertFalse(fresh.hasRequest)
        assertEquals("переподготовка — не попытка", Attempts(1), fresh.attempts)
    }

    @Test
    fun resendingWhatWasCaughtInSendingMarksTheOutcomeUnknown() {
        assertTrue("застали в отправке — прошлый полёт умер, исход неизвестен", state(SyncOperationStatus.SENDING).resent()!!.outcomeUnknown)
        assertFalse("ждавшая с запросом уходит с известной судьбой", state(SyncOperationStatus.PENDING, withRequest = true).resent()!!.outcomeUnknown)
    }

    @Test
    fun anAnswerLivesExactlyBetweenAnsweredAndClosing() {
        val answered = state(SyncOperationStatus.SENDING).answered(answer, at)!!
        assertEquals(answer, answered.answer)
        assertEquals(at, answered.lastTriedAt)
        val deferred = answered.deferred("словаря нет", at.plusSeconds(1), at.plusSeconds(5))!!
        assertEquals("ждёт с ответом в руках", answer, deferred.answer)
        assertEquals(Attempts(1), deferred.attempts)
        assertNull("закрытие стирает ответ", answered.closed(Close.Applied, at)!!.answer)
        assertNull("повтор стирает ответ", answered.retried("x", at, attempted = false, outcomeUnknown = true, notBefore = null)!!.answer)
        assertNull("переподготовка стирает ответ", answered.reprepared("x", at, null)!!.answer)
    }

    @Test
    fun theInvariantsAreHeldByTheType() {
        assertNotNull(runCatching { SyncOperationState(status = SyncOperationStatus.SENDING, answer = answer, hasRequest = true) }.exceptionOrNull())
        assertNotNull(runCatching { SyncOperationState(status = SyncOperationStatus.ANSWERED, hasRequest = true) }.exceptionOrNull())
        assertNotNull(runCatching { SyncOperationState(status = SyncOperationStatus.APPLIED, refusalReason = RefusalReason.STALE) }.exceptionOrNull())
        assertNotNull(runCatching { SyncOperationState(status = SyncOperationStatus.REFUSED) }.exceptionOrNull())
        assertNotNull(runCatching { SyncOperationState(outcomeUnknown = true, hasRequest = false) }.exceptionOrNull())
    }
}
