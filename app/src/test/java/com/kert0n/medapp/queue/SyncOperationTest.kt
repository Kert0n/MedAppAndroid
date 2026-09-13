package com.kert0n.medapp.queue

import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Причина отказа есть ровно у отказанной операции — держит это тип (PLAN E2). */
class SyncOperationTest {

    private fun operation(status: SyncOperationStatus, reason: RefusalReason?) = SyncOperation(
        id = Uuid.random(),
        command = PackageSyncCommand.Delete(PACK),
        sequence = 0,
        createdAt = Instant.EPOCH,
        payloadVersion = 1,
        status = status,
        refusalReason = reason
    )

    @Test
    fun theReasonComesExactlyWithARefusal() {
        assertNotNull(operation(SyncOperationStatus.REFUSED, RefusalReason.CONFLICT).refusalReason)
        assertNull(operation(SyncOperationStatus.APPLIED, null).refusalReason)
        // Красная проверка: снять require — оба соберутся.
        assertNotNull(runCatching { operation(SyncOperationStatus.REFUSED, null) }.exceptionOrNull())
        assertNotNull(runCatching { operation(SyncOperationStatus.PENDING, RefusalReason.STALE) }.exceptionOrNull())
        assertNotNull(runCatching { operation(SyncOperationStatus.ACCESS_LOST, RefusalReason.SUPERSEDED) }.exceptionOrNull())
    }
}
