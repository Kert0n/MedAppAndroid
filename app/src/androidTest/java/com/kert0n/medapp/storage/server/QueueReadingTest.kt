package com.kert0n.medapp.storage.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.LATER
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.queueRepository
import com.kert0n.medapp.fixture.queueStorage
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.network.server.ResourceVersion
import com.kert0n.medapp.queue.Delivery
import com.kert0n.medapp.queue.PackageState
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.settlement
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Очередь видна экрану состояния синхронизации: что ждёт, что отвергнуто с причиной, что нечем
 * прочитать; применённое экрану не нужно, а местная полка строк не даёт (PLAN E2, H3 №28).
 */
@RunWith(AndroidJUnit4::class)
class QueueReadingTest {

    private lateinit var database: MedAppDatabase
    private val at: Instant = Instant.parse("2026-09-10T12:00:00Z")
    private val recount: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000091")
    private val consume: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000092")
    private val delete: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000093")

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        database.medKits().upsert(medKit(publication = MedKit.Publication.PUBLISHED).toMedKitStorageEntity())
        database.packageRepository().add(
            pack(quantity = tablets("20"), form = TABLET_FORM, medKit = medKit(publication = MedKit.Publication.PUBLISHED).ref),
            PackageSyncState(PACK, ResourceVersion(3), ResourceVersion(1), at)
        )
    }

    @After
    fun closeDatabase() = database.close()

    private suspend fun outstanding(): List<StoredSyncOperation> = database.queueRepository().observeOutstanding().first()

    private suspend fun readable(id: Uuid) = outstanding().filterIsInstance<StoredSyncOperation.Readable>().single { it.id == id }.operation

    /** Отказ виден с командой, пачкой и причиной; ждущая — со сроком; применённая — нет. */
    @Test
    fun refusedWaitingAndAppliedAreToldApart() = runTest {
        val queue = database.syncOperations()
        queue.enqueue(recount, PackageSyncCommand.CorrectStock(PACK, seen = tablets("20"), actual = tablets("10")), at)
        queue.enqueue(consume, PackageSyncCommand.Consume(PACK, dose("2"), INTAKE), at)
        queue.enqueue(delete, PackageSyncCommand.Delete(PACK), at)
        database.queueStorage().settle(
            recount, Delivery.Refused(RefusalReason.CONFLICT, PackageState.None).settlement(PackageSyncCommand.CorrectStock(PACK, tablets("20"), tablets("10"))), at
        )
        queue.settle(consume, SyncOperationStatus.PENDING, "обрыв", at, attempted = 1, notBefore = at.plusSeconds(300))
        queue.settle(delete, SyncOperationStatus.APPLIED, null, at)

        val refused = readable(recount)
        assertEquals(SyncOperationStatus.REFUSED, refused.status)
        assertEquals(RefusalReason.CONFLICT, refused.refusalReason)
        assertTrue(refused.command is PackageSyncCommand.CorrectStock)
        assertEquals(PACK, (refused.command as PackageSyncCommand).packageId)
        val waiting = readable(consume)
        assertEquals(SyncOperationStatus.PENDING, waiting.status)
        assertEquals(at.plusSeconds(300), waiting.notBefore)
        assertEquals(listOf(recount, consume), outstanding().map { it.id })
    }

    /** Строка чужой версии формата — нечитаемой, с причиной, а не потерянной. */
    @Test
    fun aRowOfAForeignVersionIsShownAsUnreadable() = runTest {
        val stored = database.syncOperations().enqueue(delete, PackageSyncCommand.Delete(PACK), at).toStorageEntity()
        database.syncOperations().update(
            SyncOperationStorageEntity(
                id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = 99,
                sequence = stored.sequence, status = stored.status, attempts = stored.attempts,
                createdAt = stored.createdAt, packageId = stored.packageId
            )
        )

        val shown = outstanding().single() as StoredSyncOperation.Unreadable

        assertEquals(delete, shown.id)
        assertTrue(shown.reason is StoredSyncOperation.Reason.Format)
    }

    /** Местная полка серверу не отвечает: пересчёт на ней команд не ставит, и очередь пуста. */
    @Test
    fun aLocalShelfGivesNoRows() = runTest {
        val scenarios = Scenarios(database, LATER)
        val local = Uuid.random()
        database.packageRepository().add(pack(id = local, medKit = medKit(id = SHARED_KIT).ref, quantity = tablets("20"), form = TABLET_FORM))

        scenarios.packageAdjusting.adjust(local, PackageAdjusting.Action.Recount(seen = tablets("20"), actual = tablets("5")))

        assertEquals(emptyList<StoredSyncOperation>(), outstanding())
    }
}
