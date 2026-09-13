package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.value.Vocabulary
import org.junit.Assert.assertFalse
import java.math.BigDecimal
import com.kert0n.medapp.queue.pack.prepare
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.toDomain
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.fixture.EARLIER
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.medkit.toPreparedRequest
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.queue.pack.toPreparedRequest
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.RawResponse
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.network.server.medAppHttpClient
import com.kert0n.medapp.network.server.medAppJson
import com.kert0n.medapp.network.value.VocabularyMiss
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.VocabularyStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Работник отправляет замороженным запросом, читает исход и отпускает. Неопределённости нет:
 * обрыв — повтор тем же запросом, отказ — чтение истины снимком (PLAN E2, E3).
 */
class QueueWorkerTest {

    private val now: Instant = EARLIER.plusSeconds(3600)

    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val consume = PackageSyncCommand.Consume(PACK, dose("3"), INTAKE)

    private val sync = PackageSyncCommand.Consume(PACK, dose("3"), INTAKE, claimAfter = tablets("4"))

    private val snapshotJson = """
        {"drug":{"id":"$PACK","name":"Парацетамол","quantity":"17.000000","quantityUnitId":"${TABLETS.id}",
         "formTypeId":"${TABLET_FORM.id}","medKitId":"$HOME_KIT","version":4},
         "reservations":{"total":"4.000000","mine":"4.000000","version":2}}
    """

    private val snapshot: PackageSnapshotNetworkDTO =
        medAppJson.decodeFromString(PackageSnapshotNetworkDTO.serializer(), snapshotJson)

    /**
     * Очередь в памяти: операции, их запросы и исходы — ровно то, что видит работник. Состояние
     * пачки в «базе» — [known]: его переписывает свежий снимок при взятии и снимок из ответа при
     * закрытии, и по нему готовится запрос.
     */
    private class Storage(operations: List<SyncOperation>) : QueueStorage {
        val operations = operations.associateBy { it.id }.toMutableMap()
        val settled = mutableListOf<Pair<Uuid, Delivery>>()
        val unreadable = mutableListOf<StoredSyncOperation.Unreadable>()
        var frozen = 0
        var known = PackageSyncState(PACK, ResourceVersion(3))
        var knownPack: Package = pack(quantity = tablets("20"))
        val takenWith = mutableListOf<PackageSnapshot?>()

        /** Аптечки, которые «есть в базе»: снимок, называющий другую, положить некуда. */
        val knownMedKits = mutableSetOf(HOME_KIT)

        /** Снимок «лёг в базу»: версии, остаток и брони — те, что у сервера. */
        private fun learn(snapshot: PackageSnapshot) {
            known = snapshot.sync
            knownPack = snapshot.pack
        }

        override suspend fun medKit(id: Uuid): MedKitRef? =
            if (id in knownMedKits) medKit(id = id, publication = MedKit.Publication.PUBLISHED).ref else null

        val deferred = mutableListOf<Pair<Uuid, String>>()

        /** То же определение, что в SQL: срок наступил, зависимости применены, первая незакрытая по пачке. */
        override fun changes(): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun nextDueAt(now: Instant): Instant? =
            operations.values.filter { !it.status.isClosed }.mapNotNull { it.notBefore }.filter { it.isAfter(now) }.minOrNull()

        override suspend fun ready(now: Instant): List<StoredSyncOperation> =
            operations.values
                .filter { !it.status.isClosed }
                .filter { it.notBefore == null || !it.notBefore!!.isAfter(now) }
                .filter { op -> op.dependsOn.all { operations[it]?.status == SyncOperationStatus.APPLIED } }
                .filter { op ->
                    val pkg = (op.command as? PackageSyncCommand)?.packageId
                    pkg == null || operations.values.none {
                        (it.command as? PackageSyncCommand)?.packageId == pkg && it.sequence < op.sequence && !it.status.isClosed
                    }
                }
                .sortedBy { it.sequence }
                .map<SyncOperation, StoredSyncOperation> { StoredSyncOperation.Readable(it) } + unreadable

        /** Операции, на которых взятие бросает: база отказала, снимок не собрался — что угодно. */
        val takeFailsFor = mutableSetOf<Uuid>()

        override suspend fun take(id: Uuid, fresh: PackageSnapshot?, at: Instant): Take? {
            if (id in takeFailsFor) throw IllegalStateException("взятие $id сорвалось")
            val operation = operations[id] ?: return null
            if (operation.status.isClosed) return null
            takenWith += fresh
            fresh?.let(::learn)
            val prepared = operation.prepared ?: run {
                frozen++
                when (val command = operation.command) {
                    // У аптечки предусловий нет: замораживать нечего, кроме самого пути.
                    is MedKitSyncCommand -> command.toPreparedRequest(at)
                    is PackageSyncCommand -> when (val prepared = command.prepare(operation.id, knownPack, known, at)) {
                        is Preparation.Request -> prepared.request
                        is Preparation.Refuse -> return Take.Closed(Delivery.Refused(prepared.reason, PackageState.None)).also { settle(id, it.delivery.settlement(operation.command), at) }
                        Preparation.AlreadyApplied -> return Take.Closed(Delivery.Applied(PackageState.None)).also { settle(id, it.delivery.settlement(operation.command), at) }
                    }
                    else -> command.unknownRoot()
                }
            }
            // Операция, найденная в отправке, — прошлый полёт умер вместе с процессом: исход неизвестен.
            val flightLost = operation.status == SyncOperationStatus.SENDING && operation.prepared != null
            return Take.Sending(
                operation.with(status = SyncOperationStatus.SENDING, prepared = prepared, outcomeUnknown = operation.outcomeUnknown || flightLost)
                    .also { operations[id] = it }
            )
        }

        /** Ответ записан — а применение бросает: так ведёт себя сломанная транзакция закрытия. */
        var settleFails = false

        override suspend fun answered(id: Uuid, answer: RawResponse, at: Instant) {
            val operation = operations.getValue(id)
            operations[id] = operation.with(status = SyncOperationStatus.ANSWERED, answer = answer)
        }

        override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) {
            deferred += id to reason
            val operation = operations.getValue(id)
            operations[id] = operation.with(attempts = operation.attempts + 1, lastTriedAt = at, notBefore = notBefore)
        }


        override suspend fun enqueue(queued: QueuedCommand, shelf: kotlin.uuid.Uuid, at: Instant): SyncOperation =
            error("работник команд не ставит")

