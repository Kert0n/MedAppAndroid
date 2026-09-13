package com.kert0n.medapp.storage.server

import android.database.sqlite.SQLiteConstraintException
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.rejectedByDatabase
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.PreparedRequest
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.storage.database.MedAppDatabase
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.kert0n.medapp.fixture.VOCABULARY

/**
 * Очередь и её зависимости лежат в базе: номер выдаёт она, порядок по одной пачке строится
 * запросом, а нечитаемая команда не роняет разбор всей очереди (PLAN E2, F1, F4).
 */
class SyncOperationDaoTest {

    private lateinit var database: MedAppDatabase
    private val queue get() = database.syncOperations()

    private val first: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000091")
    private val second: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000092")
    private val third: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000093")
    private val createdAt: Instant = Instant.parse("2026-09-10T12:00:00Z")

    @Before
    fun openDatabase() {
        database = inMemoryDatabase()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun enqueuedOperationComesBackWithItsCommand() = runTest {
        val command = PackageSyncCommand.Consume(PACK, dose("1.5"), INTAKE, claimAfter = tablets("4"))
        val enqueued = queue.enqueue(first, command, createdAt)

        val restored = readable(first)
        assertEquals(enqueued, restored)
        assertEquals(command, restored.command)
        assertEquals(SyncOperationStatus.PENDING, restored.status)
    }

    @Test
    fun sequenceIsHandedOutByTheDatabaseAndGrows() = runTest {
        queue.enqueue(first, PackageSyncCommand.Delete(PACK), createdAt)
        queue.enqueue(second, MedKitSyncCommand.Leave(SHARED_KIT), createdAt)

        assertEquals(listOf(0L, 1L), queue.all().map { it.operation.sequence })
    }

    /** Номер уникален: гонка двух постановок отвергается базой, а не остаётся незамеченной. */
    @Test
    fun twoOperationsCannotShareOneSequence() = runTest {
        val enqueued = queue.enqueue(first, PackageSyncCommand.Delete(PACK), createdAt)
        val clash = enqueued.let {
            SyncOperation(
                id = second,
                command = PackageSyncCommand.Delete(OTHER_PACK),
                sequence = it.sequence,
                createdAt = createdAt,
                payloadVersion = SyncCommandStorageConverter.PAYLOAD_VERSION
            )
        }

        val refusal = rejectedByDatabase { queue.insert(clash.toStorageEntity()) }
        assertTrue("$refusal", refusal is SQLiteConstraintException)
    }

    @Test
    fun simultaneousEnqueueFromTwoCoroutinesGivesTwoDistinctNumbers() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val put = listOf(first to PACK, second to OTHER_PACK).map { (id, packageId) ->
            async(Dispatchers.IO) {
                start.await()
                queue.enqueue(id, PackageSyncCommand.Delete(packageId), createdAt)
            }
        }
        start.complete(Unit)
        val numbers = put.awaitAll().map { it.sequence }

        assertEquals(2, numbers.distinct().size)
        assertEquals(listOf(0L, 1L), numbers.sorted())
    }

    /** Порядок по одной упаковке строится запросом: команды аптечки в него не попадают. */
    @Test
    fun orderOfOnePackageIsAQueryAndNotADomainFunction() = runTest {
        queue.enqueue(first, PackageSyncCommand.CorrectStock(PACK, tablets("10")), createdAt)
        queue.enqueue(second, MedKitSyncCommand.Publish(HOME_KIT), createdAt)
        queue.enqueue(third, PackageSyncCommand.ReleaseClaim(PACK), createdAt)

        val ofPack = queue.ofPackage(PACK).map { it.operation.id }
        assertEquals(listOf(first, third), ofPack)
        assertNull(queue.find(second)!!.operation.packageId)
    }

