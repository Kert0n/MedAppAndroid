package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.delivery.CourierDoor
import com.kert0n.medapp.network.delivery.MedAppCourier
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.VocabularyStore
import com.kert0n.medapp.queue.pack.PackageSnapshot
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Поводов синхронизации много, а заход один: очередь, потом снимок, и совпавшие поводы сливаются.
 * Остаток очереди поручается планировщику системы, пустая очередь не стоит ничего (PLAN E4).
 */
class SynchronizationTest {

    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /** Очередь без готовых операций: проход кончается сразу, но известно, что он был. */
    private class EmptyQueue(private val calls: MutableList<String>) : QueueStorage {
        override suspend fun ready(now: Instant): List<StoredSyncOperation> {
            calls += "очередь"
            return emptyList()
        }
        override fun changes() = kotlinx.coroutines.flow.emptyFlow<Unit>()
        override suspend fun nextDueAt(now: Instant): Instant? = null
        override suspend fun medKit(id: Uuid): MedKitRef? = null
        override suspend fun take(id: Uuid, fresh: PackageSnapshot?, at: Instant) = error("не для этого теста")
        override suspend fun answered(id: Uuid, answer: Receipt, at: Instant) = error("не для этого теста")
        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = error("не для этого теста")
        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) = error("не для этого теста")
        override suspend fun enqueue(queued: QueuedCommand, shelf: Uuid, at: Instant) = error("не для этого теста")
    }

    private object NoTransport : CourierDoor {
        override suspend fun send(request: PreparedRequest): ApiResult<RawResponse> = error("не для этого теста")
        override suspend fun packageSnapshot(packageId: Uuid): ApiResult<PackageSnapshotNetworkDTO> = error("не для этого теста")
        override suspend fun medKitIsOurs(medKitId: Uuid): ApiResult<Boolean> = ApiResult.Failure(ApiFailure.Unavailable)
    }

    private class Store : VocabularyStore {
        override suspend fun snapshot() = Vocabulary(emptyList(), emptyList())
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) = Unit
    }

    private class Nothing : SnapshotStorage {
        override suspend fun serverKnows() = ServerKnowledge(emptySet(), emptySet(), emptySet(), emptySet())
        override suspend fun lay(snapshot: ServerSnapshot, at: Instant) = Unit
    }

    private class Backlog(var due: Instant?) : QueueBacklog {
        override suspend fun dueAt(now: Instant, except: Set<Uuid>): Instant? = due
    }

    private class Schedule : SyncSchedule {
        val comeBacks = ArrayList<Instant>()
        override suspend fun keepRegular(interval: SyncInterval) = Unit
        override fun comeBackFor(dueAt: Instant) {
            comeBacks += dueAt
        }
    }

    /**
     * Сервер: снимок пустой; [gate] держит ответ, пока тест не отпустит; `online = false` — связи нет.
     * [calls] видит порядок: очередь, затем снимок.
     */
    private fun synchronization(
        calls: MutableList<String>,
        backlog: Backlog = Backlog(null),
        schedule: Schedule = Schedule(),
        gate: CompletableDeferred<Unit>? = null,
        online: Boolean = true,
        scope: kotlinx.coroutines.CoroutineScope
    ): Synchronization {
        val api = MedAppApi(
            medAppHttpClient(
                MockEngine {
                    calls += "снимок"
                    gate?.await()
                    if (!online) throw java.io.IOException("нет связи")
                    respond("""{"id":"${Uuid.random()}","medKits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
        val storage = EmptyQueue(calls)
        val vocabulary = VocabularyResolver(Store(), api)
        val resolver = PackageSnapshotResolver(vocabulary, storage)
        val worker = QueueWorker(storage, MedAppCourier(NoTransport, vocabulary, resolver, clock), clock)
        val snapshots = SnapshotApplier(api, Nothing(), vocabulary, resolver, clock)
        return Synchronization(worker, snapshots, backlog, schedule, clock, scope)
    }

    /**
     * Сервер молчит дольше, чем напоминание готово ждать: ответ — «не успели», без ошибки, а заход
     * доживает и его итог достаётся следующему (PLAN D8). Время здесь настоящее: сеть у `MockEngine`
     * идёт своими потоками, и виртуальные часы `runTest` убежали бы вперёд неё.
     */
    @Test
    fun aBriefRefreshGivesUpWaitingButNotTheRound() = kotlinx.coroutines.runBlocking {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val gate = CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val synchronization = synchronization(calls, gate = gate, scope = scope)

        val brief = synchronization.awaitBriefly(java.time.Duration.ofMillis(300))

        assertNull(brief)
        // Следующий повод встаёт ждать идущий заход **до** ответа сервера: пришедший после конца
        // захода начал бы свой, и проверка зависела бы от того, чей поток успел первым.
        val next = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { synchronization.synchronize() }
        // Сервер ответил позже — заход дошёл до конца, и следующий повод получает его итог.
        gate.complete(Unit)
        val later = next.await()
        assertEquals(listOf("очередь", "снимок"), calls.toList())
        assertEquals(now, later.finishedAt)
        scope.cancel()
    }

    /** Сервер быстрый — напоминание дождалось свежего снимка. */
    @Test
    fun aBriefRefreshReturnsTheRoundWhenItIsInTime() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val brief = synchronization(java.util.Collections.synchronizedList(ArrayList()), scope = scope).awaitBriefly(java.time.Duration.ofSeconds(5))
        assertEquals(now, brief?.finishedAt)
        scope.cancel()
    }

    /**
     * Обычный путь: сначала отдаём своё, потом читаем правду; очередь пуста — планировщику ничего не
     * поручают, а время последнего чтения запомнено для экрана.
     */
    @Test
    fun aRoundDeliversThenReadsAndAsksForNothingMore() = runTest {
        val calls = ArrayList<String>()
        val schedule = Schedule()

        val round = synchronization(calls, schedule = schedule, scope = backgroundScope).let {
            it.synchronize().also { _ -> assertEquals(now, it.state.value.refreshedAt) }
        }

        assertEquals(listOf("очередь", "снимок"), calls)
        assertNull(round.backlogDueAt)
        assertEquals(emptyList<Instant>(), schedule.comeBacks)
    }

    /**
     * В очереди осталось неотправленное: планировщик придёт за ним не раньше срока — а срок, который
     * уже прошёл, значит «как только будет связь».
     */
    @Test
    fun whatIsLeftInTheQueueIsLeftToTheScheduler() = runTest {
        val schedule = Schedule()
        val backlog = Backlog(now.minusSeconds(60))

        val first = synchronization(ArrayList(), backlog, schedule, scope = backgroundScope).synchronize()
        backlog.due = now.plusSeconds(300)
        val second = synchronization(ArrayList(), backlog, schedule, scope = backgroundScope).synchronize()

        assertEquals(now, first.backlogDueAt)
        assertEquals(listOf(now, now.plusSeconds(300)), schedule.comeBacks)
        assertEquals(now.plusSeconds(300), second.backlogDueAt)
    }

    /** Повод, пришедший во время захода, ждёт его и получает его итог: снимок читается один раз. */
    @Test
    fun aReasonArrivingDuringARoundJoinsIt() = runTest {
        val calls = ArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        val synchronization = synchronization(calls, gate = gate, scope = backgroundScope)

        val first = async { synchronization.synchronize() }
        val second = async { synchronization.synchronize() }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)

        assertSame(first.await(), second.await())
        assertEquals(1, calls.count { it == "снимок" })
    }

    /** Ждать условия по настоящим часам — не дольше пяти секунд: сломанный заход не вешает прогон. */
    private suspend fun until(what: String, condition: () -> Boolean) {
        kotlinx.coroutines.withTimeoutOrNull(5_000) { while (!condition()) kotlinx.coroutines.delay(10) }
            ?: throw AssertionError("не дождались: $what")
    }

    /**
     * Заход не принадлежит тому, кто позвал первым. Анна нажала «Обновить» и ушла с экрана — её
     * ожидание кончилось, а напоминание, вставшее ждать того же захода, получает его итог.
     *
     * Красная проверка: первый позвавший вёл заход в своём контексте, его отмена завершала заход
     * `CancellationException`, и присоединившийся получал её как свою — так останавливался цикл
     * владельца доставки уведомлений.
     */
    @Test
    fun aCancelledFirstCallerDoesNotCancelThoseWhoJoined() = kotlinx.coroutines.runBlocking {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val gate = CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val synchronization = synchronization(calls, gate = gate, scope = scope)
        val screen = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Default)

        try {
            screen.async { synchronization.synchronize() }
            until("заход дошёл до снимка") { "снимок" in calls }
            val reminder = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { synchronization.synchronize() }
            screen.cancel()
            gate.complete(Unit)

            assertEquals(now, reminder.await().finishedAt)
        } finally {
            screen.cancel()
            scope.cancel()
        }
    }

    /**
     * Заход упал — ждущие получают **сбой**, а не отмену: сбой они умеют пережить, а отмена значила
     * бы для них «остановили нас самих».
     */
    @Test
    fun aFailedRoundIsAFailureForEveryoneWaiting() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val api = MedAppApi(medAppHttpClient(MockEngine { requests.incrementAndGet(); gate.await(); error("сервер ответил не по-человечески") }, "https://medapp.test"))
        val rounds = java.util.Collections.synchronizedList(ArrayList<String>())
        val storage = EmptyQueue(rounds)
        val vocabulary = VocabularyResolver(Store(), api)
        val resolver = PackageSnapshotResolver(vocabulary, storage)
        val failing = Synchronization(
            QueueWorker(storage, MedAppCourier(NoTransport, vocabulary, resolver, clock), clock),
            SnapshotApplier(api, Nothing(), vocabulary, resolver, clock),
            Backlog(null), Schedule(), clock, scope
        )

        try {
            // Второй присоединяется, пока заход держит ворота: сбой один, и получают его оба.
            val first = async { kotlin.runCatching { failing.synchronize() }.exceptionOrNull() }
            until("заход дошёл до сервера") { requests.get() >= 1 }
            val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { kotlin.runCatching { failing.synchronize() }.exceptionOrNull() }
            gate.complete(Unit)

            for (outcome in listOf(first.await(), second.await())) {
                assertEquals(false, outcome is kotlinx.coroutines.CancellationException)
                assertEquals("сервер ответил не по-человечески", outcome?.message)
            }
            // Заход — один проход очереди; запросов у него больше: чтение клиент повторяет сам.
            assertEquals("заход был один на обоих", 1, rounds.count { it == "очередь" })
        } finally {
            scope.cancel()
        }
    }

    /**
     * Кончилась область приложения — кончился и заход: это единственная отмена, которая его
     * останавливает, и ждущие получают её как свою остановку.
     */
    @Test
    fun theEndOfTheApplicationScopeStopsTheRound() = kotlinx.coroutines.runBlocking {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val synchronization = synchronization(calls, gate = CompletableDeferred(), scope = scope)

        try {
            val waiting = async { kotlin.runCatching { synchronization.synchronize() }.exceptionOrNull() }
            until("заход дошёл до снимка") { "снимок" in calls }
            scope.cancel()

            assertEquals(true, waiting.await() is kotlinx.coroutines.CancellationException)
        } finally {
            scope.cancel()
        }
    }

    /** Не прочитали — кэш прежний, и время последнего успешного чтения не сдвигается (PLAN E4). */
    @Test
    fun aRoundThatCouldNotReadKeepsTheLastRefreshTime() = runTest {
        val synchronization = synchronization(ArrayList(), online = false, scope = backgroundScope)

        val round = synchronization.synchronize()

        assertEquals(SnapshotApplier.Outcome.Refused(Unavailability.NO_CONNECTION), round.snapshot)
        assertNull(synchronization.state.value.refreshedAt)
    }
}
