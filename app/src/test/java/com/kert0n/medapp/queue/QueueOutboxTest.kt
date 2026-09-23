package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.network.delivery.CourierDoor
import com.kert0n.medapp.network.delivery.MedAppCourier
import com.kert0n.medapp.network.delivery.MedAppPacking
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
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import io.ktor.client.engine.mock.MockEngine
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Outbox — единственный владелец прохода: он просыпается при старте, по сигналу таблицы и по
 * сроку повтора, сворачивает сигналы во время прохода в один и не даёт исключению прохода
 * уронить процесс (PLAN E4, F5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueOutboxTest {

    private val now: Instant = EARLIER.plusSeconds(3600)

    /**
     * Хранилище, у которого считают чтения готовых и которое ведёт себя как Room: сигналит на
     * свою же запись, прячет из `ready` операции с ненаступившим сроком и знает ближайший срок.
     */
    private class Storage : QueueStorage {
        val signals = MutableSharedFlow<Unit>()
        var reads = 0
        var broken = false
        val operations = mutableMapOf<Uuid, SyncOperation>()
        val settled = mutableListOf<Settlement>()

        // Первое значение — «наблюдатель встал», как у настоящего хранилища (OutboxLoop).
        override fun changes(): Flow<Unit> = signals.onStart { emit(Unit) }
        override suspend fun ready(now: Instant): List<StoredSyncOperation> {
            reads++
            if (broken) throw IllegalStateException("база недоступна")
            return operations.values
                .filter { !it.status.isClosed && (it.notBefore?.isAfter(now) != true) }
                .map { StoredSyncOperation.Readable(it) }
        }
        override suspend fun nextDueAt(now: Instant): Instant? =
            operations.values.filter { !it.status.isClosed }.mapNotNull { it.notBefore }.filter { it.isAfter(now) }.minOrNull()
        override suspend fun medKit(id: Uuid): MedKitRef? = null
        override suspend fun operation(id: Uuid): SyncOperation? = operations[id]
        override suspend fun knownPackage(id: Uuid): PackageSnapshot? = null
        override suspend fun layDown(snapshot: PackageSnapshot, at: Instant) = Unit
        override suspend fun write(operation: SyncOperation, was: SyncOperationStatus) { operations[operation.id] = operation }
        override suspend fun unclosedOfMedKit(medKitId: Uuid): List<StoredSyncOperation> = emptyList()
        override suspend fun answered(id: Uuid, answer: Receipt, at: Instant) = Unit
        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = Unit
        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) {
            settled += settlement
            val operation = operations.getValue(id)
            operations[id] = when (val transition = settlement.transition) {
                is Settlement.Transition.Retry -> operation.with(status = SyncOperationStatus.PENDING, notBefore = transition.notBefore)
                is Settlement.Transition.Close -> operation.with(status = transition.status, notBefore = null)
                is Settlement.Transition.Reprepare -> operation.with(status = SyncOperationStatus.PENDING, notBefore = transition.notBefore)
            }
            // Room сообщает об изменении таблицы и тогда, когда писал сам outbox.
            signals.emit(Unit)
        }

        private fun SyncOperation.with(status: SyncOperationStatus, notBefore: Instant?) = SyncOperation(
            id, command, sequence, createdAt, payloadVersion, prepared, groupId, dependsOn,
            status, attempts, lastError, lastTriedAt, answer, notBefore, outcomeUnknown
        )
        override suspend fun enqueue(queued: QueuedCommand, shelf: kotlin.uuid.Uuid, at: Instant): SyncOperation = error("не для этого теста")
    }

    private class Transport(private val answer: () -> ApiResult<RawResponse>) : CourierDoor {
        var sent = 0
        override suspend fun send(request: PreparedRequest): ApiResult<RawResponse> {
            sent++
            return answer()
        }
        override suspend fun packageSnapshot(packageId: Uuid): ApiResult<PackageSnapshotNetworkDTO> =
            ApiResult.Failure(ApiFailure.Unavailable)

        override suspend fun medKitIsOurs(medKitId: Uuid): ApiResult<Boolean> =
            ApiResult.Failure(ApiFailure.Unavailable)
    }

    /** Часы, которые тест двигает рукой вместе с виртуальным временем `runTest`. */
    private class TestClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private class Store : VocabularyStore {
        override suspend fun snapshot() = Vocabulary(emptyList(), emptyList())
        override suspend fun save(units: List<QuantityUnit>, forms: List<DosageForm>) = Unit
    }

    private fun worker(storage: Storage, transport: Transport, clock: Clock): QueueWorker {
        val vocabulary = VocabularyResolver(
            Store(),
            MedAppApi(medAppHttpClient(MockEngine { throw java.io.IOException("связи нет") }, "https://medapp.test", retryDelay = { delayMillis(false) { 0L } }))
        )
        return QueueWorker(storage, MedAppCourier(transport, vocabulary, PackageSnapshotResolver(vocabulary, storage), clock), MedAppPacking(), DirectTransactions, clock)
    }

    /** Запрос, замороженный раньше: работник шлёт его как есть, чтения перед подготовкой нет. */
    private fun sendingOperation(notBefore: Instant? = null) = SyncOperation(
        id = INTAKE,
        command = PackageSyncCommand.Consume(PACK, dose("1"), INTAKE),
        sequence = 0,
        createdAt = EARLIER,
        payloadVersion = 1,
        prepared = PreparedRequest("PUT", "/v1/drugs/$PACK/sync/$INTAKE", body = "{}", preparedAt = EARLIER),
        status = if (notBefore == null) SyncOperationStatus.SENDING else SyncOperationStatus.PENDING,
        notBefore = notBefore
    )

    @Test
    fun startingThePassRunsOnceAndThenWaitsForTheTable() = runTest {
        val storage = Storage()
        val outbox = QueueOutbox(worker(storage, Transport { ApiResult.Failure(ApiFailure.Unavailable) }, Clock.fixed(now, ZoneOffset.UTC)), storage, Clock.fixed(now, ZoneOffset.UTC), backgroundScope)

        outbox.start()
        runCurrent()

        assertEquals(1, storage.reads)
        assertEquals(1, outbox.state.value.passes)
        assertNull(outbox.state.value.lastFailure)
    }

    @Test
    fun aSignalFromTheTableStartsAPass() = runTest {
        val storage = Storage()
        val outbox = QueueOutbox(worker(storage, Transport { ApiResult.Failure(ApiFailure.Unavailable) }, Clock.fixed(now, ZoneOffset.UTC)), storage, Clock.fixed(now, ZoneOffset.UTC), backgroundScope)
        outbox.start()
        runCurrent()

        storage.signals.emit(Unit)
        runCurrent()

        assertEquals(2, outbox.state.value.passes)
    }

    /** Сигналы во время прохода — один следующий проход, а не по проходу на сигнал. */
    @Test
    fun signalsDuringAPassCollapseIntoOneMorePass() = runTest {
        val storage = Storage()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        storage.operations[INTAKE] = sendingOperation()
        val transport = Transport { ApiResult.Failure(ApiFailure.Unavailable) }
        val slow = object : CourierDoor by transport {
            override suspend fun send(request: PreparedRequest): ApiResult<RawResponse> {
                gate.await()
                return transport.send(request)
            }
        }
        val vocabulary = VocabularyResolver(Store(), MedAppApi(medAppHttpClient(MockEngine { throw java.io.IOException("связи нет") }, "https://medapp.test", retryDelay = { delayMillis(false) { 0L } })))
        val worker = QueueWorker(storage, MedAppCourier(slow, vocabulary, PackageSnapshotResolver(vocabulary, storage), Clock.fixed(now, ZoneOffset.UTC)), MedAppPacking(), DirectTransactions, Clock.fixed(now, ZoneOffset.UTC))
        val outbox = QueueOutbox(worker, storage, Clock.fixed(now, ZoneOffset.UTC), backgroundScope)
        outbox.start()
        runCurrent()
        assertEquals(0, outbox.state.value.passes)

        repeat(5) { storage.signals.emit(Unit) }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        // Первый проход дочитал очередь (после исхода она пуста) и один следующий проход — по сигналам.
        assertEquals(2, outbox.state.value.passes)
    }

    /**
     * Срок повтора — таймер, и живёт он в базе: запись `Retry` сама сигналит таблицей, следующий
     * проход ничего готового не находит — и всё равно приходит в срок, без нового сигнала.
     */
    @Test
    fun theRetryTermWakesThePassOnItsOwnEvenAfterAnEmptyPass() = runTest {
        val storage = Storage()
        storage.operations[INTAKE] = sendingOperation()
        val clock = TestClock(now)
        val transport = Transport { ApiResult.Failure(ApiFailure.Unavailable) }
        val outbox = QueueOutbox(worker(storage, transport, clock), storage, clock, backgroundScope)
        outbox.start()
        runCurrent()
        // Проход по своему же сигналу уже случился и готового не нашёл — срок должен пережить его.
        assertEquals(now.plusSeconds(2), outbox.state.value.nextRunAt)
        val sent = transport.sent

        clock.now = now.plusSeconds(1)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(sent, transport.sent)
        clock.now = now.plusSeconds(2)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(sent + 1, transport.sent)
    }

    /** При старте в базе лежит отложенная операция: готового нет, а прийти в срок всё равно надо. */
    @Test
    fun aDeferredOperationFoundAtStartIsSentWhenItsTermComes() = runTest {
        val storage = Storage()
        storage.operations[INTAKE] = sendingOperation(notBefore = now.plusSeconds(30))
        val clock = TestClock(now)
        val transport = Transport { ApiResult.Failure(ApiFailure.Unavailable) }
        val outbox = QueueOutbox(worker(storage, transport, clock), storage, clock, backgroundScope)
        outbox.start()
        runCurrent()
        assertEquals(0, transport.sent)
        assertEquals(now.plusSeconds(30), outbox.state.value.nextRunAt)

        clock.now = now.plusSeconds(31)
        advanceTimeBy(31_000)
        runCurrent()

        assertEquals(1, transport.sent)
    }

    /** База бросила мимо работника: процесс жив, сбой назван, очередь пробуется снова. */
    @Test
    fun aFailingPassDoesNotKillTheOutbox() = runTest {
        val storage = Storage()
        storage.broken = true
        val outbox = QueueOutbox(worker(storage, Transport { ApiResult.Failure(ApiFailure.Unavailable) }, Clock.fixed(now, ZoneOffset.UTC)), storage, Clock.fixed(now, ZoneOffset.UTC), backgroundScope)
        outbox.start()
        runCurrent()

        assertEquals(1, storage.reads)
        assertNotNull(outbox.state.value.lastFailure)

        storage.broken = false
        storage.signals.emit(Unit)
        runCurrent()

        assertEquals(2, outbox.state.value.passes)
        assertNull(outbox.state.value.lastFailure)
    }
}