    /**
     * Полка ждёт свои коробки, коробки ждут полку: уборка ждёт расход, поставленный на её коробку
     * раньше, а расход другой коробки той же полки, поставленный позже, ждёт уборку. Чужая полка не
     * ждёт никого (PLAN E3).
     *
     * Красная проверка: порядок только по пачке пропускает полку раньше расхода.
     */
    @Test
    fun theShelfWaitsForItsBoxesAndTheBoxesWaitForTheShelf() = runTest {
        val elsewhere = Uuid.random()
        queue.enqueue(first, PackageSyncCommand.Consume(PACK, dose("2"), INTAKE), createdAt, medKitId = SHARED_KIT)
        queue.enqueue(second, MedKitSyncCommand.Delete(SHARED_KIT), createdAt)
        queue.enqueue(third, PackageSyncCommand.Consume(OTHER_PACK, dose("1"), INTAKE), createdAt, medKitId = SHARED_KIT)
        queue.enqueue(elsewhere, PackageSyncCommand.CorrectStock(Uuid.random(), tablets("3")), createdAt, medKitId = HOME_KIT)

        assertEquals(listOf(first, elsewhere), queue.ready(createdAt.plusSeconds(60)).map { it.operation.id })
        queue.settle(first, SyncOperationStatus.APPLIED, null, createdAt, attempted = 0)
        assertEquals(listOf(second, elsewhere), queue.ready(createdAt.plusSeconds(60)).map { it.operation.id })
        queue.settle(second, SyncOperationStatus.APPLIED, null, createdAt, attempted = 0)
        assertEquals(listOf(third, elsewhere), queue.ready(createdAt.plusSeconds(60)).map { it.operation.id })
    }

    /**
     * Команды **двух разных коробок** одной полки друг друга не ждут: сервер их не связывает, и
     * при связи они уезжают одним проходом (PLAN E1, E3). Коробка, отложенная до своего срока,
     * соседнюю тоже не держит.
     *
     * Красная проверка: порядок по всей полке отдаёт одну команду вместо двух.
     */
    @Test
    fun twoBoxesOfOneShelfDoNotWaitForEachOther() = runTest {
        queue.enqueue(first, PackageSyncCommand.Consume(PACK, dose("2"), INTAKE), createdAt, medKitId = SHARED_KIT)
        queue.enqueue(second, PackageSyncCommand.Consume(OTHER_PACK, dose("1"), INTAKE), createdAt, medKitId = SHARED_KIT)

        assertEquals(listOf(first, second), queue.ready(createdAt.plusSeconds(60)).map { it.operation.id })

        queue.settle(first, SyncOperationStatus.PENDING, "обрыв", createdAt, attempted = 1, notBefore = createdAt.plusSeconds(300))

        assertEquals(listOf(second), queue.ready(createdAt.plusSeconds(60)).map { it.operation.id })
    }

    @Test
    fun dependenciesTravelInTheirOwnTable() = runTest {
        queue.enqueue(first, MedKitSyncCommand.Publish(HOME_KIT), createdAt)
        queue.enqueue(
            second,
            PackageSyncCommand.Create(PACK, HOME_KIT, tablets("20"), pack().facts.shared),
            createdAt,
            dependsOn = setOf(first)
        )

        assertEquals(listOf(first), queue.dependenciesOf(second))
        assertEquals(setOf(first), readable(second).dependsOn)
    }

    @Test
    fun dependencyOnAnUnknownOperationIsRefused() = runTest {
        val refusal = rejectedByDatabase {
            queue.insertDependencies(listOf(SyncOperationDependencyStorageEntity(first, second)))
        }
        assertTrue("$refusal", refusal is SQLiteConstraintException)
    }

