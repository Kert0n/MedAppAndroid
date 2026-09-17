package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.queue.Rereading
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Человек открыл вещь, по которой решает, — она перечитывается, прежде чем он решит (PLAN E4):
 * коробка на карточке и в решениях о ней, а на экране аптечек — сам список полок. Экран
 * ждёт возврата отсюда и показывает загрузку; что прочитано, приходит в него из базы само, поэтому
 * исхода дверь не возвращает: после неё экран делает одно и то же, как бы чтение ни кончилось.
 *
 * Спрашивается не всё. **Без связи** — ничего: ждать некого, и человек видит базу сразу. **Вещь,
 * которой сервер не знает**, — местная полка, коробка, ещё не доехавшая до сервера, — тоже нет:
 * её `GET` ответил бы 404, и вещь пропала бы на ровном месте. **Свежее** — прочитанное меньше
 * [FRESH_WITHIN] назад — тоже нет: путь «коробка → приём» ждёт один раз, а не дважды.
 *
 * Чтение одной вещи идёт одно: пришедший, пока оно идёт, ждёт его же. Идёт оно в области
 * приложения, а не экрана — ушедший с экрана перестаёт ждать, а чтение доживает, и его отметка
 * достаётся следующему. Свежесть помнит процесс: после его смерти первое открытие спрашивает
 * сервер. Отказ и «вещи нет» свежесть не отмечают — миг без связи не закрывает вещь на полминуты.
 */
@Singleton
class Freshening @Inject constructor(
    private val rereading: Rereading,
    private val connection: Connection,
    private val packages: PackageStorageRepository,
    private val transactions: Transactions,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val guard = Mutex()
    private val readAt = HashMap<Thing, Instant>()
    private val reading = HashMap<Thing, Deferred<Unit>>()

    /**
     * Список полок — без содержимого: в каких мы ещё есть. У человека с одними местными полками
     * ответ пустой и ничего не меняет — отдельно спрашивать, есть ли общие, ради этого незачем.
     */
    suspend fun medKits() {
        if (!connection.online.value) return
        read(Thing.Shelves) { rereading.medKits() }
    }

    /** Одна коробка. */
    suspend fun pack(packageId: Uuid) {
        if (!connection.online.value) return
        if (!transactions.run { packages.answersToServer(packageId) }) return
        read(Thing.Box(packageId)) { rereading.pack(packageId) }
    }

    /** Несколько коробок разом — источники одного лечения: ждут все вместе, а не по очереди. */
    suspend fun packs(packageIds: Set<Uuid>) = coroutineScope {
        packageIds.map { async { pack(it) } }.awaitAll()
        Unit
    }

    private suspend fun read(thing: Thing, ask: suspend () -> Rereading.Outcome) {
        val running = guard.withLock {
            val at = readAt[thing]
            if (at != null && Duration.between(at, clock.instant()) < FRESH_WITHIN) return
            reading.getOrPut(thing) { scope.async { readNow(thing, ask) } }
        }
        running.await()
    }

    private suspend fun readNow(thing: Thing, ask: suspend () -> Rereading.Outcome) {
        // Момент — до запроса: ответ говорит о том, что было, когда спросили, а не когда он пришёл.
        val at = clock.instant()
        var outcome: Rereading.Outcome? = null
        try {
            outcome = attempt { ask() }.getOrNull()
        } finally {
            withContext(NonCancellable) {
                guard.withLock {
                    if (outcome is Rereading.Outcome.Read) readAt[thing] = at
                    reading.remove(thing)
                }
            }
        }
    }

    /** Что перечитывают: список полок один, коробок много. */
    private sealed interface Thing {
        data object Shelves : Thing
        data class Box(val id: Uuid) : Thing
    }

    companion object {

        /** Сколько прочитанное считается свежим (решение владельца 2026-09-17). */
        val FRESH_WITHIN: Duration = Duration.ofSeconds(30)
    }
}
