package com.kert0n.medapp.storage.server

import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.observing
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/**
 * Строки очереди для тех, кто их ставит и читает: поставить, найти, по статусу, сменить статус,
 * назвать нечитаемые. Транзакции работника — взятие в отправку и применение исхода с его
 * эффектами — живут в [QueueRoomStorage].
 */
class SyncOperationRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val queue: SyncOperationDao,
    private val vocabulary: VocabularyDao
) : SyncOperationStorageRepository {

    override suspend fun enqueue(
        id: Uuid,
        command: SyncCommand,
        at: Instant,
        groupId: Uuid?,
        dependsOn: Set<Uuid>
    ): SyncOperation = queue.enqueue(id, command, at, groupId, dependsOn)

    override suspend fun find(id: Uuid): SyncOperation? =
        (queue.find(id)?.toDomain(vocabulary.snapshot()) as? StoredSyncOperation.Readable)?.operation

    /** Нечитаемые сюда не попадают: их находит и называет [unreadable]. */
    override suspend fun withStatus(status: SyncOperationStatus): List<SyncOperation> =
        queue.withStatus(status).let { rows ->
            val words = vocabulary.snapshot()
            rows.mapNotNull { (it.toDomain(words) as? StoredSyncOperation.Readable)?.operation }
        }

    override suspend fun settle(
        id: Uuid,
        status: SyncOperationStatus,
        lastError: String?,
        at: Instant?,
        attempted: Boolean,
        refusalReason: RefusalReason?
    ) {
        queue.settle(id, status, lastError, at, if (attempted) 1 else 0, refusalReason = refusalReason)
    }

    override fun observeOutstanding(): Flow<List<StoredSyncOperation>> =
        database.observing("sync_operations", "sync_operation_dependencies") {
            val words = vocabulary.snapshot()
            queue.outstanding().map { it.toDomain(words) }
        }

    override suspend fun unreadable(): List<StoredSyncOperation.Unreadable> =
        queue.all().let { rows ->
            val words = vocabulary.snapshot()
            rows.mapNotNull { it.toDomain(words) as? StoredSyncOperation.Unreadable }
        }
}