        override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) {
            if (settleFails && settlement.transition is Settlement.Transition.Close) throw IllegalStateException("закрытие сорвалось")
            val outcome = settlement.asDelivery()
            settled += id to outcome
            settlement.effects.filterIsInstance<Settlement.Effect.LayDown>().forEach { learn(it.snapshot) }
            val operation = operations.getValue(id)
            operations[id] = when (val transition = settlement.transition) {
                // Факт о запросе умирает вместе с запросом; счёт попыток остаётся у операции.
                is Settlement.Transition.Reprepare -> operation.with(
                    status = SyncOperationStatus.PENDING, dropPrepared = true, dropAnswer = true,
                    notBefore = transition.notBefore, dropNotBefore = transition.notBefore == null, outcomeUnknown = false
                )
                is Settlement.Transition.Retry -> operation.with(
                    status = SyncOperationStatus.PENDING,
                    attempts = operation.attempts + (if (transition.attempted) 1 else 0),
                    lastTriedAt = at, dropAnswer = true,
                    notBefore = transition.notBefore, dropNotBefore = transition.notBefore == null,
                    outcomeUnknown = operation.outcomeUnknown || transition.outcomeUnknown
                )
                // Как Room: закрытие попытки не считает — закрытая операция не повторяется.
                is Settlement.Transition.Close -> operation.with(
                    status = transition.status, lastTriedAt = at, dropAnswer = true, dropNotBefore = true,
                    refusalReason = transition.refusalReason
                )
            }
        }

        /** Решение очереди, прочитанное обратно как исход: тесты говорят на языке `Delivery`. */
        private fun Settlement.asDelivery(): Delivery {
            val state = effects.firstNotNullOfOrNull {
                when (it) {
                    is Settlement.Effect.LayDown -> PackageState.Present(it.snapshot)
                    // «Ушла туда, где нас нет» и «её нет» для базы одно и то же: коробка кончается.
                    is Settlement.Effect.PackageEnded -> PackageState.Gone
                    else -> null
                }
            } ?: PackageState.None
            return when (val transition = transition) {
                is Settlement.Transition.Retry ->
                    Delivery.Retry(transition.lastError, transition.notBefore, transition.attempted, transition.outcomeUnknown)
                is Settlement.Transition.Reprepare -> Delivery.Stale((state as PackageState.Present).snapshot, transition.notBefore)
                is Settlement.Transition.Close -> when (transition.status) {
                    SyncOperationStatus.APPLIED -> Delivery.Applied(state)
                    SyncOperationStatus.REFUSED -> Delivery.Refused(requireNotNull(transition.refusalReason), state)
                    SyncOperationStatus.ACCESS_LOST -> Delivery.AccessLost
                    else -> error("закрытие ведёт в закрытое состояние")
                }
            }
        }

        private fun SyncOperation.with(
            status: SyncOperationStatus = this.status,
            prepared: PreparedRequest? = this.prepared,
            attempts: Int = this.attempts,
            lastTriedAt: Instant? = this.lastTriedAt,
            answer: RawResponse? = this.answer,
            notBefore: Instant? = this.notBefore,
            dropPrepared: Boolean = false,
            dropAnswer: Boolean = false,
            dropNotBefore: Boolean = false,
            outcomeUnknown: Boolean = this.outcomeUnknown,
            refusalReason: RefusalReason? = this.refusalReason
        ) = SyncOperation(
            id, command, sequence, createdAt, payloadVersion, if (dropPrepared) null else prepared, groupId, dependsOn,
            status, attempts, lastError, lastTriedAt, if (dropAnswer) null else answer, if (dropNotBefore) null else notBefore,
            outcomeUnknown = outcomeUnknown, refusalReason = refusalReason
        )
    }

    private class Transport(private val answer: (PreparedRequest) -> ApiResult<RawResponse>) : QueueTransport {
        val sent = mutableListOf<PreparedRequest>()
        var snapshots = 0
        var snapshotAnswer: ApiResult<PackageSnapshotNetworkDTO>? = null
        /** Снимок для чтения перед подготовкой; `null` — в этом тесте такого чтения не ждут. */
        var fresh: ApiResult<PackageSnapshotNetworkDTO>? = null

        override suspend fun send(request: PreparedRequest): ApiResult<RawResponse> {
            sent += request
            return answer(request)
        }

        /** Первое чтение — перед подготовкой ([fresh]); дальнейшие — истина после ответа ([snapshotAnswer]). */
        override suspend fun packageSnapshot(packageId: Uuid): ApiResult<PackageSnapshotNetworkDTO> {
            snapshots++
            val answer = if (snapshots == 1) fresh ?: snapshotAnswer else snapshotAnswer ?: fresh
            return requireNotNull(answer) { "снимок в этом тесте не ожидался" }
        }

        /** Чем кончится проверка занятого номера аптечки; счётчик — чтобы видеть, что её сделали. */
        var medKitIsOurs: ApiResult<Boolean> = ApiResult.Success(true)
        var medKitReads = 0

        override suspend fun medKitIsOurs(medKitId: Uuid): ApiResult<Boolean> {
            medKitReads++
            return medKitIsOurs
        }
    }

    /** Словарь в памяти: знает таблетки, а что дочитал — запоминает. */
    private class Store : VocabularyStore {
        var refreshed = 0
        private var words = Vocabulary(listOf(TABLETS), listOf(TABLET_FORM))
        override suspend fun snapshot() = words
        override suspend fun save(units: List<com.kert0n.medapp.domain.value.QuantityUnit>, forms: List<com.kert0n.medapp.domain.value.DosageForm>) {
            refreshed++
            words = Vocabulary(listOf(TABLETS) + units, listOf(TABLET_FORM) + forms)
        }
    }

    private val store = Store()

    private fun resolver(online: Boolean) = VocabularyResolver(
        store,
        MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    if (!online) throw java.io.IOException("связи нет")
                    // Сервер знает и миллилитры: то, чего в снимке устройства ещё нет.
                    val body = if (request.url.encodedPath.endsWith("/quantity-units")) {
                        """[{"id":"${MILLILITRES.id}","name":"${MILLILITRES.name}"}]"""
                    } else {
                        "[]"
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://medapp.test",
                retryDelay = { delayMillis(false) { 0L } }
            )
        )
    )

    private fun operation(
        command: SyncCommand = consume,
        id: Uuid = INTAKE,
        sequence: Long = 0,
        attempts: Int = 0,
        lastTriedAt: Instant? = null,
        status: SyncOperationStatus = SyncOperationStatus.PENDING,
        prepared: PreparedRequest? = null,
        outcomeUnknown: Boolean = false
    ) = SyncOperation(
        id = id, command = command, sequence = sequence, createdAt = EARLIER, payloadVersion = 1,
        prepared = prepared, status = status, attempts = attempts, lastTriedAt = lastTriedAt,
        outcomeUnknown = outcomeUnknown
    )

    /** Снимок с другой версией пачки: то, что сервер знает сейчас, а устройство — ещё нет. */
    private fun snapshotWithVersion(version: Long): PackageSnapshotNetworkDTO =
        medAppJson.decodeFromString(
            PackageSnapshotNetworkDTO.serializer(),
            snapshotJson.replace("\"version\":4", "\"version\":$version")
        )

    /** Транспорт, у которого чтение перед подготовкой отвечает снимком [fresh]. */
    private fun transport(fresh: PackageSnapshotNetworkDTO = snapshot, answer: (PreparedRequest) -> ApiResult<RawResponse>) =
        Transport(answer).also { it.fresh = ApiResult.Success(fresh) }

    private fun worker(storage: Storage, transport: QueueTransport, online: Boolean = true, clock: Clock = this.clock) =
        QueueWorker(storage, transport, resolver(online), PackageSnapshotResolver(resolver(online), storage), clock)

    /** Снимок, каким его положит хранение: разрешённый, с домашней аптечкой. */
    private fun resolved(dto: PackageSnapshotNetworkDTO): PackageSnapshot =
        dto.toDomain(Vocabulary(listOf(TABLETS), listOf(TABLET_FORM)), medKit(id = HOME_KIT, publication = MedKit.Publication.PUBLISHED).ref, now, now)

    @Test
    fun pendingOperationIsSentAndSettledDoneWithTheSnapshot() = runTest {
        val storage = Storage(listOf(operation()))
        val transport = transport { ApiResult.Success(RawResponse(200, snapshotJson)) }

        val report = worker(storage, transport).drain()

        assertEquals(1, report.settled)
        // Внеплановый расход — тоже `sync` под своим номером: у него есть номер, и повтор
        // под ним сервер применит один раз (решение владельца, PLAN B4).
        assertEquals("PUT", transport.sent.single().method)
        assertEquals("/v1/drugs/$PACK/sync/$INTAKE", transport.sent.single().path)
        assertFalse(transport.sent.single().body!!.contains("reservation"))
        assertEquals(Delivery.Applied(PackageState.Present(resolved(snapshot))), storage.settled.single().second)
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)
        assertNull(report.retryAt)
    }

    /** Предусловие — то, что у сервера сейчас, а не то, что устройство видело когда-то (PLAN E2, E3). */
    @Test
    fun requestIsPreparedFromTheStateJustReadNotFromTheStoredRow() = runTest {
        val storage = Storage(listOf(operation()))
        val transport = transport(fresh = snapshotWithVersion(7)) { ApiResult.Success(RawResponse(200, snapshotJson)) }

        worker(storage, transport).drain()

        assertEquals(1, transport.snapshots)
        assertEquals(ResourceVersion(7), transport.sent.single().drugVersion)
        assertTrue(transport.sent.single().body!!.contains("\"drugVersion\":7"))
        assertEquals(resolved(snapshotWithVersion(7)), storage.takenWith.single())
    }

    /** Ответ на первую операцию пачки уже лёг в базу — вторая готовится по нему, без второго чтения. */
    @Test
    fun theNextOperationOfThePackageIsPreparedFromTheAnswerOfThePrevious() = runTest {
        val second = PackageSyncCommand.Consume(PACK, dose("1"), OTHER_PACK)
        val storage = Storage(listOf(operation(sequence = 0), operation(second, id = OTHER_PACK, sequence = 1)))
        val transport = transport(fresh = snapshotWithVersion(7)) { ApiResult.Success(RawResponse(200, snapshotJson.replace("\"version\":4", "\"version\":8"))) }

        val report = worker(storage, transport).drain()

        assertEquals(2, report.settled)
        assertEquals(1, transport.snapshots)
        assertEquals(listOf(ResourceVersion(7), ResourceVersion(8)), transport.sent.map { it.drugVersion })
        assertEquals(listOf(resolved(snapshotWithVersion(7)), null), storage.takenWith)
    }

    /** Отправка, пережившая смерть процесса: исход неизвестен, запрос уже заморожен — уходит как есть. */
    @Test
    fun aSendingSurvivorGoesOutAsItWasWithoutReadingFirst() = runTest {
        val frozen = consume.toPreparedRequest(INTAKE, PackageSyncState(PACK, ResourceVersion(3)), tablets("20"), null, EARLIER)
        val storage = Storage(listOf(operation(status = SyncOperationStatus.SENDING, prepared = frozen)))
        val transport = Transport { ApiResult.Success(RawResponse(200, snapshotJson)) }

        worker(storage, transport).drain()

        assertEquals(0, transport.snapshots)
        assertSame(frozen, transport.sent.single())
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)
    }

    /** Пачки на сервере нет уже при чтении: доступа к ней нет, и отправлять нечего. */
    @Test
    fun packageGoneBeforeTheReadClosesAsAccessLostWithoutSending() = runTest {
        val storage = Storage(listOf(operation()))
        val transport = Transport { ApiResult.Success(RawResponse(200, snapshotJson)) }
        transport.fresh = ApiResult.Failure(ApiFailure.NotFound)

        worker(storage, transport).drain()

        assertTrue(transport.sent.isEmpty())
        assertEquals(Delivery.AccessLost, storage.settled.single().second)
    }

    /**
     * Последняя доза: расход списал пачку до нуля, сервер её уничтожил, ответ потерялся — и повтор
     * отвечает 404 (PLAN B4). Пачки нет по нашей же причине, значит расход применён, а доступ не
     * утрачен.
     */
    @Test
    fun aRepeatedConsumptionThatEmptiedThePackageIsAppliedNotLost() = runTest {
        // Операция застала смерть процесса в отправке: исход прошлого полёта неизвестен.
        val everything = PackageSyncCommand.Consume(PACK, dose("20"), INTAKE)
        val frozen = everything.toPreparedRequest(
            INTAKE, PackageSyncState(PACK, ResourceVersion(3)), tablets("20"), null, EARLIER
        )
        val storage = Storage(
            listOf(operation(everything, status = SyncOperationStatus.SENDING, prepared = frozen))
        )
        val transport = Transport { ApiResult.Failure(ApiFailure.NotFound) }

        worker(storage, transport).drain()

        assertEquals(Delivery.Applied(PackageState.Gone), storage.settled.single().second)
    }

    /**
     * Факт «исход неизвестен» принадлежит запросу, а не операции: после переподготовки уходит
     * **другой** запрос, и 404 на нём — не наш расход, дошедший до нуля, а исчезнувшая пачка.
     * Иначе прошлый неизвестный исход подписывал бы применение тому, чего не было.
     */
    @Test
    fun aRepreparedConsumptionDoesNotInheritTheUnknownOutcomeOfTheOldRequest() = runTest {
        val everything = PackageSyncCommand.Consume(PACK, dose("20"), INTAKE)
        val frozen = everything.toPreparedRequest(
            INTAKE, PackageSyncState(PACK, ResourceVersion(3)), tablets("20"), null, EARLIER
        )
        val storage = Storage(
            listOf(operation(everything, status = SyncOperationStatus.SENDING, attempts = 1, prepared = frozen, outcomeUnknown = true))
        )
        var attempts = 0
        val transport = transport(fresh = snapshotWithVersion(7)) {
            attempts++
            // Первая отправка — тот же замороженный запрос: версия устарела. Вторая — уже другой.
            if (attempts == 1) ApiResult.Failure(ApiFailure.PreconditionFailed) else ApiResult.Failure(ApiFailure.NotFound)
        }
        transport.snapshotAnswer = ApiResult.Success(snapshotWithVersion(7))

        worker(storage, transport).drain()

        assertEquals(2, transport.sent.size)
        assertEquals(Delivery.Stale(resolved(snapshotWithVersion(7))), storage.settled[0].second)
        assertEquals(Delivery.AccessLost, storage.settled[1].second)
    }

    /**
     * 429 — исход известен: сервер запрос не применил. Пачка, исчезнувшая до повтора, исчезла не
     * по нашей причине, и 404 на повторе — утрата доступа, а не наш расход до нуля. Число попыток
     * тут ни при чём: решает факт «исход прошлой отправки неизвестен», а его нет.
     */
    @Test
    fun aConsumptionRateLimitedAndThenNotFoundIsAccessLost() = runTest {
        val everything = PackageSyncCommand.Consume(PACK, dose("20"), INTAKE)
        val storage = Storage(listOf(operation(everything)))
        var limited = true
        val transport = transport {
            if (limited) ApiResult.Failure(ApiFailure.TooManyRequests(30.seconds)) else ApiResult.Failure(ApiFailure.NotFound)
        }
        worker(storage, transport).drain()
        assertFalse(storage.operations.getValue(INTAKE).outcomeUnknown)

        limited = false
        worker(storage, transport, clock = Clock.fixed(now.plusSeconds(31), ZoneOffset.UTC)).drain()

        assertEquals(2, transport.sent.size)
        assertEquals(Delivery.AccessLost, storage.settled.last().second)
    }

    /** Потерянный ответ — исход неизвестен: 404 на повторе того же запроса — наш расход до нуля. */
    @Test
    fun aConsumptionWithALostAnswerAndThenNotFoundIsAppliedAsGone() = runTest {
        val everything = PackageSyncCommand.Consume(PACK, dose("20"), INTAKE)
        val storage = Storage(listOf(operation(everything)))
        var lost = true
        val transport = transport {
            if (lost) ApiResult.Failure(ApiFailure.OutcomeUnknown) else ApiResult.Failure(ApiFailure.NotFound)
        }
        worker(storage, transport).drain()
        assertTrue(storage.operations.getValue(INTAKE).outcomeUnknown)

        lost = false
        worker(storage, transport, clock = Clock.fixed(now.plusSeconds(31), ZoneOffset.UTC)).drain()

        assertEquals(2, transport.sent.size)
        assertSame(transport.sent[0], transport.sent[1])
        assertEquals(Delivery.Applied(PackageState.Gone), storage.settled.last().second)
    }

    /** 404 на первой же отправке — пачки нет не по нашей причине: доступ утрачен. */
    @Test
    fun aFirstConsumptionMeetingANotFoundIsAccessLost() = runTest {
        val everything = PackageSyncCommand.Consume(PACK, dose("20"), INTAKE)
        val storage = Storage(listOf(operation(everything)))
        val transport = transport { ApiResult.Failure(ApiFailure.NotFound) }

        worker(storage, transport).drain()

        assertEquals(Delivery.AccessLost, storage.settled.single().second)
    }

    /**
     * 412 у `sync` — версия устарела, запрос отвергнут до применения (PLAN B3, E3): состояние
     * читается и ложится в базу, запрос готовится заново под тем же номером и уходит тем же
     * проходом — со свежей версией.
     */
    @Test
    fun staleSyncIsRepreparedFromTheFreshStateUnderTheSameNumber() = runTest {
        val storage = Storage(listOf(operation(sync)))
        var attempts = 0
        val transport = transport(fresh = snapshotWithVersion(3)) {
            attempts++
            if (attempts == 1) ApiResult.Failure(ApiFailure.PreconditionFailed) else ApiResult.Success(RawResponse(200, snapshotJson.replace("\"version\":4", "\"version\":8")))
        }
        transport.snapshotAnswer = ApiResult.Success(snapshotWithVersion(7))

        val report = worker(storage, transport).drain()

        assertEquals(1, report.settled)
        assertEquals(Delivery.Stale(resolved(snapshotWithVersion(7))), storage.settled[0].second)
        assertEquals(Delivery.Applied(PackageState.Present(resolved(snapshotWithVersion(8)))), storage.settled[1].second)
        assertEquals(2, transport.sent.size)
        assertTrue(transport.sent.all { it.path.endsWith("/sync/$INTAKE") })
        assertEquals(listOf(ResourceVersion(3), ResourceVersion(7)), transport.sent.map { it.drugVersion })
        // Тело то же — меняется только версия: иначе журнал ответил бы 409 навсегда.
        assertEquals(
            transport.sent[0].body!!.replace("\"drugVersion\":3", "\"drugVersion\":7"),
            transport.sent[1].body
        )
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)
    }

    /** Потерянный ответ, за которым пришёл 412: своя бронь уже равна заявленной — расход применён. */
    @Test
    fun staleSyncWhoseClaimAlreadyMatchesIsAppliedWithoutResending() = runTest {
        val frozen = sync.toPreparedRequest(INTAKE, PackageSyncState(PACK, ResourceVersion(3), ResourceVersion(1)), tablets("20"), tablets("7"), EARLIER)
        val storage = Storage(listOf(operation(sync, status = SyncOperationStatus.SENDING, prepared = frozen)))
        val transport = Transport { ApiResult.Failure(ApiFailure.PreconditionFailed) }
        transport.snapshotAnswer = ApiResult.Success(snapshot) // mine = 4 = claimAfter, было 7

        val report = worker(storage, transport).drain()

        assertEquals(1, report.settled)
        assertEquals(1, transport.sent.size)
        assertEquals(Delivery.Applied(PackageState.Present(resolved(snapshot))), storage.settled.single().second)
    }

    /**
     * 409 у `sync` — тот же номер с другим телом (PLAN B4, E3). Повторять нечем: журнал сервера
     * ответит так же всегда, а переподготовка тела не меняет — значит, это дефект, и операция
     * закрывается отказом, не уходя второй раз.
     */
    @Test
    fun aSyncUnderTheSameNumberWithAnotherBodyIsRefusedNotReprepared() = runTest {
        val storage = Storage(listOf(operation(sync)))
        val transport = transport(fresh = snapshotWithVersion(3)) { ApiResult.Failure(ApiFailure.Conflict) }
        transport.snapshotAnswer = ApiResult.Success(snapshot)

        worker(storage, transport).drain()

        assertEquals(1, transport.sent.size)
        assertEquals(
            Delivery.Refused(RefusalReason.INVALID, PackageState.Present(resolved(snapshot))),
            storage.settled.single().second
        )
        assertEquals(SyncOperationStatus.REFUSED, storage.operations.getValue(INTAKE).status)
    }

    /**
     * 412 у правки сведений — гонка: сосед выпил таблетку между чтением и отправкой. Истина
     * читается, правка готовится заново своими полями поверх неё и уходит тем же проходом — чужой
     * приём решение человека не сбрасывает (C1 «Действие над общей пачкой — разница»).
     */
    @Test
    fun staleDescriptionIsRepreparedOverTheFreshTruth() = runTest {
        val describe = PackageSyncCommand.Describe(
            PACK,
            com.kert0n.medapp.domain.pack.PackageSharedFacts("Парацетамол", TABLET_FORM),
            com.kert0n.medapp.domain.pack.PackageSharedFacts("Парацетамол 500", TABLET_FORM)
        )
        val storage = Storage(listOf(operation(describe)))
        var attempts = 0
        val transport = transport(fresh = snapshotWithVersion(3)) {
            attempts++
            if (attempts == 1) ApiResult.Failure(ApiFailure.PreconditionFailed) else ApiResult.Success(RawResponse(200, snapshotJson.replace("\"version\":4", "\"version\":8")))
        }
        transport.snapshotAnswer = ApiResult.Success(snapshotWithVersion(7))

        worker(storage, transport).drain()

        assertEquals(Delivery.Stale(resolved(snapshotWithVersion(7))), storage.settled[0].second)
        assertEquals(Delivery.Applied(PackageState.Present(resolved(snapshotWithVersion(8)))), storage.settled[1].second)
        assertEquals(listOf(ResourceVersion(3), ResourceVersion(7)), transport.sent.map { it.drugVersion })
        assertTrue(transport.sent.all { it.body!!.contains("Парацетамол 500") })
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)
    }

    /**
     * Сосед переименовал коробку иначе: то же поле изменено иначе, соотнести нельзя — подготовка
     * закрывает операцию отказом `CONFLICT`, и человек описывает ситуацию заново (C1).
     */
    @Test
    fun aDescriptionTheNeighbourChangedDifferentlyIsAConflictAtPreparation() = runTest {
        val describe = PackageSyncCommand.Describe(
            PACK,
            com.kert0n.medapp.domain.pack.PackageSharedFacts("Парацетамол", TABLET_FORM),
            com.kert0n.medapp.domain.pack.PackageSharedFacts("Парацетамол 500", TABLET_FORM)
        )
        val storage = Storage(listOf(operation(describe)))
        val renamed = medAppJson.decodeFromString(
            PackageSnapshotNetworkDTO.serializer(),
            snapshotJson.replace("Парацетамол", "Панадол")
        )
        val transport = transport(fresh = renamed) { error("несводимая правка на провод не идёт") }

        worker(storage, transport).drain()

        assertTrue(transport.sent.isEmpty())
        assertEquals(Delivery.Refused(RefusalReason.CONFLICT, PackageState.None), storage.settled.single().second)
        assertEquals(SyncOperationStatus.REFUSED, storage.operations.getValue(INTAKE).status)
    }

    /**
     * Пересчёт — разница поверх свежего числа: видел 20, назвал 17, а свежее чтение принесло 17 —
     * уходит 14. Ниже нуля свести нельзя: видел 20, назвал 2, а прочитано 17 — отказ подготовки.
     */
    @Test
    fun aRecountLaysItsDifferenceOverTheFreshNumberOrConflicts() = runTest {
        val recount = PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("17"))
        val storage = Storage(listOf(operation(recount)))
        val transport = transport(fresh = snapshotWithVersion(3)) {
            ApiResult.Success(RawResponse(200, snapshotJson.replace("17.000000", "14.000000")))
        }

        worker(storage, transport).drain()

        assertTrue(transport.sent.single().body!!.contains("\"quantity\":\"14"))
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)

        val tooMuch = PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("2"))
        val refusing = Storage(listOf(operation(tooMuch)))
        val silent = transport(fresh = snapshotWithVersion(3)) { error("несводимый пересчёт на провод не идёт") }

        worker(refusing, silent).drain()

        assertTrue(silent.sent.isEmpty())
        assertEquals(Delivery.Refused(RefusalReason.CONFLICT, PackageState.None), refusing.settled.single().second)
    }

    /**
     * Пересчёт 20 → 17 уехал, ответ потерян, повтор получил 412: у сервера 17 — ровно то, к чему вёл
     * запрос. Это применение, а не гонка: переподготовленная разница легла бы поверх себя, 17 → 14.
     * Если же число не то — гонка, и разница кладётся заново.
     */
    @Test
    fun aRecountWithALostAnswerThatAlreadyLandedIsNotAppliedTwice() = runTest {
        val recount = PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("17"))
        val frozen = recount.toPreparedRequest(INTAKE, PackageSyncState(PACK, ResourceVersion(3)), tablets("20"), null, EARLIER)
        val lost = Storage(listOf(operation(recount, status = SyncOperationStatus.SENDING, attempts = 1, prepared = frozen, outcomeUnknown = true)))
        val refusing = Transport { ApiResult.Failure(ApiFailure.PreconditionFailed) }
        refusing.snapshotAnswer = ApiResult.Success(snapshot) // у сервера 17

        worker(lost, refusing).drain()

        assertEquals(1, refusing.sent.size)
        assertEquals(Delivery.Applied(PackageState.Present(resolved(snapshot))), lost.settled.single().second)

        // Первая отправка по свежим 17 ведёт к 14, и 412 застаёт те же 17: запрос не применялся.
        val raced = Storage(listOf(operation(recount)))
        val again = transport(fresh = snapshot) { ApiResult.Failure(ApiFailure.PreconditionFailed) }
        again.snapshotAnswer = ApiResult.Success(snapshot)

        worker(raced, again).drain()

        assertEquals(Delivery.Stale(resolved(snapshot)), raced.settled.first().second)
    }

    @Test
    fun invalidInputIsRefusedAndTheTruthRead() = runTest {
        val describe = PackageSyncCommand.Describe(
            PACK,
            com.kert0n.medapp.domain.pack.PackageSharedFacts("Парацетамол", TABLET_FORM),
            com.kert0n.medapp.domain.pack.PackageSharedFacts("Парацетамол 500", TABLET_FORM)
        )
        val storage = Storage(listOf(operation(describe)))
        val transport = transport { ApiResult.Failure(ApiFailure.Invalid(emptyList())) }
        transport.snapshotAnswer = ApiResult.Success(snapshot)

        worker(storage, transport).drain()

        assertEquals(Delivery.Refused(RefusalReason.INVALID, PackageState.Present(resolved(snapshot))), storage.settled.single().second)
    }

    @Test
    fun retryAfterIsHonouredAndNothingElseIsSentInThatPass() = runTest {
        val storage = Storage(listOf(operation(sequence = 0), operation(id = OTHER_PACK, sequence = 1)))
        val transport = transport { ApiResult.Failure(ApiFailure.TooManyRequests(30.seconds)) }

        val report = worker(storage, transport).drain()

        assertEquals(1, transport.sent.size)
        assertEquals(now.plusSeconds(30), report.retryAt)
        assertEquals(0, report.settled)
        assertEquals(SyncOperationStatus.PENDING, storage.operations.getValue(INTAKE).status)
    }

    @Test
    fun aBrokenConnectionRetriesWithTheVerySamePreparedRequest() = runTest {
        // Запрос заморожен при первой отправке; на повторе он не пересобирается, даже если
        // версия пачки с тех пор изменилась бы (PLAN E2, E3).
        val storage = Storage(listOf(operation()))
        var broken = true
        val transport = transport { if (broken) ApiResult.Failure(ApiFailure.OutcomeUnknown) else ApiResult.Success(RawResponse(200, snapshotJson)) }

        val first = worker(storage, transport).drain()
        assertEquals(0, first.settled)
        // Срок повтора — в исходе и в базе, а не в памяти прохода: две секунды после первой неудачи.
        assertEquals(Delivery.Retry("ответ потерян", notBefore = now.plusSeconds(2), outcomeUnknown = true), storage.settled.single().second)
        assertEquals(SyncOperationStatus.PENDING, storage.operations.getValue(INTAKE).status)
        assertNotNull(first.retryAt)

        broken = false
        val second = worker(storage, transport).drain()
        assertEquals(0, second.settled) // задержка после первой попытки ещё не прошла
        val later = worker(storage, transport, clock = Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC))
        assertEquals(1, later.drain().settled)
        assertEquals(2, transport.sent.size)
        assertSame(transport.sent[0], transport.sent[1])
        assertEquals(1, storage.frozen)
    }

    /** Без связи — одна попытка соединения на весь проход, и она не считается попыткой операции. */
    @Test
    fun noConnectionStopsThePassAndIsNotAnAttempt() = runTest {
        val storage = Storage(
            (0 until 30).map { operation(PackageSyncCommand.Consume(PACK, dose("1"), Uuid.random()), id = Uuid.random(), sequence = it.toLong()) }
        )
        val transport = transport { ApiResult.Failure(ApiFailure.Unavailable) }

        worker(storage, transport).drain()

        assertEquals(1, transport.sent.size)
        assertTrue(storage.operations.values.all { it.attempts == 0 })
        assertEquals(listOf(now.plusSeconds(2)), storage.operations.values.mapNotNull { it.notBefore })
    }

    /**
     * Окончательный отказ пропуска — это срок, а не новый круг. HTTP-слой уже перевыпустил токен
     * и повторил один раз (PLAN B5); дошедший сюда 401 значит «этой учётке сервер не отвечает».
     * Операция должна ждать по задержке: без неё `take` оставляет строку `SENDING` без
     * `not_before`, Room сигналит о собственной записи, outbox будит проход — и тот отправляет
     * снова, без движения времени.
     *
     * Красная проверка: вернуть остановку прохода без записи исхода — `notBefore` пуст.
     */
    @Test
    fun aFinalAuthorizationRefusalWaitsInsteadOfLooping() = runTest {
        val storage = Storage(listOf(operation(PackageSyncCommand.Consume(PACK, dose("1"), INTAKE))))
        val transport = transport { ApiResult.Failure(ApiFailure.Unauthorized) }

        worker(storage, transport).drain()

        assertEquals(1, transport.sent.size)
        val stored = storage.operations.values.single()
        assertEquals(SyncOperationStatus.PENDING, stored.status)
        assertEquals(now.plusSeconds(2), stored.notBefore)
        // Готовой раньше срока она не станет, и второй проход её не берёт.
        worker(storage, transport).drain()
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun unreadableRowIsSkippedAndNamed() = runTest {
        val storage = Storage(emptyList())
        val broken = StoredSyncOperation.Unreadable(OTHER_PACK, StoredSyncOperation.Reason.Format("payload не разбирается"))
        storage.unreadable += broken
        val transport = transport { ApiResult.Success(RawResponse(200, snapshotJson)) }

        val report = worker(storage, transport).drain()

        assertEquals(listOf(broken), report.skipped)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun staleVocabularyIsReadOnceAndThePassRestarts() = runTest {
        val storage = Storage(emptyList())
        val miss = VocabularyMiss(VocabularyMiss.Kind.UNIT, MILLILITRES.id)
        storage.unreadable += StoredSyncOperation.Unreadable(OTHER_PACK, StoredSyncOperation.Reason.VocabularyStale(miss))
        val transport = transport { ApiResult.Success(RawResponse(200, snapshotJson)) }

        val report = worker(storage, transport, online = true).drain()

        assertEquals(1, store.refreshed)
        // Словарь дочитан, строка всё ещё не читается — второй проход её уже пропускает.
        assertEquals(1, report.skipped.size)
    }

    /** Ответ не по форме — сбой протокола, а не «пустая пачка» и не исключение: повтор тем же запросом. */
    @Test
    fun answerOutOfShapeIsRetriedAndNothingIsApplied() = runTest {
        val storage = Storage(listOf(operation()))
        val transport = transport { ApiResult.Success(RawResponse(200, "<html>прокси</html>")) }

        val report = worker(storage, transport).drain()

        assertEquals(0, report.settled)
        assertTrue((storage.settled.single().second as Delivery.Retry).error.startsWith("ответ не разбирается"))
        assertEquals(SyncOperationStatus.PENDING, storage.operations.getValue(INTAKE).status)
    }

    /** 404 у снятия брони — брони уже нет: желаемое наступило, а пачка на месте и доступ не потерян. */
    @Test
    fun missingClaimOnReleaseIsAppliedAndDoesNotMarkThePackage() = runTest {
        val storage = Storage(listOf(operation(PackageSyncCommand.ReleaseClaim(PACK))))
        val transport = transport { ApiResult.Failure(ApiFailure.NotFound) }
        transport.snapshotAnswer = ApiResult.Success(snapshot)

        worker(storage, transport).drain()

        assertEquals(Delivery.Applied(PackageState.Present(resolved(snapshot))), storage.settled.single().second)
    }

    /** 409 на заявлении брони — она уже есть: по свежему `mine` та же команда становится правкой. */
    @Test
    fun claimAlreadyDeclaredIsRepreparedAsAPatch() = runTest {
        val storage = Storage(listOf(operation(PackageSyncCommand.SetClaim(PACK, tablets("6")))))
        val noClaim = medAppJson.decodeFromString(
            PackageSnapshotNetworkDTO.serializer(), snapshotJson.replace(",\"mine\":\"4.000000\"", "")
        )
        var attempts = 0
        val transport = transport(fresh = noClaim) {
            attempts++
            if (attempts == 1) ApiResult.Failure(ApiFailure.Conflict)
            else ApiResult.Success(RawResponse(201, """{"drugId":"$PACK","amount":"6.000000"}"""))
        }
        transport.snapshotAnswer = ApiResult.Success(snapshot)

        val report = worker(storage, transport).drain()

        assertEquals(1, report.settled)
        assertEquals(listOf("POST", "PATCH"), transport.sent.map { it.method })
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)
    }

    /** 400 на создании — пачка не заведена, читать нечего и терять доступ не к чему. */
    @Test
    fun invalidCreateIsRefusedWithoutTouchingThePackage() = runTest {
        val create = PackageSyncCommand.Create(PACK, HOME_KIT)
        val storage = Storage(listOf(operation(create)))
        val transport = Transport { ApiResult.Failure(ApiFailure.Invalid(emptyList())) }

        worker(storage, transport).drain()

        assertEquals(0, transport.snapshots)
        assertEquals(Delivery.Refused(RefusalReason.INVALID, PackageState.None), storage.settled.single().second)
    }

    /** 409 на создании — пачка с нашим номером уже есть: читаем, она наша, значит уже применено. */
    @Test
    fun createUnderAnIdentifierThatIsAlreadyOursIsApplied() = runTest {
        val create = PackageSyncCommand.Create(PACK, HOME_KIT)
        val storage = Storage(listOf(operation(create)))
        val transport = Transport { ApiResult.Failure(ApiFailure.Conflict) }
        transport.snapshotAnswer = ApiResult.Success(snapshot)

        worker(storage, transport).drain()

        assertEquals(Delivery.Applied(PackageState.Present(resolved(snapshot))), storage.settled.single().second)
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)
    }

    /**
     * 404 на создании — не стало **полки**, куда коробку кладут: сосед удалил её, пока команда
     * ждала связи. Сама коробка при этом лежит у человека дома, поэтому это отказ, а не утрата
     * доступа, и коробка возвращается на ту полку, с которой её принесли (PLAN E6).
     */
    @Test
    fun aBoxWhoseTargetShelfIsGoneComesBackInsteadOfEnding() = runTest {
        val create = PackageSyncCommand.Create(PACK, SHARED_KIT, fromMedKitId = HOME_KIT)
        val storage = Storage(listOf(operation(create)))
        val transport = Transport { ApiResult.Failure(ApiFailure.NotFound) }

        worker(storage, transport).drain()

        val settlement = storage.settled.single().second.settlement(create)
        assertEquals(Delivery.Refused(RefusalReason.STALE, PackageState.None), storage.settled.single().second)
        assertTrue(
            "коробка не возвращена: ${settlement.effects}",
            Settlement.Effect.Returned(PACK, HOME_KIT) in settlement.effects
        )
    }

    /**
     * 409 на команде аптечки — номер занят, и сам по себе он не значит «наше»: аптечка читается, и
     * только увиденная своя делает желаемое сбывшимся (PLAN C0, E3).
     */
    @Test
    fun aTakenMedKitIdentifierIsExplainedByReadingIt() = runTest {
        val storage = Storage(listOf(operation(MedKitSyncCommand.Publish(HOME_KIT))))
        val transport = Transport { ApiResult.Failure(ApiFailure.Conflict) }

        worker(storage, transport).drain()

        assertEquals(1, transport.medKitReads)
        assertEquals(Delivery.Applied(PackageState.None), storage.settled.single().second)
    }

    /**
     * Номер занят чужой полкой. Объявить это успешной публикацией нельзя: мы начали бы класть
     * коробки в полку, которой не видим (PLAN C0).
     */
    @Test
    fun aMedKitIdentifierTakenBySomebodyElseIsRefused() = runTest {
        val storage = Storage(listOf(operation(MedKitSyncCommand.Publish(HOME_KIT))))
        val transport = Transport { ApiResult.Failure(ApiFailure.Conflict) }
        transport.medKitIsOurs = ApiResult.Success(false)

        worker(storage, transport).drain()

        assertEquals(Delivery.Refused(RefusalReason.INVALID, PackageState.None), storage.settled.single().second)
    }

    /** Проверку не дочитали — повтор тем же запросом: он снова даст 409, и вопрос зададут заново. */
    @Test
    fun aTakenMedKitIdentifierThatCouldNotBeCheckedWaitsForARetry() = runTest {
        val storage = Storage(listOf(operation(MedKitSyncCommand.Publish(HOME_KIT))))
        val transport = Transport { ApiResult.Failure(ApiFailure.Conflict) }
        transport.medKitIsOurs = ApiResult.Failure(ApiFailure.Unavailable)

        worker(storage, transport).drain()

        assertEquals(SyncOperationStatus.PENDING, storage.operations.getValue(INTAKE).status)
    }

    /** Расход больше остатка — отказ по количеству, пачка остаётся какой её знает сервер: удалять её нечем. */
    @Test
    fun consumeBeyondTheStockIsRefusedAsInsufficientAndThePackageStays() = runTest {
        val storage = Storage(listOf(operation()))
        val transport = transport { ApiResult.Failure(ApiFailure.Invalid(emptyList())) }
        transport.snapshotAnswer = ApiResult.Success(snapshot)

        worker(storage, transport).drain()

        assertEquals(Delivery.Refused(RefusalReason.INSUFFICIENT, PackageState.Present(resolved(snapshot))), storage.settled.single().second)
    }

    /** Единицу пачки сменили: дозу в прежней единице на провод не везут — единицы там нет. */
    @Test
    fun consumeInAUnitThePackageNoLongerUsesIsRefusedBeforeSending() = runTest {
        val inMillilitres = PackageSyncCommand.Consume(PACK, dose(com.kert0n.medapp.fixture.millilitres("5")), INTAKE)
        val storage = Storage(listOf(operation(inMillilitres)))
        val transport = transport { ApiResult.Success(RawResponse(200, snapshotJson)) }

        val report = worker(storage, transport).drain()

        assertTrue(transport.sent.isEmpty())
        assertEquals(1, report.settled)
        assertEquals(Delivery.Refused(RefusalReason.UNIT_CHANGED, PackageState.None), storage.settled.single().second)
    }

    @Test
    fun deletingWhatIsAlreadyGoneIsApplied() = runTest {
        val storage = Storage(listOf(operation(PackageSyncCommand.Delete(PACK))))
        val transport = transport { ApiResult.Failure(ApiFailure.NotFound) }

        worker(storage, transport).drain()

        assertEquals(Delivery.Applied(PackageState.Gone), storage.settled.single().second)
    }

    /**
     * Ответ получен, а единицы в нём словарь не знает и дочитать его нечем: операция ждёт с
     * ответом в руках и закрывается из него при следующей связи — без второго запроса.
     */
    @Test
    fun anAnswerThatCannotBeAppliedYetIsKeptAndSettledLaterWithoutResending() = runTest {
        val storage = Storage(listOf(operation()))
        val newUnit = snapshotJson.replace(TABLETS.id.toString(), MILLILITRES.id.toString())
        val transport = transport { ApiResult.Success(RawResponse(200, newUnit)) }

        val first = worker(storage, transport, online = false).drain()

        assertEquals(0, first.settled)
        assertEquals(SyncOperationStatus.ANSWERED, storage.operations.getValue(INTAKE).status)
        assertEquals(RawResponse(200, newUnit), storage.operations.getValue(INTAKE).answer)
        assertEquals(1, storage.deferred.size)
        assertTrue(storage.settled.isEmpty())

        val later = worker(storage, transport, clock = Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC))
        val second = later.drain()

        assertEquals(1, second.settled)
        assertEquals(1, transport.sent.size)
        assertEquals(1, store.refreshed)
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)
    }

    /**
     * Сосед перенёс пачку в аптечку, которой у нас нет. Это не промах словаря: ждать нечего, коробка
     * ушла туда, где нас нет. Команда применена, а коробка у нас кончается утратой доступа —
     * операция закрыта, а не отложена навсегда, и проход идёт дальше (PLAN E3, E6).
     */
    @Test
    fun aSnapshotNamingAnUnknownMedKitClosesTheOperationAndThePassGoesOn() = runTest {
        val storage = Storage(listOf(operation(sequence = 0), operation(id = OTHER_PACK, sequence = 1, command = PackageSyncCommand.Consume(OTHER_PACK, dose("1"), OTHER_PACK))))
        val elsewhere = snapshotJson.replace(HOME_KIT.toString(), SHARED_KIT.toString())
        val transport = transport { request ->
            if (request.path.contains(PACK.toString())) ApiResult.Success(RawResponse(200, elsewhere))
            else ApiResult.Success(RawResponse(200, snapshotJson.replace(PACK.toString(), OTHER_PACK.toString())))
        }

        val report = worker(storage, transport).drain()

        assertEquals(2, report.settled)
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(INTAKE).status)
        assertEquals(Delivery.Applied(PackageState.Gone), storage.settled.first { it.first == INTAKE }.second)
        assertTrue(storage.deferred.isEmpty())
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(OTHER_PACK).status)
        assertEquals(0, store.refreshed)
    }

    /**
     * Шаг одной операции бросил — база отказала при взятии. Проход не умирает: операция помечена
     * на повтор с задержкой и названа в отчёте, а соседняя операция обработана.
     */
    @Test
    fun aFailingStepMarksTheOperationNamesItAndThePassGoesOn() = runTest {
        val storage = Storage(listOf(operation(sequence = 0), operation(id = OTHER_PACK, sequence = 1, command = PackageSyncCommand.Consume(OTHER_PACK, dose("1"), OTHER_PACK))))
        storage.takeFailsFor += INTAKE
        val transport = transport { ApiResult.Success(RawResponse(200, snapshotJson.replace(PACK.toString(), OTHER_PACK.toString()))) }

        val report = worker(storage, transport).drain()

        assertEquals(1, report.settled)
        assertEquals(listOf(INTAKE), report.failed.map { it.id })
        assertTrue(report.failed.single().reason, report.failed.single().reason.startsWith("сбой прохода"))
        val marked = storage.operations.getValue(INTAKE)
        assertEquals(SyncOperationStatus.PENDING, marked.status)
        assertEquals(now.plusSeconds(2), marked.notBefore)
        assertEquals(SyncOperationStatus.APPLIED, storage.operations.getValue(OTHER_PACK).status)
    }

    /** Ответ уже записан, а закрытие бросило: операция ждёт с ответом в руках, а не уходит на повтор. */
    @Test
    fun aFailureAfterTheAnswerIsRecordedLeavesTheOperationAnswered() = runTest {
        val storage = Storage(listOf(operation()))
        storage.settleFails = true
        val transport = transport { ApiResult.Success(RawResponse(200, snapshotJson)) }

        val report = worker(storage, transport).drain()

        assertEquals(listOf(INTAKE), report.failed.map { it.id })
        assertEquals(SyncOperationStatus.ANSWERED, storage.operations.getValue(INTAKE).status)
        assertEquals(1, storage.deferred.size)
        assertEquals(1, transport.sent.size)
    }

    /** Ответ, записанный до смерти процесса, закрывается из записи: сервер о нём не спрашивают. */
    @Test
    fun aRecordedAnswerIsSettledFromTheRecordAfterARestart() = runTest {
        val frozen = consume.toPreparedRequest(INTAKE, PackageSyncState(PACK, ResourceVersion(3)), tablets("20"), null, EARLIER)
        val storage = Storage(listOf(
            SyncOperation(
                id = INTAKE, command = consume, sequence = 0, createdAt = EARLIER, payloadVersion = 1, prepared = frozen,
                status = SyncOperationStatus.ANSWERED, answer = RawResponse(200, snapshotJson)
            )
        ))
        val transport = Transport { error("отправки быть не должно") }

        val report = worker(storage, transport).drain()

        assertEquals(1, report.settled)
        assertTrue(transport.sent.isEmpty())
        assertEquals(Delivery.Applied(PackageState.Present(resolved(snapshot))), storage.settled.single().second)
    }

    /** Второй офлайн-приём той же пачки не уходит, пока первый ждёт срока: порядок по пачке — в определении готовности. */
    @Test
    fun theNextOperationOfAPackageWaitsWhileTheFirstWaitsForItsRetry() = runTest {
        val second = PackageSyncCommand.Consume(PACK, dose("1"), OTHER_PACK)
        val storage = Storage(listOf(operation(sequence = 0), operation(second, id = OTHER_PACK, sequence = 1)))
        val transport = transport { ApiResult.Failure(ApiFailure.OutcomeUnknown) }

        worker(storage, transport).drain()

        assertEquals(1, transport.sent.size)
        assertEquals(now.plusSeconds(2), storage.operations.getValue(INTAKE).notBefore)
        assertNull(storage.operations.getValue(OTHER_PACK).notBefore)
    }

    /** `Retry-After` переживает новый `drain`: срок лежит в базе, и до него операция не готова. */
    @Test
    fun retryAfterSurvivesAnotherDrain() = runTest {
        val storage = Storage(listOf(operation()))
        var limited = true
        val transport = transport {
            if (limited) ApiResult.Failure(ApiFailure.TooManyRequests(30.seconds)) else ApiResult.Success(RawResponse(200, snapshotJson))
        }
        worker(storage, transport).drain()
        assertEquals(now.plusSeconds(30), storage.operations.getValue(INTAKE).notBefore)

        limited = false
        worker(storage, transport, clock = Clock.fixed(now.plusSeconds(10), ZoneOffset.UTC)).drain()
        assertEquals(1, transport.sent.size)
        val inTime = worker(storage, transport, clock = Clock.fixed(now.plusSeconds(31), ZoneOffset.UTC)).drain()
        assertEquals(2, transport.sent.size)
        assertEquals(1, inTime.settled)
    }

    /** Зависимая операция уходит тем же проходом, что и её родитель: готовность перечитывается после каждого шага. */
    @Test
    fun aDependentOperationGoesOutInTheSameDrainAsItsParent() = runTest {
        val release = PackageSyncCommand.ReleaseClaim(PACK)
        val parent = operation(sync, sequence = 0)
        val dependent = SyncOperation(
            id = OTHER_PACK, command = release, sequence = 1, createdAt = EARLIER, payloadVersion = 1, dependsOn = setOf(INTAKE)
        )
        val storage = Storage(listOf(parent, dependent))
        val transport = transport { request ->
            if (request.path.endsWith("/sync/$INTAKE")) ApiResult.Success(RawResponse(200, snapshotJson))
            else ApiResult.Success(RawResponse(204, ""))
        }
        transport.snapshotAnswer = ApiResult.Success(snapshot)

        val report = worker(storage, transport).drain()

        assertEquals(2, report.settled)
        assertEquals(listOf("PUT", "DELETE"), transport.sent.map { it.method })
    }

    /** Исполнитель один: два `drain` разом не отправляют одну операцию дважды. */
    @Test
    fun concurrentDrainsSendEachOperationOnce() = runTest {
        val storage = Storage(listOf(operation()))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transport = object : QueueTransport {
            val sent = java.util.concurrent.atomic.AtomicInteger()
            override suspend fun send(request: PreparedRequest): ApiResult<RawResponse> {
                sent.incrementAndGet()
                gate.await()
                return ApiResult.Success(RawResponse(200, snapshotJson))
            }
            override suspend fun packageSnapshot(packageId: Uuid): ApiResult<PackageSnapshotNetworkDTO> = ApiResult.Success(snapshot)
            override suspend fun medKitIsOurs(medKitId: Uuid): ApiResult<Boolean> = ApiResult.Success(true)
        }
        val worker = worker(storage, transport)

        val reports = kotlinx.coroutines.coroutineScope {
            val first = async { worker.drain() }
            val second = async { worker.drain() }
            kotlinx.coroutines.yield()
            gate.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, transport.sent.get())
        assertEquals(1, reports.sumOf { it.settled })
    }

    @Test
    fun lostPackageClosesAsAccessLost() = runTest {
        val storage = Storage(listOf(operation()))
        val transport = transport { ApiResult.Failure(ApiFailure.NotFound) }

        worker(storage, transport).drain()

        assertEquals(Delivery.AccessLost, storage.settled.single().second)
    }
}
