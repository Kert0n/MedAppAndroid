package com.kert0n.medapp.storage.server

import com.kert0n.medapp.queue.QueueBacklog
import java.time.Instant
import javax.inject.Inject

/** Остаток очереди — одним запросом к её таблице (PLAN E4). */
class QueueBacklogRoomStorage @Inject constructor(
    private val queue: SyncOperationDao
) : QueueBacklog {

    override suspend fun dueAt(now: Instant): Instant? = queue.earliestDueOfUnclosed()
}
