package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.domain.medkit.MedKit
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import com.kert0n.medapp.feature.intake.UnplannedIntakeRecording
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.feature.medkits.MedKitInvitation
import com.kert0n.medapp.feature.medkits.MedKitPublishing
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.feature.packages.PackageRelocation
import com.kert0n.medapp.fixture.FakeConnection
import com.kert0n.medapp.fixture.ProbeAccounts
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.courseRepository
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.medKitRepository
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueService
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.snapshotStorage
import com.kert0n.medapp.fixture.transactions
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.medkit.ServerMedKitInvitations
import com.kert0n.medapp.network.pack.PackageSyncNetworkDTO
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.network.value.toDosageForm
import com.kert0n.medapp.network.value.toQuantityUnit
import com.kert0n.medapp.queue.PackageSnapshotResolver
import com.kert0n.medapp.queue.QueueHttpTransport
import com.kert0n.medapp.queue.QueueWorker
import com.kert0n.medapp.queue.Rereading
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.storage.medkit.toStorageEntity
import com.kert0n.medapp.storage.value.VocabularyRoomRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Открытая вещь перечитывается — по-настоящему, против боевого сервера (PLAN E4, AGENTS «Связь с
 * сервером»). Анна — устройство, собранное из частей приложения: своя база, очередь и дверь
 * [Freshening]. Борис — живой клиент со своей учёткой: он меняет коробку у себя, как сосед со
 * своего телефона, и ни одного его действия Анна не видит, пока не откроет вещь.
 *
 * Завязка одна: Анна публикует полку с коробкой на двадцать, Борис вступает по её приглашению.
 * Перечитываются коробка и список полок; содержимое полки обновляет заход (решение владельца 2026-09-17).
 * Включается только `-Pprobe`; синтетические полки убираются за собой.
 */
class FresheningProbe {

