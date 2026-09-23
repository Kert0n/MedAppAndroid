package com.kert0n.medapp.storage.operation

import androidx.room.withTransaction
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
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

    override fun observeTroubles(): Flow<List<OutstandingOperation>> =
        database.observing("sync_operations", "sync_operation_dependencies", "packages", "med_kits") {
            val words = vocabulary.snapshot()
            val stored = queue.outstanding().map { it.toDomain(words) }
            val subjects = stored.mapNotNull { it.command()?.subjectId() }
            // Имена берутся двумя запросами на весь список, а не по одному на строку: строк
            // немного, но правило то же, что и у сотни полок (REQ-055).
            val names = if (subjects.isEmpty()) emptyMap() else buildMap {
                database.packages().namesOf(subjects).forEach { put(it.id, it.name) }
                database.medKits().namesOf(subjects).forEach { put(it.id, it.name) }
            }
            stored.map { it.asOutstanding(names) }
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

/**
 * Строка очереди словами человека (PLAN H3 №28): о чём шла речь, что с ней не так и что можно
 * сделать. Имя вещи берётся из её собственной таблицы — команда несёт только тождество.
 */
private fun StoredSyncOperation.asOutstanding(names: Map<Uuid, String>): OutstandingOperation {
    val command = command()
    val subjectId = command?.subjectId()
    return OutstandingOperation(
        id = id,
        // Спрашивают у самой строки, а не у её статуса: предусловие перехода — у типа
        // (C1 «Переходы операции — у типа»). Решения человека ждут ровно двое, и различает их
        // то, читается ли строка.
        trouble = when {
            this is StoredSyncOperation.Unreadable -> OutstandingOperation.Trouble.UNREADABLE
            needsDecision -> OutstandingOperation.Trouble.REFUSED
            else -> OutstandingOperation.Trouble.WAITING
        },
        subject = subjectId?.let { names[it] },
        about = command.about(),
        reason = (state.refusalReason ?: (this as? StoredSyncOperation.Unreadable)?.let { RefusalReason.UNREADABLE })
            ?.asReason(),
        retryAt = state.notBefore,
        // Пересчёт лечит расхождение по числу, и только его: чужой ход и нехватка остатка — это
        // спор о количестве коробки, а не о её сведениях (REQ-045).
        recountable = (command as? PackageSyncCommand)?.packageId
            ?.takeIf { state.refusalReason == RefusalReason.INSUFFICIENT || state.refusalReason == RefusalReason.CONFLICT }
    )
}

/** Команда строки — у собранной; у нечитаемой её нет, и спросить с неё нечего. */
private fun StoredSyncOperation.command(): SyncCommand? =
    (this as? StoredSyncOperation.Readable)?.operation?.command

private fun SyncCommand.subjectId(): Uuid? = when (this) {
    is PackageSyncCommand -> packageId
    is MedKitSyncCommand -> medKitId
    else -> null
}

private fun SyncCommand?.about(): OutstandingOperation.About = when (this) {
    is PackageSyncCommand.Create -> OutstandingOperation.About.PACKAGE_CREATED
    is PackageSyncCommand.Describe -> OutstandingOperation.About.PACKAGE_CHANGED
    is PackageSyncCommand.CorrectStock -> OutstandingOperation.About.PACKAGE_CHANGED
    is PackageSyncCommand.Move, is PackageSyncCommand.Withdraw -> OutstandingOperation.About.PACKAGE_MOVED
    is PackageSyncCommand.Delete -> OutstandingOperation.About.PACKAGE_REMOVED
    is PackageSyncCommand.Consume -> OutstandingOperation.About.INTAKE
    is PackageSyncCommand.SetClaim -> OutstandingOperation.About.CLAIM
    is MedKitSyncCommand -> OutstandingOperation.About.MED_KIT
    else -> OutstandingOperation.About.UNKNOWN
}

private fun RefusalReason.asReason(): OutstandingOperation.Reason = when (this) {
    RefusalReason.INVALID -> OutstandingOperation.Reason.INVALID
    RefusalReason.INSUFFICIENT -> OutstandingOperation.Reason.NOT_ENOUGH
    RefusalReason.UNIT_CHANGED -> OutstandingOperation.Reason.UNIT_CHANGED
    RefusalReason.STALE -> OutstandingOperation.Reason.STALE
    RefusalReason.CONFLICT -> OutstandingOperation.Reason.CONFLICT
    RefusalReason.SUPERSEDED -> OutstandingOperation.Reason.SUPERSEDED
    RefusalReason.UNREADABLE -> OutstandingOperation.Reason.UNREADABLE
}
