package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.medkit.MedKitRef
import java.time.Instant
import javax.inject.Inject

/**
 * Держит пару «изменение и его команда»: изменение проходит через хранилище, команда встаёт в
 * очередь — одной транзакцией, которую служба открывает сама (PLAN F5). Репозитории про очередь
 * не знают, а сервер узнаёт обо всём, что случилось, когда есть связь: поставленную команду
 * забирает [QueueOutbox] после коммита, и просить об отправке никому не нужно.
 *
 * Местной аптечке команд не ставится: на сервере её нет, и везти туда нечего (PLAN E1). Это
 * единственное, что служба решает сама; что именно изменилось, ей всё равно.
 */
class QueueService @Inject constructor(
    private val transactions: Transactions,
    private val storage: QueueStorage
) {

    /**
     * [change] — запись изменения; `false` значит «писать было некуда», и команды тогда тоже не
     * ставятся: расход, которого не записали, серверу не везут. Ждать отправки человеку незачем —
     * изменение уже записано, и от сети его исход не зависит (PLAN E4).
     */
    suspend fun change(
        medKit: MedKitRef,
        commands: List<QueuedCommand>,
        at: Instant,
        change: suspend () -> Boolean
    ): Boolean = transactions.run {
        val applied = change()
        if (applied && medKit.answersToServer) {
            // Полка изменения и есть полка его команд: порядок внутри неё держит очередь (PLAN E3).
            for (command in commands) storage.enqueue(command, medKit.id, at)
        }
        applied
    }
}