    companion object {
        private fun <T> success(result: ApiResult<T>): T = when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> throw AssertionError("ожидался успех: $result")
        }
    }

    private lateinit var anna: Anna
    private lateinit var boris: MedAppApi
    private lateinit var unit: QuantityUnit
    private lateinit var form: DosageForm
    private val shelves = mutableListOf<Uuid>()

    @Before
    fun open(): Unit = runBlocking {
        val skipReason = ProbeAccounts.skipReason
        assumeTrue(skipReason.orEmpty(), skipReason == null)
        anna = Anna(requireNotNull(ProbeAccounts.anna))
        boris = requireNotNull(ProbeAccounts.boris)
        success(anna.vocabulary.refresh())
        unit = success(anna.api.quantityUnits()).first().toQuantityUnit()
        form = success(anna.api.formTypes()).first().toDosageForm()
    }

    @After
    fun close(): Unit = runBlocking {
        if (ProbeAccounts.skipReason != null) return@runBlocking
        val left = shelves.filter { shelf ->
            anna.api.deleteMedKit(shelf)
            boris.deleteMedKit(shelf)
            listOf(anna.api, boris).any { api ->
                val seen = api.medKit(shelf)
                seen !is ApiResult.Failure || seen.failure != ApiFailure.NotFound
            }
        }
        anna.database.close()
        if (left.isNotEmpty()) throw AssertionError("синтетические полки остались на боевом сервере: $left")
    }

    private fun pills(amount: String) = Quantity(BigDecimal(amount), unit)

    private fun assertAmount(expected: String, actual: BigDecimal) =
        assertEquals("ожидалось $expected, а не $actual", 0, BigDecimal(expected).compareTo(actual))

    private class Shared(val shelf: Uuid, val box: Uuid)

    private suspend fun sharedWithBoris(): Shared {
        val shelf = anna.localShelf("Семейная")
        val box = anna.addBox(shelf, "20")
        anna.publish(shelf)
        success(boris.joinMedKit(MembershipPostNetworkDTO(anna.invite(shelf))))
        return Shared(shelf, box)
    }

    private suspend fun borisTakes(box: Uuid, amount: String) {
        val seen = success(boris.packageSnapshot(box))
        success(boris.synchronise(box, Uuid.random(), PackageSyncNetworkDTO(consumed = amount, packageVersion = seen.pack.version)))
    }

    /** Борис взял пять; Анна открыла коробку — у неё пятнадцать, хотя захода не было. */
    @Test
    fun anOpenedBoxShowsWhatTheNeighbourTook(): Unit = runBlocking {
        val shared = sharedWithBoris()
        borisTakes(shared.box, "5")
        assertAmount("20", requireNotNull(anna.packages.find(shared.box)).quantity.amount)

        anna.freshening.pack(shared.box)

        assertAmount("15", requireNotNull(anna.packages.find(shared.box)).quantity.amount)
    }

    /**
     * Анна вошла на экран аптечек — список полок говорит, что Борис вступил: участников двое. Число
     * коробки при этом прежнее: содержимое полок список не читает, его обновляет заход.
     */
    @Test
    fun theShelfListTellsWhoIsInButNotWhatIsInside(): Unit = runBlocking {
        val shared = sharedWithBoris()
        borisTakes(shared.box, "3")

        anna.freshening.medKits()

        assertEquals(2L, requireNotNull(anna.database.medKits().find(shared.shelf)).toDomain().participantCount)
        assertAmount("20", requireNotNull(anna.packages.find(shared.box)).quantity.amount)
    }

    /** Борис выбросил коробку — Анна открыла саму коробку, и её больше нет. */
    @Test
    fun aBoxThrownAwayByTheNeighbourIsGoneWhenOpened(): Unit = runBlocking {
        val shared = sharedWithBoris()
        success(boris.deletePackage(shared.box, success(boris.packageSnapshot(shared.box)).pack.version))

        anna.freshening.pack(shared.box)

        assertNull(anna.packages.find(shared.box))
    }

    /**
     * Борис удалил полку у всех — Анна вошла на экран аптечек, и полки у неё нет, и коробок с ней:
     * решать о ней ей больше нечего.
     */
    @Test
    fun aShelfRemovedForEveryoneIsGoneFromTheList(): Unit = runBlocking {
        val shared = sharedWithBoris()
        success(boris.deleteMedKit(shared.shelf))

        anna.freshening.medKits()

        assertNull(anna.database.medKits().find(shared.shelf))
        assertNull(anna.packages.find(shared.box))
    }

    /**
     * Анна пересчитала коробку, но запрос ещё не ушёл, а Борис успел взять. Открытие коробки
     * приносит подтверждённое число сервера, а её решение — пометку и строку очереди — не трогает:
     * неотправленный пересчёт уедет своим порядком и ляжет поверх нового числа (PLAN E1).
     */
    @Test
    fun anOpenedBoxKeepsOurUnsentDecision(): Unit = runBlocking {
        val shared = sharedWithBoris()
        anna.recount(shared.box, "12")
        val decided = requireNotNull(anna.packages.find(shared.box)).status
        borisTakes(shared.box, "4")

        anna.freshening.pack(shared.box)

        val after = requireNotNull(anna.packages.find(shared.box))
        assertAmount("16", after.quantity.amount)
        assertEquals(decided, after.status)
        assertEquals(listOf(SyncOperationStatus.PENDING), anna.statuses().filter { it != SyncOperationStatus.APPLIED })
    }

    /**
     * Запрос Анны уже ушёл, а ответ не лёг. Сервер мог увидеть её пересчёт, а подтверждённое число
     * о нём ещё не знает: открытие коробку не трогает, истину принесёт ответ на её запрос (PLAN E1).
     */
    @Test
    fun anOpenedBoxWithOurRequestInFlightIsLeftToItsAnswer(): Unit = runBlocking {
        val shared = sharedWithBoris()
        anna.recount(shared.box, "12")
        anna.sendWithoutAnswer()
        val before = requireNotNull(anna.packages.find(shared.box)).quantity
        borisTakes(shared.box, "4")

        anna.freshening.pack(shared.box)

        assertEquals(before, requireNotNull(anna.packages.find(shared.box)).quantity)
    }

    /**
     * Очередь Анны не пуста: разовый приём трёх ещё не уехал, а Борис взял пять. Открытие коробки
     * приносит подтверждённое сервером число, и её расход ложится поверх него **один раз**: у Анны
     * двенадцать, а не пятнадцать и не девять. Когда очередь доедет, сервер скажет то же (PLAN E1).
     */
    @Test
    fun anUnsentIntakeIsCountedOnceOverTheFreshNumber(): Unit = runBlocking {
        val shared = sharedWithBoris()
        val taken = anna.scenarios().unplannedIntakeRecording.record(shared.box, Dose(pills("3")), java.time.Instant.now())
        assertTrue("приём не записан: $taken", taken is UnplannedIntakeRecording.Outcome.Recorded)
        borisTakes(shared.box, "5")

        anna.freshening.pack(shared.box)

        assertAmount("12", anna.effective(shared.box))
        anna.drain()
        assertAmount("12", BigDecimal(success(anna.api.packageSnapshot(shared.box)).pack.amount))
        assertAmount("12", anna.effective(shared.box))
    }

    /** Устройство Анны: те же части, что у приложения, и дверь перечитывания при связи. */
    private inner class Anna(val api: MedAppApi) {
        val database = inMemoryDatabase()
        private val clock = Clock.systemUTC()
        private val transactions = database.transactions()
        val vocabulary = VocabularyResolver(VocabularyRoomRepository(database.vocabulary()), api)
        private val snapshots = PackageSnapshotResolver(vocabulary, database.queueStorage())
        private val reading = SnapshotApplier(api, database.snapshotStorage(), vocabulary, snapshots, clock)
        private val worker = QueueWorker(database.queueStorage(), QueueHttpTransport(api), vocabulary, snapshots, clock)
        val packages = database.packageRepository()
        private val medKits = database.medKitRepository()
        private val queue = database.queueService()
        private val invitation = MedKitInvitation(medKits, ServerMedKitInvitations(api), reading, Duration.ofMinutes(60), clock)
        private val relocation = PackageRelocation(packages, medKits, database.courseRepository(), queue, transactions, clock)
        private val publishing = MedKitPublishing(medKits, packages, relocation, queue, transactions, clock)
        val freshening = Freshening(
            Rereading(api, database.snapshotStorage(), snapshots, clock),
            FakeConnection(online = true),
            packages,
            transactions,
            clock,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        )

        fun scenarios() = Scenarios(database, clock.instant())

        suspend fun localShelf(name: String): Uuid {
            val id = Uuid.random()
            database.medKits().upsert(medKit(id = id, name = name).toStorageEntity())
            shelves += id
            return id
        }

        suspend fun addBox(shelf: Uuid, amount: String): Uuid {
            val id = Uuid.random()
            packages.add(pack(id = id, medKit = medKit(id = shelf).ref, quantity = pills(amount), form = form))
            return id
        }

        suspend fun publish(shelf: Uuid) {
            assertEquals(MedKitPublishing.Outcome.PUBLISHING, publishing.publish(shelf))
            drain()
            assertEquals(MedKit.Publication.PUBLISHED, requireNotNull(medKits.find(shelf)).publication)
        }

        suspend fun invite(shelf: Uuid): String {
            val outcome = invitation.invite(shelf)
            return (outcome as? MedKitInvitation.Outcome.Invited)?.invitation?.key?.value
                ?: throw AssertionError("приглашение не выдано: $outcome")
        }

        suspend fun recount(box: Uuid, actual: String) {
            val seen = requireNotNull(packages.find(box)).quantity
            assertEquals(
                PackageAdjusting.Outcome.ADJUSTED,
                scenarios().packageAdjusting.adjust(box, PackageAdjusting.Action.Recount(seen, pills(actual)))
            )
        }

        /**
         * Пересчёт взят работником — запрос ушёл, а ответа нет: переходом самой строки. Закрытые
         * строки публикации остаются в базе и в выбор не входят.
         */
        suspend fun sendWithoutAnswer() {
            val words = vocabulary.snapshot()
            val operation = database.syncOperations().all()
                .map { (it.toDomain(words) as com.kert0n.medapp.queue.StoredSyncOperation.Readable).operation }
                .single { it.status == SyncOperationStatus.PENDING }.id
            database.queueStorage().take(operation, null, clock.instant())
        }

        suspend fun effective(box: Uuid): BigDecimal =
            requireNotNull(packages.observe(box).first()).availability.effective.amount

        suspend fun drain() {
            val report = worker.drain()
            assertEquals("сбои прохода: ${report.failed}", emptyList<QueueWorker.Report.Failure>(), report.failed)
        }

        suspend fun statuses(): List<SyncOperationStatus> {
            val words = vocabulary.snapshot()
            return database.syncOperations().all()
                .map { (it.toDomain(words) as com.kert0n.medapp.queue.StoredSyncOperation.Readable).operation.status }
        }
    }
}
