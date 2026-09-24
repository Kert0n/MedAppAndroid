package com.kert0n.medapp.storage.operation

import com.kert0n.medapp.queue.QueueBacklog
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/** Остаток очереди — одним запросом к её таблице (PLAN E4). */
class QueueBacklogRoomStorage @Inject constructor(
    private val queue: SyncOperationDao
) : QueueBacklog {

    override suspend fun dueAt(now: Instant, except: Set<Uuid>): Instant? =
        if (except.isEmpty()) queue.earliestDueOfUnclosed() else queue.earliestDueOfUnclosedExcept(except.toList())
}
