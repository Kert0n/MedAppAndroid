package com.kert0n.medapp.storage.server

import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import androidx.room.withTransaction
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.observing
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/**
 * Строки очереди для тех, кто их ставит и читает: поставить, найти, по статусу, назвать
 * нечитаемые, отметить разобранной. Состояние отправки меняют только переходы операции —
 * дверь у [QueueRoomStorage]. Транзакции работника — взятие в отправку и применение исхода с его
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

    override fun observeOutstanding(): Flow<List<StoredSyncOperation>> =
        database.observing("sync_operations", "sync_operation_dependencies") {
            val words = vocabulary.snapshot()
            queue.outstanding().map { it.toDomain(words) }
        }

    override suspend fun stored(id: Uuid): StoredSyncOperation? = queue.find(id)?.toDomain(vocabulary.snapshot())

    override suspend fun dismiss(id: Uuid, at: Instant): Boolean = database.withTransaction {
        if (queue.dismiss(id, at) != 1) return@withTransaction false
        // Зависимые, закрытые следом за этой (`SUPERSEDED`), — тем же решением: отдельно их не
        // разбирают. Обход один, каждая операция в нём раз.
        for (dependent in queue.dependentsOf(id)) {
            if (dependent.refusalReason == RefusalReason.SUPERSEDED && dependent.dismissedAt == null) queue.dismiss(dependent.id, at)
        }
        true
    }

    override suspend fun unreadable(): List<StoredSyncOperation.Unreadable> =
        queue.all().let { rows ->
            val words = vocabulary.snapshot()
            rows.mapNotNull { it.toDomain(words) as? StoredSyncOperation.Unreadable }
        }
}
