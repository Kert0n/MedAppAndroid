package com.kert0n.medapp.feature.operation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.server.SyncOperationStorageEntity
import com.kert0n.medapp.storage.server.toStorageEntity
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Разобранная человеком операция уходит с экрана состояния синхронизации, а строка остаётся
 * (PLAN H3 №28, C1). Разбирается только то, что ждёт решения: отвергнутое и нечитаемое; ждущее
 * срока или применённое — не решение человека. Нечитаемое закрывается отказом той же дверью, что
 * у работника, и зависимые закрываются следом.
 */
@RunWith(AndroidJUnit4::class)
class OperationDismissingTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T12:00:00Z")

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun outstandingIds(): List<Uuid> = database.queueRepository().observeOutstanding().first().map { it.id }

    @Test
    fun aRefusedOperationIsDismissedAndTheRowStays() = runTest {
        val id = Uuid.random()
        database.syncOperations().enqueue(id, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), now)
        database.syncOperations().settle(id, SyncOperationStatus.REFUSED, "отвергнуто", now, refusalReason = RefusalReason.CONFLICT)
        assertEquals(listOf(id), outstandingIds())

        assertEquals(OperationDismissing.Outcome.DISMISSED, scenarios.operationDismissing.dismiss(id))

        assertEquals(emptyList<Uuid>(), outstandingIds())
        val row = requireNotNull(database.syncOperations().find(id)).operation
        assertEquals(SyncOperationStatus.REFUSED, row.status)
        assertEquals(RefusalReason.CONFLICT, row.refusalReason)
        assertNotNull("отметка разбора не записана", row.dismissedAt)
        // Разобранное второй раз — уже не на экране: разбирать нечего.
        assertEquals(OperationDismissing.Outcome.GONE, scenarios.operationDismissing.dismiss(id))
    }

    /**
     * Нечитаемая строка закрывается отказом `UNREADABLE`: иначе она осталась бы `PENDING` навсегда, и
     * остаток очереди звал бы заход без конца. Зависимая — `SUPERSEDED` и разобрана тем же
     * решением; учёт приёма — `REMOTE_REFUSED` той же дверью, что у работника (`QueueRoomStorageTest`).
     */
    @Test
    fun anUnreadableRowIsClosedWithItsDependents() = runTest {
        val unreadable = Uuid.random()
        val dependent = Uuid.random()
        val stored = database.syncOperations().enqueue(unreadable, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), now).toStorageEntity()
        database.syncOperations().update(
            SyncOperationStorageEntity(
                id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = 99,
                sequence = stored.sequence, status = stored.status, attempts = stored.attempts,
                createdAt = stored.createdAt, packageId = stored.packageId
            )
        )
        database.syncOperations().enqueue(dependent, PackageSyncCommand.SetClaim(PACK, tablets("2")), now, dependsOn = setOf(unreadable))
        assertTrue(database.queueRepository().stored(unreadable) is StoredSyncOperation.Unreadable)

        assertEquals(OperationDismissing.Outcome.DISMISSED, scenarios.operationDismissing.dismiss(unreadable))

        assertEquals(emptyList<Uuid>(), outstandingIds())
        val closed = requireNotNull(database.syncOperations().find(unreadable)).operation
        assertEquals(SyncOperationStatus.REFUSED, closed.status)
        assertEquals(RefusalReason.UNREADABLE, closed.refusalReason)
        val superseded = requireNotNull(database.syncOperations().find(dependent)).operation
        assertEquals(SyncOperationStatus.REFUSED, superseded.status)
        assertEquals(RefusalReason.SUPERSEDED, superseded.refusalReason)
        assertNotNull("зависимая разобрана тем же решением", superseded.dismissedAt)
    }

    /** Ждущее или применённое — не решение человека; чужой идентификатор — нечего разбирать. */
    @Test
    fun onlyWhatAwaitsADecisionCanBeDismissed() = runTest {
        val pending = Uuid.random()
        database.syncOperations().enqueue(pending, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), now)
        assertEquals(OperationDismissing.Outcome.NOT_AWAITING_DECISION, scenarios.operationDismissing.dismiss(pending))
        assertEquals(listOf(pending), outstandingIds())

        val applied = Uuid.random()
        database.syncOperations().enqueue(applied, PackageSyncCommand.Delete(PACK), now)
        database.syncOperations().settle(applied, SyncOperationStatus.APPLIED)
        assertEquals(OperationDismissing.Outcome.NOT_AWAITING_DECISION, scenarios.operationDismissing.dismiss(applied))

        assertEquals(OperationDismissing.Outcome.GONE, scenarios.operationDismissing.dismiss(Uuid.random()))
    }
}
