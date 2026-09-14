package com.kert0n.medapp.feature.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.domain.notification.Reminder
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.Scenarios
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.server.SyncOperationStorageEntity
import com.kert0n.medapp.storage.server.toStorageEntity
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Очередь ждёт решения человека — об этом говорят одним уведомлением: новый отказ — новая карточка
 * вместо прежней, сказанное второй раз не беспокоит, решать стало нечего — снимается (PLAN D8,
 * H3 №28). Красная проверка: убрать `SYNC_ATTENTION` из сверяемых видов — снятое осталось бы навсегда.
 */
@RunWith(AndroidJUnit4::class)
class SyncAttentionTest {

    private lateinit var database: MedAppDatabase
    private lateinit var scenarios: Scenarios
    private val now: Instant = Instant.parse("2027-03-10T06:00:00Z")
    private val first = Uuid.random()
    private val second = Uuid.random()

    @Before
    fun setUp() = runTest {
        database = inMemoryDatabase()
        scenarios = Scenarios(database, now)
        database.packageRepository().add(pack(id = PACK, quantity = tablets("20"), form = TABLET_FORM))
    }

    @After
    fun tearDown() = database.close()

    private suspend fun refused(id: Uuid) {
        database.syncOperations().enqueue(id, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), now)
        database.syncOperations().settle(id, SyncOperationStatus.REFUSED, "отвергнуто", now, refusalReason = RefusalReason.INVALID)
    }

    private suspend fun attention(): List<Reminder> = scenarios.reminderStore.ofKinds(listOf(NotificationKind.SYNC_ATTENTION))

    private suspend fun reconcile() = scenarios.notificationReconciliation.reconcile(now, ZoneOffset.UTC)

    @Test
    fun aNewRefusalReplacesTheCardAndASaidOneDoesNotBother() = runTest {
        refused(first)
        reconcile()
        val promised = attention().single()
        assertEquals(NotificationKey.sync(first), promised.key)
        assertEquals(NotificationTarget.SyncStatus, promised.target)
        scenarios.reminderOutbox.pass()
        assertEquals(1, scenarios.notifier.shown.count { it.kind == NotificationKind.SYNC_ATTENTION })

        // Сказанное второй раз не говорится.
        reconcile()
        scenarios.reminderOutbox.pass()
        assertEquals(1, scenarios.notifier.shown.count { it.kind == NotificationKind.SYNC_ATTENTION })

        // Новый отказ: прежняя карточка уходит, новая говорится.
        refused(second)
        reconcile()
        scenarios.reminderOutbox.pass()
        assertEquals(listOf(NotificationKey.sync(first)), scenarios.notifier.dismissed)
        assertEquals(2, scenarios.notifier.shown.count { it.kind == NotificationKind.SYNC_ATTENTION })
        assertEquals(listOf(NotificationKey.sync(second)), attention().map { it.key })
    }

    /** Нечитаемая строка — тоже повод; стала читаемой и обычной — решать нечего, снимается. */
    @Test
    fun anUnreadableRowNeedsADecisionUntilItCanBeRead() = runTest {
        val stored = database.syncOperations().enqueue(first, PackageSyncCommand.Delete(PACK), now).toStorageEntity()
        val foreign = SyncOperationStorageEntity(
            id = stored.id, kind = stored.kind, payload = stored.payload, payloadVersion = 99,
            sequence = stored.sequence, status = stored.status, attempts = stored.attempts,
            createdAt = stored.createdAt, packageId = stored.packageId
        )
        database.syncOperations().update(foreign)
        reconcile()
        assertEquals(Reminder.State.DUE, attention().single().state)

        database.syncOperations().update(stored)
        reconcile()

        assertEquals(Reminder.State.WITHDRAWN, attention().single().state)
        scenarios.reminderOutbox.pass()
        assertNull(attention().firstOrNull())
    }

    /** Ждущая или отправляемая операция — не повод: сервер ещё не отвечал, решать нечего. */
    @Test
    fun aPendingOperationIsNotAReasonToBother() = runTest {
        database.syncOperations().enqueue(first, PackageSyncCommand.Consume(PACK, dose("1"), INTAKE), now)

        reconcile()

        assertNull(attention().firstOrNull())
    }
}