    @Test
    fun preparedRequestIsStoredWholeIncludingPreconditions() = runTest {
        queue.enqueue(first, PackageSyncCommand.CorrectStock(PACK, tablets("10")), createdAt)
        val prepared = PreparedRequest(
            method = "PATCH",
            path = "/drugs/$PACK",
            query = mapOf("mode" to "absolute"),
            body = """{"amount":"10"}""",
            drugVersion = ResourceVersion(7),
            claimsVersion = ResourceVersion(3),
            quantityBefore = tablets("12"),
            mineBefore = tablets("2"),
            preparedAt = createdAt
        )
        val frozen = readable(first).let {
            SyncOperation(
                id = it.id,
                command = it.command,
                sequence = it.sequence,
                createdAt = it.createdAt,
                payloadVersion = it.payloadVersion,
                prepared = prepared,
                status = SyncOperationStatus.SENDING
            )
        }
        queue.update(frozen.toStorageEntity())

        val restored = readable(first)
        assertEquals(prepared, restored.prepared)
        assertEquals(SyncOperationStatus.SENDING, restored.status)
    }

    @Test
    fun settlingRecordsStatusErrorAndAttempt() = runTest {
        queue.enqueue(first, PackageSyncCommand.Delete(PACK), createdAt)
        val at = createdAt.plusSeconds(30)

        queue.settle(first, SyncOperationStatus.PENDING, "нет ответа", at, attempted = 1)

        val stored = requireNotNull(queue.find(first)).operation
        assertEquals(SyncOperationStatus.PENDING, stored.status)
        assertEquals("нет ответа", stored.lastError)
        assertEquals(at, stored.lastTriedAt)
        assertEquals(1, stored.attempts)
    }

    /**
     * Факт «исход неизвестен» принадлежит запросу: прилипает при потерянном ответе, ставится
     * сам, когда операцию застали в отправке (процесс умер в полёте), и умирает вместе с запросом
     * при переподготовке (PLAN E3).
     */
    @Test
    fun unknownOutcomeSticksToTheRequestAndDiesWithIt() = runTest {
        queue.enqueue(first, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), createdAt)
        val frozen = queue.freeze(
            first, "PUT", "/drugs/$PACK/sync/$first", "{}", null, 3L, null, "20", null, TABLETS.id, createdAt
        )
        assertEquals(1, frozen)

        // 429 — сервер не применял: факта нет.
        queue.settle(first, SyncOperationStatus.PENDING, "429", createdAt, attempted = 1, outcomeUnknown = 0)
        assertEquals(false, readable(first).outcomeUnknown)

        // Потерянный ответ — факт есть, и следующий известный исход его не стирает.
        queue.markSending(first)
        queue.settle(first, SyncOperationStatus.PENDING, "ответ потерян", createdAt, attempted = 1, outcomeUnknown = 1)
        queue.markSending(first)
        queue.settle(first, SyncOperationStatus.PENDING, "429", createdAt, attempted = 1, outcomeUnknown = 0)
        assertEquals(true, readable(first).outcomeUnknown)

