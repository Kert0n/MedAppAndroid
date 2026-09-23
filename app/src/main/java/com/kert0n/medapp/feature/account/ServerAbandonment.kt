package com.kert0n.medapp.feature.account

import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.queue.abandoned
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import java.time.Instant
import javax.inject.Inject

/**
 * Учётки, которой сервер знал нас, больше нет (PLAN G2): каждая полка, стоящая у сервера, уходит.
 * Сначала очередь — закрытие поручения применяет свои эффекты, и унесённая домой коробка остаётся
 * у человека, — потом полка вместе с тем, что на ней осталось. Одной транзакцией. Отвечает,
 * сколько полок ушло; повтор без серверных полок ничего не делает.
 */
class ServerAbandonment @Inject constructor(
    private val medKits: MedKitStorageRepository,
    private val queue: QueueStorage,
    private val transactions: Transactions
) {

    suspend fun abandon(at: Instant): Int = transactions.run {
        val shelves = medKits.published()
        for (shelf in shelves) {
            for (stored in queue.unclosedOfMedKit(shelf)) queue.settle(stored.id, stored.abandoned(), at)
            medKits.loseAccess(shelf, at)
        }
        shelves.size
    }
}
