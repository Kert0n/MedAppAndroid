package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.medkit.MedKitRef
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Держит пару «изменение и его команда»: изменение проходит через хранилище, команда встаёт в
 * очередь — одной транзакцией, которую служба открывает сама (PLAN F5). Репозитории про очередь
 * не знают, а сервер узнаёт обо всём, что случилось, когда есть связь: поставленную команду
 * забирает [QueueOutbox] после коммита, и просить об отправке никому не нужно.
 *
 * Местной аптечке команд не ставится: на сервере её нет, и везти туда нечего (PLAN E1). Полке,
 * которая к серверу только едет, ставится **объявление** — то, чем она и её содержимое станут ему
 * известны, — и то, что этим объявлением держится: создание коробки вместе с бронью её выделения
 * (PLAN E5, E6). Изменение известного туда не ставится: менять у сервера нечего, и учтено оно уже
 * местно. Это единственное, что служба решает сама; что именно изменилось, ей всё равно.
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
        if (applied) {
            // Полка изменения и есть полка его команд: порядок внутри неё держит очередь (PLAN E3).
            for (command in deliverableTo(medKit, commands)) storage.enqueue(command, medKit.id, at)
        }
        applied
    }

    /**
     * Что из поставленного уезжает этой полке. Полке, отвечающей серверу, — всё. Полке, которая к
     * нему только едет, — объявления и то, что за ними стоит: бронь выделения держится своим
     * созданием и без него ничего не значит, а порядок внутри пары уже назван зависимостью.
     */
    private fun deliverableTo(medKit: MedKitRef, commands: List<QueuedCommand>): List<QueuedCommand> {
        if (medKit.answersToServer) return commands
        if (!medKit.acceptsCommands) return emptyList()
        val announced = HashSet<Uuid>()
        return commands.filter { queued ->
            val announces = queued.command.announcesToServer || queued.dependsOn.any { it in announced }
            if (announces) announced += queued.id
            announces
        }
    }
}