        // Переподготовка сбрасывает запрос — и факт вместе с ним; счёт попыток остаётся у операции.
        queue.markSending(first)
        queue.reprepare(first, "устарело", createdAt, notBefore = null)
        val reprepared = readable(first)
        assertEquals(false, reprepared.outcomeUnknown)
        assertEquals(3, reprepared.attempts)
    }

    /** Операция, застигнутая в отправке, — полёт, о котором никто не рассказал: исход неизвестен. */
    @Test
    fun anOperationFoundSendingIsTakenWithAnUnknownOutcome() = runTest {
        queue.enqueue(first, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), createdAt)
        queue.freeze(first, "PUT", "/drugs/$PACK/sync/$first", "{}", null, 3L, null, "20", null, TABLETS.id, createdAt)
        assertEquals(false, readable(first).outcomeUnknown)

        queue.markSending(first)

        assertEquals(true, readable(first).outcomeUnknown)
    }

    /** Ближайший срок — среди незакрытых и ещё не наступивших: закрытые и наступившие ждать не заставляют. */
    @Test
    fun nextDueAtSeesOnlyUnclosedOperationsWithATermStillAhead() = runTest {
        queue.enqueue(first, PackageSyncCommand.Delete(PACK), createdAt)
        queue.enqueue(second, PackageSyncCommand.CorrectStock(PACK, tablets("10")), createdAt)
        queue.enqueue(third, MedKitSyncCommand.Leave(SHARED_KIT), createdAt)
        queue.settle(first, SyncOperationStatus.PENDING, "429", createdAt, attempted = 1, notBefore = createdAt.plusSeconds(30))
        queue.settle(second, SyncOperationStatus.PENDING, "обрыв", createdAt, attempted = 1, notBefore = createdAt.plusSeconds(10))
        queue.settle(third, SyncOperationStatus.APPLIED, null, createdAt, attempted = 1, notBefore = createdAt.plusSeconds(5))

        assertEquals(createdAt.plusSeconds(10), queue.nextDueAt(createdAt))
        assertEquals(createdAt.plusSeconds(30), queue.nextDueAt(createdAt.plusSeconds(10)))
        assertEquals(null, queue.nextDueAt(createdAt.plusSeconds(30)))
    }

    /** Незакрытые — те, чей исход ещё не установлен: свёртка остатка берёт именно их (PLAN E1). */
    @Test
    fun unclosedOperationsExcludeTheSettledOnes() = runTest {
        queue.enqueue(first, PackageSyncCommand.CorrectStock(PACK, tablets("10")), createdAt)
        queue.enqueue(second, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), createdAt)
        queue.settle(first, SyncOperationStatus.APPLIED)

        val unclosed = queue.unclosedOfPackages(listOf(PACK))
        assertEquals(listOf(second), unclosed.map { it.operation.id })
    }

    /**
     * Строка с чужой версией payload не собирается в операцию и не роняет очередь: работник
     * её пропустит и назовёт (PLAN F4).
     */
    @Test
    fun operationWithForeignPayloadVersionReadsAsUnreadable() = runTest {
        val enqueued = queue.enqueue(first, PackageSyncCommand.Delete(PACK), createdAt)
        val stored = enqueued.toStorageEntity()
        queue.update(
            SyncOperationStorageEntity(
                id = stored.id,
                kind = stored.kind,
                payload = stored.payload,
                payloadVersion = stored.payloadVersion + 1,
                sequence = stored.sequence,
                status = stored.status,
                attempts = stored.attempts,
                createdAt = stored.createdAt,
                packageId = stored.packageId
            )
        )

        val stale = unreadable(first)
        assertTrue(stale.reason.text, stale.reason.text.contains("версии"))
        assertEquals(1, queue.all().size)
    }

    /**
     * Повреждённым может быть не только payload команды: параметры подготовленного запроса
     * восстанавливаются тем же разбором, и раньше они падали мимо защиты — вместе с поиском
     * нечитаемых строк, написанным ровно для таких случаев.
     */
    @Test
    fun damagedPreparedRequestMakesTheWholeRowUnreadable() = runTest {
        queue.enqueue(first, PackageSyncCommand.Delete(PACK), createdAt)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_operations SET prepared_method = 'DELETE', prepared_path = '/drugs/$PACK', " +
                "prepared_query = 'не json', prepared_at = 0 WHERE id = '$first'"
        )

        val damaged = unreadable(first)

        assertEquals(first, damaged.id)
        assertEquals(listOf(damaged), queue.all().mapNotNull { it.toDomain(VOCABULARY) as? StoredSyncOperation.Unreadable })
    }

    private suspend fun readable(id: Uuid): SyncOperation =
        (requireNotNull(queue.find(id)).toDomain(VOCABULARY) as StoredSyncOperation.Readable).operation

    private suspend fun unreadable(id: Uuid): StoredSyncOperation.Unreadable =
        requireNotNull(queue.find(id)).toDomain(VOCABULARY) as StoredSyncOperation.Unreadable
}
